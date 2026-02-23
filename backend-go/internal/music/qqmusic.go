package music

import (
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math/big"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	reqv3 "github.com/imroc/req/v3"
)

// SongResult is the unified search result shared between QQ and NetEase.
type SongResult struct {
	Mid      string `json:"mid"`
	Name     string `json:"name"`
	Artist   string `json:"artist"`
	Album    string `json:"album"`
	Duration int    `json:"duration"` // seconds
	Cover    string `json:"cover"`
	Platform string `json:"platform"` // "qq" or "netease"
}

// QQCredential holds QQ Music login credentials.
type QQCredential struct {
	MusicID    int    `json:"musicid"`
	MusicKey   string `json:"musickey"`
	LoginType  int    `json:"login_type"`
	CreateTime int64  `json:"musickeyCreateTime"`
	ExpiresIn  int64  `json:"keyExpiresIn"`
	// Extended fields from login response (omitempty for backward compat)
	OpenID       string `json:"openid,omitempty"`
	RefreshToken string `json:"refresh_token,omitempty"`
	AccessToken  string `json:"access_token,omitempty"`
	RefreshKey   string `json:"refresh_key,omitempty"`
	UnionID      string `json:"unionid,omitempty"`
	StrMusicID   string `json:"str_musicid,omitempty"`
	EncryptUin   string `json:"encrypt_uin,omitempty"`
	ExpiredAt    int64  `json:"expired_at,omitempty"`
}

// QQLoginType represents the QR login method.
type QQLoginType string

const (
	QQLoginTypeQQ QQLoginType = "qq"
	QQLoginTypeWX QQLoginType = "wx"
)

// QQLoginSession holds state for an in-progress QR login.
type QQLoginSession struct {
	QRSig     string
	LoginType QQLoginType
	Cookies   []*http.Cookie
}

// QQMusicClient handles QQ Music API calls.
type QQMusicClient struct {
	httpClient   *http.Client    // plain client for login flows (needs cookie jar)
	reqClient    *reqv3.Client   // TLS-fingerprinted client for API calls
	credential   *QQCredential
	credFile     string
	qimei36      string
	loginSession *QQLoginSession
	mu           sync.Mutex
}

const (
	qqAPIEndpoint     = "https://u.y.qq.com/cgi-bin/musics.fcg"  // signed endpoint
	qqAPIEndpointOpen = "https://u.y.qq.com/cgi-bin/musicu.fcg"  // unsigned endpoint
	qqStreamHost      = "https://isure.stream.qqmusic.qq.com/"
	qqUserAgent       = "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
	qqReferer         = "y.qq.com"
)

var (
	// Indexes and values from qqmusic_api/utils/sign.py
	part1Indexes   = []int{23, 14, 6, 36, 16, 40, 7, 19} // filtered < 40 at runtime
	part2Indexes   = []int{16, 1, 32, 12, 19, 27, 8, 5}
	scrambleValues = []byte{89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179}
)

// NewQQMusicClient creates a new QQ Music client, optionally loading credentials.
func NewQQMusicClient(credentialPath string) *QQMusicClient {
	c := &QQMusicClient{
		httpClient: &http.Client{Timeout: 15 * time.Second},
		reqClient: reqv3.C().
			SetTLSFingerprintChrome().
			SetTimeout(15 * time.Second).
			SetUserAgent(qqUserAgent).
			SetCommonHeader("Referer", "https://"+qqReferer),
		credFile: credentialPath,
	}
	if credentialPath != "" {
		c.loadCredential()
	}
	// Fetch QIMEI36 (cached to qimei.json alongside credential)
	c.qimei36 = getQIMEI36("qimei.json")
	log.Printf("qqmusic: QIMEI36=%s", c.qimei36)
	return c
}

func (c *QQMusicClient) loadCredential() {
	data, err := os.ReadFile(c.credFile)
	if err != nil {
		return
	}
	var cred QQCredential
	if err := json.Unmarshal(data, &cred); err != nil {
		log.Printf("qqmusic: failed to parse credential file: %v", err)
		return
	}
	c.credential = &cred
}

// IsLoggedIn returns true if valid, non-expired credentials are loaded.
func (c *QQMusicClient) IsLoggedIn() bool {
	if c.credential == nil || c.credential.MusicID == 0 || c.credential.MusicKey == "" {
		return false
	}
	if c.credential.CreateTime > 0 && c.credential.ExpiresIn > 0 {
		return time.Now().Unix() < c.credential.CreateTime+c.credential.ExpiresIn
	}
	return true
}

// SearchSongs searches QQ Music for songs matching keyword.
func (c *QQMusicClient) SearchSongs(keyword string, num int) ([]SongResult, error) {
	if num <= 0 {
		num = 10
	}
	params := map[string]interface{}{
		"searchid":     getSearchID(),
		"query":        keyword,
		"search_type":  0,
		"num_per_page": num,
		"page_num":     1,
		"highlight":    0,
		"grp":          1,
	}
	body := c.buildRequestBody("music.search.SearchCgiService", "DoSearchForQQMusicMobile", params)

	// Try unsigned endpoint first (public search doesn't need signing)
	respData, err := c.doRequestOpen(body)
	if err != nil {
		return nil, fmt.Errorf("search request failed: %w", err)
	}

	results, parseErr := c.parseSearchResponse(respData)
	if parseErr == nil && len(results) > 0 {
		return results, nil
	}

	// Fallback: try signed endpoint
	respData, err = c.doRequest(body)
	if err != nil {
		if parseErr != nil {
			return nil, fmt.Errorf("search failed (unsigned: %v, signed: %w)", parseErr, err)
		}
		return nil, fmt.Errorf("search request failed: %w", err)
	}
	return c.parseSearchResponse(respData)
}

func (c *QQMusicClient) parseSearchResponse(respData map[string]interface{}) ([]SongResult, error) {
	key := "music.search.SearchCgiService.DoSearchForQQMusicMobile"
	moduleData, ok := respData[key]
	if !ok {
		return nil, fmt.Errorf("missing response key: %s", key)
	}
	moduleMap, ok := moduleData.(map[string]interface{})
	if !ok {
		return nil, fmt.Errorf("unexpected response type for %s", key)
	}
	data, _ := moduleMap["data"].(map[string]interface{})
	if data == nil {
		return nil, fmt.Errorf("no data in search response")
	}
	bodyData, _ := data["body"].(map[string]interface{})
	if bodyData == nil {
		return nil, fmt.Errorf("no body in search data")
	}
	items, _ := bodyData["item_song"].([]interface{})

	results := make([]SongResult, 0, len(items))
	for _, item := range items {
		m, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		mid, _ := m["mid"].(string)
		name, _ := m["name"].(string)
		interval, _ := m["interval"].(float64)

		var artists []string
		if singers, ok := m["singer"].([]interface{}); ok {
			for _, s := range singers {
				if sm, ok := s.(map[string]interface{}); ok {
					if sn, ok := sm["name"].(string); ok {
						artists = append(artists, sn)
					}
				}
			}
		}

		var albumName, albumMid string
		if album, ok := m["album"].(map[string]interface{}); ok {
			albumName, _ = album["name"].(string)
			albumMid, _ = album["mid"].(string)
		}

		cover := ""
		if albumMid != "" {
			cover = fmt.Sprintf("https://y.gtimg.cn/music/photo_new/T002R300x300M000%s.jpg", albumMid)
		}

		results = append(results, SongResult{
			Mid:      mid,
			Name:     name,
			Artist:   strings.Join(artists, " / "),
			Album:    albumName,
			Duration: int(interval),
			Cover:    cover,
			Platform: "qq",
		})
	}
	return results, nil
}

// GetSongURL fetches a playable URL for the given song mid.
// Tries 320kbps first, falls back to 128kbps.
// Uses ct=19 with unsigned endpoint (musicu.fcg) per Python reference impl.
func (c *QQMusicClient) GetSongURL(mid string) (string, error) {
	guid := randomHex(32)

	// Try M800 (320k mp3) first, then M500 (128k mp3)
	qualities := []struct {
		prefix string
		ext    string
	}{
		{"M800", ".mp3"},
		{"M500", ".mp3"},
	}

	for _, q := range qualities {
		filename := fmt.Sprintf("%s%s%s%s", q.prefix, mid, mid, q.ext)
		params := map[string]interface{}{
			"filename": []string{filename},
			"guid":     guid,
			"songmid":  []string{mid},
			"songtype": []int{0},
		}
		body := c.buildRequestBody("music.vkey.GetVkey", "UrlGetVkey", params)
		// Override ct for song URL requests (ct=19 uses different auth path)
		comm := body["comm"].(map[string]interface{})
		comm["ct"] = "19"

		respData, err := c.doRequestOpen(body)
		if err != nil {
			return "", fmt.Errorf("get vkey request failed: %w", err)
		}

		key := "music.vkey.GetVkey.UrlGetVkey"
		moduleData, ok := respData[key]
		if !ok {
			continue
		}
		moduleMap, _ := moduleData.(map[string]interface{})
		data, _ := moduleMap["data"].(map[string]interface{})
		if data == nil {
			continue
		}
		midurlinfo, _ := data["midurlinfo"].([]interface{})
		if len(midurlinfo) == 0 {
			continue
		}
		info, _ := midurlinfo[0].(map[string]interface{})
		// Check purl first, then fallback to wifiurl
		purl, _ := info["purl"].(string)
		if purl != "" {
			return qqStreamHost + purl, nil
		}
		wifiurl, _ := info["wifiurl"].(string)
		if wifiurl != "" {
			return qqStreamHost + wifiurl, nil
		}
	}
	return "", fmt.Errorf("no playable URL found for mid: %s", mid)
}

// hash33 computes the QQ ptlogin hash function.
func hash33(s string, init int) int {
	h := init
	for _, c := range s {
		h = (h << 5) + h + int(c)
	}
	return h & 0x7FFFFFFF
}

// GetQRCode fetches a QR code image for login. Returns image data, mimetype, and login type.
func (c *QQMusicClient) GetQRCode(loginType QQLoginType) ([]byte, string, error) {
	if loginType == QQLoginTypeWX {
		return c.getWXQRCode()
	}
	return c.getQQQRCode()
}

// getQQQRCode fetches a QR code image for QQ login.
func (c *QQMusicClient) getQQQRCode() ([]byte, string, error) {
	jar, _ := cookiejar.New(nil)
	loginClient := &http.Client{Jar: jar, Timeout: 15 * time.Second}

	params := url.Values{
		"appid":      {"716027609"},
		"e":          {"2"},
		"l":          {"M"},
		"s":          {"3"},
		"d":          {"72"},
		"v":          {"4"},
		"t":          {strconv.FormatInt(time.Now().UnixMilli(), 10)},
		"daid":       {"383"},
		"pt_3rd_aid": {"100497308"},
	}
	req, _ := http.NewRequest("GET", "https://ssl.ptlogin2.qq.com/ptqrshow?"+params.Encode(), nil)
	req.Header.Set("Referer", "https://xui.ptlogin2.qq.com/")

	resp, err := loginClient.Do(req)
	if err != nil {
		return nil, "", fmt.Errorf("get qr code: %w", err)
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, "", err
	}

	// Extract qrsig cookie
	var qrsig string
	u, _ := url.Parse("https://ssl.ptlogin2.qq.com")
	for _, ck := range jar.Cookies(u) {
		if ck.Name == "qrsig" {
			qrsig = ck.Value
		}
	}
	if qrsig == "" {
		return nil, "", fmt.Errorf("no qrsig cookie in response")
	}

	c.mu.Lock()
	c.loginSession = &QQLoginSession{QRSig: qrsig, LoginType: QQLoginTypeQQ}
	c.mu.Unlock()
	return data, "image/png", nil
}

// getWXQRCode fetches a QR code image for WeChat login.
func (c *QQMusicClient) getWXQRCode() ([]byte, string, error) {
	session := &http.Client{Timeout: 15 * time.Second}

	// Get the WeChat QR connect page to extract uuid
	params := url.Values{
		"appid":        {"wx48db31d50e334801"},
		"redirect_uri": {"https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/"},
		"response_type": {"code"},
		"scope":        {"snsapi_login"},
		"state":        {"STATE"},
		"href":         {"https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect"},
	}
	req, _ := http.NewRequest("GET", "https://open.weixin.qq.com/connect/qrconnect?"+params.Encode(), nil)
	resp, err := session.Do(req)
	if err != nil {
		return nil, "", fmt.Errorf("get wx qr page: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, "", fmt.Errorf("read wx qr page: %w", err)
	}

	uuidRe := regexp.MustCompile(`uuid=(.+?)"`)
	uuidMatches := uuidRe.FindSubmatch(body)
	if len(uuidMatches) < 2 {
		return nil, "", fmt.Errorf("no uuid in wx qr page")
	}
	uuid := string(uuidMatches[1])

	// Fetch the actual QR code image
	req, _ = http.NewRequest("GET", "https://open.weixin.qq.com/connect/qrcode/"+uuid, nil)
	req.Header.Set("Referer", "https://open.weixin.qq.com/connect/qrconnect")
	resp2, err := session.Do(req)
	if err != nil {
		return nil, "", fmt.Errorf("get wx qr image: %w", err)
	}
	defer resp2.Body.Close()
	imgData, err := io.ReadAll(resp2.Body)
	if err != nil {
		return nil, "", fmt.Errorf("read wx qr image: %w", err)
	}

	c.mu.Lock()
	c.loginSession = &QQLoginSession{QRSig: uuid, LoginType: QQLoginTypeWX}
	c.mu.Unlock()
	return imgData, "image/jpeg", nil
}

// CheckQRStatus checks QR login status. Returns "waiting", "scanned", "success", "expired", "refused".
func (c *QQMusicClient) CheckQRStatus() (string, error) {
	c.mu.Lock()
	if c.loginSession == nil || c.loginSession.QRSig == "" {
		c.mu.Unlock()
		return "", fmt.Errorf("no active QR login session")
	}
	lt := c.loginSession.LoginType
	qrsig := c.loginSession.QRSig
	c.mu.Unlock()

	if lt == QQLoginTypeWX {
		return c.checkWXQRStatus(qrsig)
	}
	return c.checkQQQRStatus(qrsig)
}

func (c *QQMusicClient) checkQQQRStatus(qrsig string) (string, error) {

	ptqrtoken := hash33(qrsig, 0)
	params := url.Values{
		"u1":          {"https://graph.qq.com/oauth2.0/login_jump"},
		"ptqrtoken":   {strconv.Itoa(ptqrtoken)},
		"ptredirect":  {"0"},
		"h":           {"1"},
		"t":           {"1"},
		"g":           {"1"},
		"from_ui":     {"1"},
		"ptlang":      {"2052"},
		"action":      {"0-0-" + strconv.FormatInt(time.Now().UnixMilli(), 10)},
		"js_ver":      {"20102616"},
		"js_type":     {"1"},
		"pt_uistyle":  {"40"},
		"aid":         {"716027609"},
		"daid":        {"383"},
		"pt_3rd_aid":  {"100497308"},
		"has_onekey":  {"1"},
	}

	req, _ := http.NewRequest("GET", "https://ssl.ptlogin2.qq.com/ptqrlogin?"+params.Encode(), nil)
	req.Header.Set("Referer", "https://xui.ptlogin2.qq.com/")
	req.AddCookie(&http.Cookie{Name: "qrsig", Value: qrsig})

	// Don't follow redirects
	noRedirectClient := &http.Client{
		Timeout:       15 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error { return http.ErrUseLastResponse },
	}
	resp, err := noRedirectClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("check qr: %w", err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	bodyStr := string(body)

	// Parse ptuiCB('code', ...)
	re := regexp.MustCompile(`ptuiCB\('(\d+)'`)
	matches := re.FindStringSubmatch(bodyStr)
	if len(matches) < 2 {
		return "", fmt.Errorf("unexpected response: %s", bodyStr[:min(len(bodyStr), 200)])
	}
	code := matches[1]

	switch code {
	case "66", "408":
		return "waiting", nil
	case "67", "404":
		return "scanned", nil
	case "65":
		c.mu.Lock()
		c.loginSession = nil
		c.mu.Unlock()
		return "expired", nil
	case "68", "403":
		c.mu.Lock()
		c.loginSession = nil
		c.mu.Unlock()
		return "refused", nil
	case "0", "405":
		// Success - extract sigx URL
		urlRe := regexp.MustCompile(`ptuiCB\('\d+','[^']*','([^']*)'`)
		urlMatches := urlRe.FindStringSubmatch(bodyStr)
		if len(urlMatches) < 2 || urlMatches[1] == "" {
			return "", fmt.Errorf("no redirect URL in success response")
		}
		// Complete the OAuth flow
		if err := c.completeQQLogin(urlMatches[1], resp.Cookies()); err != nil {
			return "", fmt.Errorf("complete login: %w", err)
		}
		c.mu.Lock()
		c.loginSession = nil
		c.mu.Unlock()
		return "success", nil
	}
	return "", fmt.Errorf("unknown ptqrlogin code: %s", code)
}

// completeQQLogin finishes the OAuth flow after QR scan success.
func (c *QQMusicClient) completeQQLogin(sigURL string, cookies []*http.Cookie) error {
	jar, _ := cookiejar.New(nil)
	oauthClient := &http.Client{
		Jar:     jar,
		Timeout: 15 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error { return http.ErrUseLastResponse },
	}

	// Step 3: check_sig → get p_skey
	req, _ := http.NewRequest("GET", sigURL, nil)
	req.Header.Set("Referer", "https://xui.ptlogin2.qq.com/")
	for _, ck := range cookies {
		req.AddCookie(ck)
	}
	resp, err := oauthClient.Do(req)
	if err != nil {
		return fmt.Errorf("check_sig: %w", err)
	}
	resp.Body.Close()

	// Extract p_skey from cookies
	var pSkey string
	graphURL, _ := url.Parse("https://graph.qq.com")
	for _, ck := range jar.Cookies(graphURL) {
		if ck.Name == "p_skey" {
			pSkey = ck.Value
		}
	}
	// Also check response cookies directly
	for _, ck := range resp.Cookies() {
		if ck.Name == "p_skey" {
			pSkey = ck.Value
		}
	}
	if pSkey == "" {
		return fmt.Errorf("no p_skey cookie after check_sig")
	}

	gtk := hash33(pSkey, 5381)

	// Step 4: OAuth authorize → get code
	form := url.Values{
		"response_type": {"code"},
		"client_id":     {"100497308"},
		"redirect_uri":  {"https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/"},
		"scope":         {"get_user_info,get_app_friends"},
		"state":         {"state"},
		"switch":        {""},
		"from_ptlogin":  {"1"},
		"src":           {"1"},
		"update_auth":   {"1"},
		"openapi":       {"1010_1030"},
		"g_tk":          {strconv.Itoa(gtk)},
		"auth_time":     {strconv.FormatInt(time.Now().UnixMilli(), 10)},
		"ui":            {randomHex(32)},
	}
	req, _ = http.NewRequest("POST", "https://graph.qq.com/oauth2.0/authorize", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Referer", "https://graph.qq.com")
	resp, err = oauthClient.Do(req)
	if err != nil {
		return fmt.Errorf("authorize: %w", err)
	}
	resp.Body.Close()

	loc := resp.Header.Get("Location")
	if loc == "" {
		return fmt.Errorf("no redirect from authorize")
	}
	parsedLoc, _ := url.Parse(loc)
	code := parsedLoc.Query().Get("code")
	if code == "" {
		return fmt.Errorf("no code in authorize redirect: %s", loc)
	}

	// Step 5: QQ Music API login with code
	body := c.buildRequestBody("QQConnectLogin.LoginServer", "QQLogin", map[string]interface{}{
		"code": code,
	})
	// Override comm for login
	comm := body["comm"].(map[string]interface{})
	comm["tmeLoginType"] = "2"

	respData, err := c.doRequest(body)
	if err != nil {
		return fmt.Errorf("qqlogin api: %w", err)
	}

	key := "QQConnectLogin.LoginServer.QQLogin"
	moduleData, ok := respData[key]
	if !ok {
		return fmt.Errorf("missing key %s in login response", key)
	}
	moduleMap, _ := moduleData.(map[string]interface{})
	respCode, _ := moduleMap["code"].(float64)
	if int(respCode) != 0 {
		return fmt.Errorf("login failed with code %d", int(respCode))
	}
	data, _ := moduleMap["data"].(map[string]interface{})
	if data == nil {
		return fmt.Errorf("no data in login response")
	}

	musicID, _ := data["musicid"].(float64)
	musicKey, _ := data["musickey"].(string)
	createTime, _ := data["musickeyCreateTime"].(float64)
	expiresIn, _ := data["keyExpiresIn"].(float64)

	cred := &QQCredential{
		MusicID:    int(musicID),
		MusicKey:   musicKey,
		LoginType:  2,
		CreateTime: int64(createTime),
		ExpiresIn:  int64(expiresIn),
	}
	populateExtendedCredFields(cred, data)
	c.credential = cred
	return c.SaveCredential()
}

// checkWXQRStatus checks WeChat QR login status.
func (c *QQMusicClient) checkWXQRStatus(uuid string) (string, error) {
	session := &http.Client{Timeout: 15 * time.Second}

	req, _ := http.NewRequest("GET", "https://lp.open.weixin.qq.com/connect/l/qrconnect?uuid="+uuid+"&_="+strconv.FormatInt(time.Now().UnixMilli(), 10), nil)
	req.Header.Set("Referer", "https://open.weixin.qq.com/")
	resp, err := session.Do(req)
	if err != nil {
		return "waiting", nil // timeout = still waiting
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	bodyStr := string(body)

	re := regexp.MustCompile(`window\.wx_errcode=(\d+);window\.wx_code='([^']*)'`)
	matches := re.FindStringSubmatch(bodyStr)
	if len(matches) < 3 {
		return "waiting", nil
	}

	wxErrcode := matches[1]
	wxCode := matches[2]

	switch wxErrcode {
	case "408":
		return "waiting", nil
	case "404":
		return "scanned", nil
	case "405":
		if wxCode == "" {
			return "", fmt.Errorf("wx login: got success but no code")
		}
		if err := c.completeWXLogin(wxCode); err != nil {
			return "", fmt.Errorf("complete wx login: %w", err)
		}
		c.mu.Lock()
		c.loginSession = nil
		c.mu.Unlock()
		return "success", nil
	case "403":
		c.mu.Lock()
		c.loginSession = nil
		c.mu.Unlock()
		return "refused", nil
	}
	return "waiting", nil
}

// completeWXLogin finishes the WeChat OAuth flow.
func (c *QQMusicClient) completeWXLogin(wxCode string) error {
	body := c.buildRequestBody("music.login.LoginServer", "Login", map[string]interface{}{
		"code":      wxCode,
		"strAppid":  "wx48db31d50e334801",
	})
	comm := body["comm"].(map[string]interface{})
	comm["tmeLoginType"] = "1"

	respData, err := c.doRequest(body)
	if err != nil {
		return fmt.Errorf("wx login api: %w", err)
	}

	key := "music.login.LoginServer.Login"
	moduleData, ok := respData[key]
	if !ok {
		return fmt.Errorf("missing key %s in login response", key)
	}
	moduleMap, _ := moduleData.(map[string]interface{})
	respCode, _ := moduleMap["code"].(float64)
	if int(respCode) != 0 {
		return fmt.Errorf("wx login failed with code %d", int(respCode))
	}
	data, _ := moduleMap["data"].(map[string]interface{})
	if data == nil {
		return fmt.Errorf("no data in wx login response")
	}

	musicID, _ := data["musicid"].(float64)
	musicKey, _ := data["musickey"].(string)
	createTime, _ := data["musickeyCreateTime"].(float64)
	expiresIn, _ := data["keyExpiresIn"].(float64)

	cred := &QQCredential{
		MusicID:    int(musicID),
		MusicKey:   musicKey,
		LoginType:  1,
		CreateTime: int64(createTime),
		ExpiresIn:  int64(expiresIn),
	}
	populateExtendedCredFields(cred, data)
	c.credential = cred
	return c.SaveCredential()
}

// effectiveUin returns the best UIN string for API requests.
// Prefers str_musicid (safe for large WeChat musicid values).
func (c *QQMusicClient) effectiveUin() string {
	if c.credential != nil && c.credential.StrMusicID != "" {
		return c.credential.StrMusicID
	}
	if c.credential != nil {
		return strconv.Itoa(c.credential.MusicID)
	}
	return "0"
}

// populateExtendedCredFields extracts extended fields from a login response into cred.
func populateExtendedCredFields(cred *QQCredential, data map[string]interface{}) {
	if v, ok := data["openid"].(string); ok {
		cred.OpenID = v
	}
	if v, ok := data["refresh_token"].(string); ok {
		cred.RefreshToken = v
	}
	if v, ok := data["access_token"].(string); ok {
		cred.AccessToken = v
	}
	if v, ok := data["refresh_key"].(string); ok {
		cred.RefreshKey = v
	}
	if v, ok := data["unionid"].(string); ok {
		cred.UnionID = v
	}
	if v, ok := data["str_musicid"].(string); ok {
		cred.StrMusicID = v
	}
	if v, ok := data["encrypt_uin"].(string); ok {
		cred.EncryptUin = v
	}
	if v, ok := data["expired_at"].(float64); ok {
		cred.ExpiredAt = int64(v)
	}
}

// SaveCredential persists the credential to disk.
func (c *QQMusicClient) SaveCredential() error {
	if c.credential == nil || c.credFile == "" {
		return nil
	}
	data, err := json.MarshalIndent(c.credential, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(c.credFile, data, 0600)
}

// buildRequestBody constructs the JSON request body with comm block.
func (c *QQMusicClient) buildRequestBody(module, method string, params map[string]interface{}) map[string]interface{} {
	comm := map[string]interface{}{
		"ct":         "11",
		"cv":         13020508,
		"v":          13020508,
		"QIMEI36":    c.qimei36,
		"tmeAppID":   "qqmusic",
		"format":     "json",
		"inCharset":  "utf-8",
		"outCharset": "utf-8",
		"uid":        "3931641530",
	}
	if c.credential != nil && c.credential.MusicID != 0 && c.credential.MusicKey != "" {
		comm["qq"] = c.effectiveUin()
		comm["authst"] = c.credential.MusicKey
		comm["tmeLoginType"] = strconv.Itoa(c.credential.LoginType)
	}

	reqKey := module + "." + method
	return map[string]interface{}{
		"comm": comm,
		reqKey: map[string]interface{}{
			"module": module,
			"method": method,
			"param":  params,
		},
	}
}

// doRequest signs, sends, and parses a QQ Music API request (signed endpoint).
func (c *QQMusicClient) doRequest(body map[string]interface{}) (map[string]interface{}, error) {
	return c.doRequestTo(qqAPIEndpoint, body, true)
}

// doRequestOpen sends a request to the unsigned endpoint (no sign param).
func (c *QQMusicClient) doRequestOpen(body map[string]interface{}) (map[string]interface{}, error) {
	return c.doRequestTo(qqAPIEndpointOpen, body, false)
}

func (c *QQMusicClient) doRequestTo(endpoint string, body map[string]interface{}, sign bool) (map[string]interface{}, error) {
	jsonBytes, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}

	reqURL := endpoint
	if sign {
		reqURL += "?sign=" + qqSign(jsonBytes)
	}

	r := c.reqClient.R().SetBodyJsonBytes(jsonBytes)
	// Inject auth cookies (required by QQ Music API alongside JSON comm credentials)
	if c.credential != nil && c.credential.MusicID != 0 && c.credential.MusicKey != "" {
		r.SetCookies(
			&http.Cookie{Name: "uin", Value: c.effectiveUin()},
			&http.Cookie{Name: "qqmusic_key", Value: c.credential.MusicKey},
			&http.Cookie{Name: "qm_keyst", Value: c.credential.MusicKey},
			&http.Cookie{Name: "tmeLoginType", Value: strconv.Itoa(c.credential.LoginType)},
		)
	}
	resp, err := r.Post(reqURL)
	if err != nil {
		return nil, err
	}

	var result map[string]interface{}
	if err := json.Unmarshal(resp.Bytes(), &result); err != nil {
		return nil, fmt.Errorf("failed to parse response: %w", err)
	}
	return result, nil
}

// qqSign computes the sign for a QQ Music API request body.
// Ported from qqmusic_api/utils/sign.py
func qqSign(jsonBody []byte) string {
	h := sha1.Sum(jsonBody)
	hash := strings.ToUpper(hex.EncodeToString(h[:]))

	// part1: hash[i] for i in part1Indexes where i < 40
	var part1 strings.Builder
	for _, idx := range part1Indexes {
		if idx < len(hash) {
			part1.WriteByte(hash[idx])
		}
	}

	// part2: hash[i] for i in part2Indexes
	var part2 strings.Builder
	for _, idx := range part2Indexes {
		if idx < len(hash) {
			part2.WriteByte(hash[idx])
		}
	}

	// part3: XOR scramble
	part3 := make([]byte, 20)
	for i, v := range scrambleValues {
		hexStr := hash[i*2 : i*2+2]
		val, _ := strconv.ParseUint(hexStr, 16, 8)
		part3[i] = v ^ byte(val)
	}

	// base64 encode, remove /\+=
	b64 := base64.StdEncoding.EncodeToString(part3)
	b64 = strings.NewReplacer("/", "", "\\", "", "+", "", "=", "").Replace(b64)

	return strings.ToLower("zzc" + part1.String() + b64 + part2.String())
}

// randomHex returns n random hex characters.
func randomHex(n int) string {
	const hexChars = "abcdef0123456789"
	b := make([]byte, n)
	for i := range b {
		idx, _ := rand.Int(rand.Reader, big.NewInt(int64(len(hexChars))))
		b[i] = hexChars[idx.Int64()]
	}
	return string(b)
}

// getSearchID generates a random search ID matching the Python implementation.
func getSearchID() string {
	e, _ := rand.Int(rand.Reader, big.NewInt(20))
	eVal := e.Int64() + 1
	t := eVal * 18014398509481984

	n, _ := rand.Int(rand.Reader, big.NewInt(4194304))
	nVal := n.Int64() * 4294967296

	r := time.Now().UnixMilli() % (24 * 60 * 60 * 1000)
	return strconv.FormatInt(t+nVal+r, 10)
}

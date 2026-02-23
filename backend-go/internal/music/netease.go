package music

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"math/rand"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	reqv3 "github.com/imroc/req/v3"
)

// Netease crypto constants
const (
	weapiAESKey = "0CoJUm6Qyw8W8jud"
	weapiAESIV  = "0102030405060708"
	eapiAESKey  = "e82ckenh8dichen8"

	weapiRSAModulus = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
	weapiRSAPubExp  = "010001"

	randomChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

	// Match pyncm's User-Agent exactly
	neteaseUAWeapi = "Mozilla/5.0 (linux@github.com/mos9527/pyncm) Chrome/PyNCM.1.8.1"
	neteaseUAEapi  = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154"
)

// NeteaseClient implements the NetEase Cloud Music API.
type NeteaseClient struct {
	reqClient  *reqv3.Client   // TLS-fingerprinted client for API calls
	jar        http.CookieJar  // shared cookie jar
	csrf       string
	credFile   string
	sDeviceId  string
	wnmcid     string
	mu         sync.Mutex
}

func generateWNMCID() string {
	b := make([]byte, 6)
	for i := range b {
		b[i] = "abcdefghijklmnopqrstuvwxyz"[rand.Intn(26)]
	}
	return fmt.Sprintf("%s.%d.01.0", string(b), time.Now().UnixMilli())
}

func NewNeteaseClient(credentialPath string) *NeteaseClient {
	jar, _ := cookiejar.New(nil)
	c := &NeteaseClient{
		reqClient: reqv3.C().
			SetTimeout(15 * time.Second),
		jar:       jar,
		credFile:  credentialPath,
		sDeviceId: fmt.Sprintf("unknown-%d", rand.Intn(1000000)),
		wnmcid:    generateWNMCID(),
	}
	if credentialPath != "" {
		if err := c.LoadCredential(); err != nil {
			// No saved credential; start with clean jar (matches pyncm behavior)
		}
	}
	return c
}

// --- WEAPI encryption ---

func pkcs7Pad(data []byte, blockSize int) []byte {
	pad := blockSize - len(data)%blockSize
	padding := make([]byte, pad)
	for i := range padding {
		padding[i] = byte(pad)
	}
	return append(data, padding...)
}

func pkcs7Unpad(data []byte) []byte {
	if len(data) == 0 {
		return data
	}
	pad := int(data[len(data)-1])
	if pad > len(data) || pad > aes.BlockSize || pad == 0 {
		return data
	}
	return data[:len(data)-pad]
}

func aesEncryptCBC(plaintext, key, iv []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	padded := pkcs7Pad(plaintext, aes.BlockSize)
	ct := make([]byte, len(padded))
	cipher.NewCBCEncrypter(block, iv).CryptBlocks(ct, padded)
	return ct, nil
}

func aesEncryptECB(plaintext, key []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	padded := pkcs7Pad(plaintext, aes.BlockSize)
	ct := make([]byte, len(padded))
	for i := 0; i < len(padded); i += aes.BlockSize {
		block.Encrypt(ct[i:i+aes.BlockSize], padded[i:i+aes.BlockSize])
	}
	return ct, nil
}

func aesDecryptECB(ciphertext, key []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	if len(ciphertext)%aes.BlockSize != 0 {
		return nil, fmt.Errorf("ciphertext not multiple of block size")
	}
	pt := make([]byte, len(ciphertext))
	for i := 0; i < len(ciphertext); i += aes.BlockSize {
		block.Decrypt(pt[i:i+aes.BlockSize], ciphertext[i:i+aes.BlockSize])
	}
	return pkcs7Unpad(pt), nil
}

func randomString(n int) string {
	b := make([]byte, n)
	for i := range b {
		b[i] = randomChars[rand.Intn(len(randomChars))]
	}
	return string(b)
}

// rsaEncrypt performs textbook RSA (no padding) on reversed key bytes.
func rsaEncrypt(text string, modulus, exponent string) string {
	// Reverse the text bytes (matching pyncm behavior)
	tb := []byte(text)
	for i, j := 0, len(tb)-1; i < j; i, j = i+1, j-1 {
		tb[i], tb[j] = tb[j], tb[i]
	}
	m := new(big.Int).SetBytes(tb)
	n, _ := new(big.Int).SetString(modulus, 16)
	e, _ := new(big.Int).SetString(exponent, 16)
	r := new(big.Int).Exp(m, e, n)
	return fmt.Sprintf("%0256x", r)
}

func weapiEncrypt(jsonParams string) url.Values {
	iv := []byte(weapiAESIV)
	key1 := []byte(weapiAESKey)

	// First AES pass
	ct1, _ := aesEncryptCBC([]byte(jsonParams), key1, iv)
	b64_1 := base64Encode(ct1)

	// Second AES pass with random key
	secKey := randomString(16)
	ct2, _ := aesEncryptCBC([]byte(b64_1), []byte(secKey), iv)
	params := base64Encode(ct2)

	encSecKey := rsaEncrypt(secKey, weapiRSAModulus, weapiRSAPubExp)

	return url.Values{
		"params":    {params},
		"encSecKey": {encSecKey},
	}
}

// --- EAPI encryption ---

func eapiEncrypt(urlPath, jsonText string) url.Values {
	h := md5.Sum([]byte("nobody" + urlPath + "use" + jsonText + "md5forencrypt"))
	digest := hex.EncodeToString(h[:])
	message := urlPath + "-36cd479b6b5-" + jsonText + "-36cd479b6b5-" + digest
	ct, _ := aesEncryptECB([]byte(message), []byte(eapiAESKey))
	return url.Values{
		"params": {hex.EncodeToString(ct)},
	}
}

func eapiDecrypt(data []byte) ([]byte, error) {
	return aesDecryptECB(data, []byte(eapiAESKey))
}

// base64Encode without stdlib encoding/base64 import - use a simple implementation
// Actually let's just use encoding/base64 via import.
func base64Encode(data []byte) string {
	// Standard base64 encoding
	const enc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
	var sb strings.Builder
	for i := 0; i < len(data); i += 3 {
		var b0, b1, b2 byte
		b0 = data[i]
		if i+1 < len(data) {
			b1 = data[i+1]
		}
		if i+2 < len(data) {
			b2 = data[i+2]
		}
		sb.WriteByte(enc[b0>>2])
		sb.WriteByte(enc[((b0&0x03)<<4)|(b1>>4)])
		if i+1 < len(data) {
			sb.WriteByte(enc[((b1&0x0f)<<2)|(b2>>6)])
		} else {
			sb.WriteByte('=')
		}
		if i+2 < len(data) {
			sb.WriteByte(enc[b2&0x3f])
		} else {
			sb.WriteByte('=')
		}
	}
	return sb.String()
}

// --- HTTP helpers ---

// injectCookies copies all cookies from the shared jar into a reqv3 request.
func (c *NeteaseClient) injectCookies(r *reqv3.Request) {
	u, _ := url.Parse("https://music.163.com")
	for _, ck := range c.jar.Cookies(u) {
		r.SetCookies(&http.Cookie{Name: ck.Name, Value: ck.Value})
	}
}

// collectCookies saves response Set-Cookie headers back into the shared jar.
func (c *NeteaseClient) collectCookies(resp *reqv3.Response) {
	cookies := resp.Cookies()
	if len(cookies) == 0 {
		return
	}
	u, _ := url.Parse("https://music.163.com")
	var toSet []*http.Cookie
	for _, ck := range cookies {
		if ck.Domain == "" {
			ck.Domain = ".music.163.com"
		}
		if ck.Path == "" {
			ck.Path = "/"
		}
		toSet = append(toSet, ck)
		if ck.Name == "__csrf" {
			c.csrf = ck.Value
		}
	}
	c.jar.SetCookies(u, toSet)
}

func (c *NeteaseClient) weapiRequest(endpoint string, params map[string]interface{}) (map[string]interface{}, error) {
	params["csrf_token"] = c.csrf
	jsonBytes, err := json.Marshal(params)
	if err != nil {
		return nil, err
	}
	form := weapiEncrypt(string(jsonBytes))

	// Build URL with csrf_token query param (matches pyncm behavior)
	reqURL := "https://music.163.com" + endpoint + "?csrf_token=" + c.csrf

	r := c.reqClient.R().
		SetHeaders(map[string]string{
			"Content-Type": "application/x-www-form-urlencoded",
			"User-Agent":   neteaseUAWeapi,
			"Referer":      "https://music.163.com",
		}).
		SetBodyString(form.Encode())
	c.injectCookies(r)
	// Inject eapi_config as cookies (matches pyncm WeapiCryptoRequest)
	for _, ck := range c.eapiConfigCookies() {
		r.SetCookies(ck)
	}

	resp, err := r.Post(reqURL)
	if err != nil {
		return nil, err
	}
	c.collectCookies(resp)

	body := resp.Bytes()
	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("weapi json decode: %w, body: %s", err, string(body[:min(len(body), 200)]))
	}
	return result, nil
}

// eapiConfigCookies returns the eapi_config values as cookies, matching pyncm behavior.
func (c *NeteaseClient) eapiConfigCookies() []*http.Cookie {
	return []*http.Cookie{
		{Name: "os", Value: "iPhone OS"},
		{Name: "appver", Value: "10.0.0"},
		{Name: "osver", Value: "16.2"},
		{Name: "channel", Value: "distribution"},
		{Name: "deviceId", Value: "pyncm!"},
	}
}

func (c *NeteaseClient) eapiRequest(endpoint, apiPath string, params map[string]interface{}) (map[string]interface{}, error) {
	// Build header JSON with requestId (matches pyncm EapiCryptoRequest)
	header := map[string]interface{}{
		"os":        "iPhone OS",
		"appver":    "10.0.0",
		"osver":     "16.2",
		"channel":   "distribution",
		"deviceId":  "pyncm!",
		"requestId": strconv.Itoa(20000000 + rand.Intn(10000000)),
	}
	headerJSON, _ := json.Marshal(header)
	params["header"] = string(headerJSON)

	jsonBytes, _ := json.Marshal(params)
	form := eapiEncrypt(apiPath, string(jsonBytes))

	r := c.reqClient.R().
		SetHeaders(map[string]string{
			"User-Agent":   neteaseUAEapi,
			"Content-Type": "application/x-www-form-urlencoded",
			"Referer":      "",
		}).
		SetBodyString(form.Encode())

	// Inject cookies from shared jar, but override eapi-specific ones
	u, _ := url.Parse("https://music.163.com")
	eapiOverrides := map[string]string{
		"os": "iPhone OS", "appver": "10.0.0",
		"osver": "16.2", "channel": "distribution", "deviceId": "pyncm!",
	}
	for _, ck := range c.jar.Cookies(u) {
		if _, override := eapiOverrides[ck.Name]; override {
			continue
		}
		r.SetCookies(&http.Cookie{Name: ck.Name, Value: ck.Value})
	}
	for name, val := range eapiOverrides {
		r.SetCookies(&http.Cookie{Name: name, Value: val})
	}

	resp, err := r.Post("https://music.163.com" + endpoint)
	if err != nil {
		return nil, err
	}
	c.collectCookies(resp)

	body := resp.Bytes()

	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err == nil {
		return result, nil
	}

	decrypted, err := eapiDecrypt(body)
	if err != nil {
		return nil, fmt.Errorf("eapi decrypt failed: %w", err)
	}
	decrypted = []byte(strings.TrimRight(string(decrypted), "\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10"))
	if err := json.Unmarshal(decrypted, &result); err != nil {
		return nil, fmt.Errorf("eapi json decode: %w", err)
	}
	return result, nil
}

// --- Public API ---

// GetQRKey returns a unikey for QR code login.
func (c *NeteaseClient) GetQRKey() (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	result, err := c.weapiRequest("/weapi/login/qrcode/unikey", map[string]interface{}{
		"type":         "1",
		"noCheckToken": true,
	})
	if err != nil {
		return "", err
	}
	unikey, ok := result["unikey"].(string)
	if !ok {
		return "", fmt.Errorf("no unikey in response: %v", result)
	}
	return unikey, nil
}

// GetQRCodeURL builds the full QR code URL with chainId for anti-fraud.
func (c *NeteaseClient) GetQRCodeURL(unikey string) string {
	chainId := fmt.Sprintf("v1_%s_web_login_%d", c.sDeviceId, time.Now().UnixMilli())
	return fmt.Sprintf("http://music.163.com/login?codekey=%s&chainId=%s", unikey, chainId)
}

// CheckQR checks QR login status. Returns "waiting", "scanned", "success", or "expired".
func (c *NeteaseClient) CheckQR(unikey string) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	result, err := c.weapiRequest("/weapi/login/qrcode/client/login", map[string]interface{}{
		"type":         1,
		"noCheckToken": true,
		"key":          unikey,
	})
	if err != nil {
		return "", err
	}

	code, _ := result["code"].(float64)
	switch int(code) {
	case 800:
		return "expired", nil
	case 801:
		return "waiting", nil
	case 802:
		return "scanned", nil
	case 803:
		if err := c.SaveCredential(); err != nil {
			return "success", fmt.Errorf("login success but save failed: %w", err)
		}
		return "success", nil
	default:
		return "", fmt.Errorf("unknown qr status code: %v", code)
	}
}

// SearchSongs searches for songs by keyword.
func (c *NeteaseClient) SearchSongs(keyword string, limit int) ([]SongResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if limit <= 0 {
		limit = 30
	}
	result, err := c.eapiRequest("/eapi/cloudsearch/pc", "/api/cloudsearch/pc", map[string]interface{}{
		"s":      keyword,
		"type":   "1",
		"limit":  strconv.Itoa(limit),
		"offset": "0",
	})
	if err != nil {
		return nil, err
	}

	resultObj, _ := result["result"].(map[string]interface{})
	if resultObj == nil {
		return nil, fmt.Errorf("no result in search response: %v", result)
	}
	songs, _ := resultObj["songs"].([]interface{})

	var results []SongResult
	for _, s := range songs {
		song, ok := s.(map[string]interface{})
		if !ok {
			continue
		}
		sr := SongResult{Platform: "netease"}

		if id, ok := song["id"].(float64); ok {
			sr.Mid = strconv.FormatInt(int64(id), 10)
		}
		sr.Name, _ = song["name"].(string)

		// Artists
		if ar, ok := song["ar"].([]interface{}); ok {
			var names []string
			for _, a := range ar {
				if am, ok := a.(map[string]interface{}); ok {
					if n, ok := am["name"].(string); ok {
						names = append(names, n)
					}
				}
			}
			sr.Artist = strings.Join(names, " / ")
		}

		// Album
		if al, ok := song["al"].(map[string]interface{}); ok {
			sr.Album, _ = al["name"].(string)
			sr.Cover, _ = al["picUrl"].(string)
		}

		// Duration (ms -> seconds)
		if dt, ok := song["dt"].(float64); ok {
			sr.Duration = int(dt / 1000)
		}

		results = append(results, sr)
	}
	return results, nil
}

// GetSongURL gets the playback URL for a song.
func (c *NeteaseClient) GetSongURL(songID string) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	id, err := strconv.ParseInt(songID, 10, 64)
	if err != nil {
		return "", fmt.Errorf("invalid song id: %s", songID)
	}

	// Try 320kbps first
	songURL, err := c.getSongURLWithBitrate(id, 320000)
	if err != nil {
		return "", err
	}
	if songURL != "" {
		return songURL, nil
	}

	// Fallback to 128kbps
	return c.getSongURLWithBitrate(id, 128000)
}

func (c *NeteaseClient) getSongURLWithBitrate(id int64, br int) (string, error) {
	result, err := c.eapiRequest("/eapi/song/enhance/player/url", "/api/song/enhance/player/url", map[string]interface{}{
		"ids":        []int64{id},
		"br":         strconv.Itoa(br),
		"encodeType": "aac",
	})
	if err != nil {
		return "", err
	}

	data, _ := result["data"].([]interface{})
	if len(data) == 0 {
		return "", nil
	}
	first, _ := data[0].(map[string]interface{})
	if first == nil {
		return "", nil
	}
	u, _ := first["url"].(string)
	return u, nil
}

// GetLoginStatus checks if the client is logged in.
func (c *NeteaseClient) GetLoginStatus() (bool, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	result, err := c.weapiRequest("/weapi/w/nuser/account/get", map[string]interface{}{})
	if err != nil {
		return false, err
	}
	profile := result["profile"]
	return profile != nil, nil
}

// neteaseCredential is the on-disk format for persisted login state.
type neteaseCredential struct {
	SDeviceId string         `json:"s_device_id,omitempty"`
	WNMCID    string         `json:"wnmcid,omitempty"`
	Cookies   []cookieEntry  `json:"cookies"`
}

type cookieEntry struct {
	Name   string `json:"name"`
	Value  string `json:"value"`
	Domain string `json:"domain"`
	Path   string `json:"path"`
}

// SaveCredential persists cookies and device identifiers to disk.
func (c *NeteaseClient) SaveCredential() error {
	if c.credFile == "" {
		return nil
	}
	u, _ := url.Parse("https://music.163.com")
	cookies := c.jar.Cookies(u)

	var entries []cookieEntry
	for _, ck := range cookies {
		entries = append(entries, cookieEntry{
			Name:   ck.Name,
			Value:  ck.Value,
			Domain: ck.Domain,
			Path:   ck.Path,
		})
	}

	cred := neteaseCredential{
		SDeviceId: c.sDeviceId,
		WNMCID:    c.wnmcid,
		Cookies:   entries,
	}

	data, err := json.MarshalIndent(cred, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(c.credFile, data, 0600)
}

// LoadCredential loads cookies and device identifiers from disk.
func (c *NeteaseClient) LoadCredential() error {
	data, err := os.ReadFile(c.credFile)
	if err != nil {
		return err
	}

	var cred neteaseCredential
	if err := json.Unmarshal(data, &cred); err != nil {
		// Try legacy format (bare cookie array)
		var legacy []cookieEntry
		if err2 := json.Unmarshal(data, &legacy); err2 != nil {
			return err
		}
		cred.Cookies = legacy
	}

	if cred.SDeviceId != "" {
		c.sDeviceId = cred.SDeviceId
	}
	if cred.WNMCID != "" {
		c.wnmcid = cred.WNMCID
	}

	u, _ := url.Parse("https://music.163.com")
	var cookies []*http.Cookie
	for _, e := range cred.Cookies {
		domain := e.Domain
		if domain == "" {
			domain = ".music.163.com"
		}
		path := e.Path
		if path == "" {
			path = "/"
		}
		cookies = append(cookies, &http.Cookie{
			Name:   e.Name,
			Value:  e.Value,
			Domain: domain,
			Path:   path,
		})
		if e.Name == "__csrf" {
			c.csrf = e.Value
		}
	}
	c.jar.SetCookies(u, cookies)
	return nil
}

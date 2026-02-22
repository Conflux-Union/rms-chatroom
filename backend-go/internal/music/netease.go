package music

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"math/rand"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
)

// Netease crypto constants
const (
	weapiAESKey = "0CoJUm6Qyw8W8jud"
	weapiAESIV  = "0102030405060708"
	eapiAESKey  = "e82ckenh8dichen8"

	weapiRSAModulus = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
	weapiRSAPubExp  = "010001"

	randomChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

	neteaseUAWeapi = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
	neteaseUAEapi  = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154"
)

// NeteaseClient implements the NetEase Cloud Music API.
type NeteaseClient struct {
	httpClient *http.Client
	csrf       string
	credFile   string
	mu         sync.Mutex
}

func NewNeteaseClient(credentialPath string) *NeteaseClient {
	jar, _ := cookiejar.New(nil)
	c := &NeteaseClient{
		httpClient: &http.Client{Jar: jar},
		credFile:   credentialPath,
	}
	_ = c.LoadCredential()
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

func (c *NeteaseClient) weapiRequest(endpoint string, params map[string]interface{}) (map[string]interface{}, error) {
	params["csrf_token"] = c.csrf
	jsonBytes, err := json.Marshal(params)
	if err != nil {
		return nil, err
	}
	form := weapiEncrypt(string(jsonBytes))

	req, err := http.NewRequest("POST", "https://music.163.com"+endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", neteaseUAWeapi)
	req.Header.Set("Referer", "https://music.163.com")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	// Update CSRF token from cookies
	for _, ck := range resp.Cookies() {
		if ck.Name == "__csrf" {
			c.csrf = ck.Value
		}
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("weapi json decode: %w, body: %s", err, string(body[:min(len(body), 200)]))
	}
	return result, nil
}

func (c *NeteaseClient) eapiRequest(endpoint, apiPath string, params map[string]interface{}) (map[string]interface{}, error) {
	// Add eapi header config
	header := map[string]interface{}{
		"os":       "iPhone OS",
		"appver":   "10.0.0",
		"osver":    "16.2",
		"channel":  "distribution",
		"deviceId": "pyncm!",
	}
	headerJSON, _ := json.Marshal(header)
	params["header"] = string(headerJSON)

	jsonBytes, _ := json.Marshal(params)
	form := eapiEncrypt(apiPath, string(jsonBytes))

	req, err := http.NewRequest("POST", "https://music.163.com"+endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", neteaseUAEapi)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	// Set eapi cookies
	u, _ := url.Parse("https://music.163.com")
	existing := c.httpClient.Jar.Cookies(u)
	eapiCookies := map[string]string{
		"os": "iPhone OS", "appver": "10.0.0",
		"osver": "16.2", "channel": "distribution", "deviceId": "pyncm!",
	}
	for name, val := range eapiCookies {
		found := false
		for _, ck := range existing {
			if ck.Name == name {
				found = true
				break
			}
		}
		if !found {
			req.AddCookie(&http.Cookie{Name: name, Value: val})
		}
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	// Try direct JSON first, then try EAPI decryption
	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err == nil {
		return result, nil
	}

	decrypted, err := eapiDecrypt(body)
	if err != nil {
		return nil, fmt.Errorf("eapi decrypt failed: %w", err)
	}
	// Strip trailing padding bytes that aren't valid JSON
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
		"type": 1,
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

// CheckQR checks QR login status. Returns "waiting", "scanned", "success", or "expired".
func (c *NeteaseClient) CheckQR(unikey string) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	result, err := c.weapiRequest("/weapi/login/qrcode/client/login", map[string]interface{}{
		"type": 1,
		"key":  unikey,
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
		"ids": []int64{id},
		"br":  strconv.Itoa(br),
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

// SaveCredential persists cookies to disk.
func (c *NeteaseClient) SaveCredential() error {
	u, _ := url.Parse("https://music.163.com")
	cookies := c.httpClient.Jar.Cookies(u)

	type cookieEntry struct {
		Name   string `json:"name"`
		Value  string `json:"value"`
		Domain string `json:"domain"`
		Path   string `json:"path"`
	}
	var entries []cookieEntry
	for _, ck := range cookies {
		entries = append(entries, cookieEntry{
			Name:   ck.Name,
			Value:  ck.Value,
			Domain: ck.Domain,
			Path:   ck.Path,
		})
	}

	data, err := json.MarshalIndent(entries, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(c.credFile, data, 0600)
}

// LoadCredential loads cookies from disk.
func (c *NeteaseClient) LoadCredential() error {
	data, err := os.ReadFile(c.credFile)
	if err != nil {
		return err
	}

	type cookieEntry struct {
		Name   string `json:"name"`
		Value  string `json:"value"`
		Domain string `json:"domain"`
		Path   string `json:"path"`
	}
	var entries []cookieEntry
	if err := json.Unmarshal(data, &entries); err != nil {
		return err
	}

	u, _ := url.Parse("https://music.163.com")
	var cookies []*http.Cookie
	for _, e := range entries {
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
		// Extract CSRF
		if e.Name == "__csrf" {
			c.csrf = e.Value
		}
	}
	c.httpClient.Jar.SetCookies(u, cookies)
	return nil
}

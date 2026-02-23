package music

import (
	"crypto/aes"
	"crypto/md5"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"strconv"
	"strings"
	"time"

	reqv3 "github.com/imroc/req/v3"
)

const qimeiPublicKey = `-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDEIxgwoutfwoJxcGQeedgP7FG9
qaIuS0qzfR8gWkrkTZKM2iWHn2ajQpBRZjMSoSf6+KJGvar2ORhBfpDXyVtZCKpq
LQ+FLkpncClKVIrBwv6PHyUvuCb0rIarmgDnzkfQAqVufEtR64iazGDKatvJ9y6B
9NMbHddGSAUmRTCrHQIDAQAB
-----END PUBLIC KEY-----`

const (
	qimeiSecret = "ZdJqM15EeO2zWc08"
	qimeiAppKey = "0AND0HD6FE4HY80F"
	// Fallback QIMEI if API call fails
	defaultQIMEI = "6c9d3cd110abca9b16311cee10001e717614"
)

// getQIMEI36 fetches or loads a cached QIMEI36 device identifier.
func getQIMEI36(cacheFile string) string {
	// Try loading from cache
	if cacheFile != "" {
		if data, err := os.ReadFile(cacheFile); err == nil {
			var cached struct {
				Q36 string `json:"q36"`
			}
			if json.Unmarshal(data, &cached) == nil && cached.Q36 != "" {
				return cached.Q36
			}
		}
	}

	q36, err := fetchQIMEI()
	if err != nil {
		return defaultQIMEI
	}

	// Cache it
	if cacheFile != "" {
		data, _ := json.Marshal(map[string]string{"q36": q36})
		os.WriteFile(cacheFile, data, 0600)
	}
	return q36
}

func fetchQIMEI() (string, error) {
	device := randomDevice()
	payload := buildQIMEIPayload(device)

	cryptKey := randomHex(16)
	nonce := randomHex(16)
	ts := time.Now().Unix()

	// RSA encrypt the AES key
	encKey, err := qimeiRSAEncrypt([]byte(cryptKey))
	if err != nil {
		return "", fmt.Errorf("rsa encrypt: %w", err)
	}
	keyB64 := base64.StdEncoding.EncodeToString(encKey)

	// AES-CBC encrypt the payload (key = IV)
	payloadJSON, _ := json.Marshal(payload)
	encParams := qimeiAESEncrypt([]byte(cryptKey), payloadJSON)
	paramsB64 := base64.StdEncoding.EncodeToString(encParams)

	extra := `{"appKey":"` + qimeiAppKey + `"}`
	sign := calcMD5(keyB64, paramsB64, strconv.FormatInt(ts*1000, 10), nonce, qimeiSecret, extra)

	// Auth header sign
	headerSign := calcMD5("qimei_qq_androidpzAuCmaFAaFaHrdakPjLIEqKrGnSOOvH", strconv.FormatInt(ts, 10))

	client := reqv3.C().SetTimeout(5 * time.Second)
	resp, err := client.R().
		SetHeaders(map[string]string{
			"Host":       "api.tencentmusic.com",
			"method":     "GetQimei",
			"service":    "trpc.tme_datasvr.qimeiproxy.QimeiProxy",
			"appid":      "qimei_qq_android",
			"sign":       headerSign,
			"user-agent": "QQMusic",
			"timestamp":  strconv.FormatInt(ts, 10),
		}).
		SetBodyJsonMarshal(map[string]interface{}{
			"app": 0,
			"os":  1,
			"qimeiParams": map[string]interface{}{
				"key":    keyB64,
				"params": paramsB64,
				"time":   strconv.FormatInt(ts, 10),
				"nonce":  nonce,
				"sign":   sign,
				"extra":  extra,
			},
		}).
		Post("https://api.tencentmusic.com/tme/trpc/proxy")
	if err != nil {
		return "", fmt.Errorf("qimei request: %w", err)
	}

	// Parse nested JSON: {"data": "{\"data\": {\"q16\": ..., \"q36\": ...}}"}
	var outer struct {
		Data string `json:"data"`
	}
	if err := json.Unmarshal(resp.Bytes(), &outer); err != nil {
		return "", fmt.Errorf("parse outer: %w", err)
	}
	var inner struct {
		Data struct {
			Q36 string `json:"q36"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(outer.Data), &inner); err != nil {
		return "", fmt.Errorf("parse inner: %w", err)
	}
	if inner.Data.Q36 == "" {
		return "", fmt.Errorf("empty q36 in response")
	}
	return inner.Data.Q36, nil
}

func qimeiRSAEncrypt(data []byte) ([]byte, error) {
	block, _ := pem.Decode([]byte(qimeiPublicKey))
	if block == nil {
		return nil, fmt.Errorf("failed to decode PEM")
	}
	pub, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	return rsa.EncryptPKCS1v15(rand.Reader, pub.(*rsa.PublicKey), data)
}

func qimeiAESEncrypt(key, plaintext []byte) []byte {
	// PKCS7 padding
	padSize := 16 - len(plaintext)%16
	padded := make([]byte, len(plaintext)+padSize)
	copy(padded, plaintext)
	for i := len(plaintext); i < len(padded); i++ {
		padded[i] = byte(padSize)
	}

	block, err := aes.NewCipher(key)
	if err != nil {
		return nil
	}
	ciphertext := make([]byte, len(padded))
	// CBC: key is also used as IV
	iv := make([]byte, 16)
	copy(iv, key)
	for i := 0; i < len(padded); i += 16 {
		for j := 0; j < 16; j++ {
			padded[i+j] ^= iv[j]
		}
		block.Encrypt(ciphertext[i:i+16], padded[i:i+16])
		copy(iv, ciphertext[i:i+16])
	}
	return ciphertext
}

func calcMD5(parts ...string) string {
	h := md5.New()
	for _, p := range parts {
		h.Write([]byte(p))
	}
	return hex.EncodeToString(h.Sum(nil))
}

type fakeDevice struct {
	AndroidID   string
	IMEI        string
	Brand       string
	Model       string
	Device      string
	ProcVersion string
	OSRelease   string
	OSSDK       int
}

func randomDevice() fakeDevice {
	return fakeDevice{
		AndroidID:   randomHex(16),
		IMEI:        randomIMEI(),
		Brand:       "Xiaomi",
		Model:       "MI 6",
		Device:      "sagit",
		ProcVersion: "Linux 5.4.0-54-generic-" + randomHex(8) + " (android-build@google.com)",
		OSRelease:   "10",
		OSSDK:       29,
	}
}

func randomIMEI() string {
	// Generate 14 random digits, then compute Luhn check digit
	digits := make([]int, 14)
	for i := range digits {
		n, _ := rand.Int(rand.Reader, big.NewInt(10))
		digits[i] = int(n.Int64())
	}
	// Luhn
	sum := 0
	for i := 0; i < 14; i++ {
		d := digits[i]
		if i%2 == 1 {
			d *= 2
			if d > 9 {
				d -= 9
			}
		}
		sum += d
	}
	check := (10 - sum%10) % 10
	var sb strings.Builder
	for _, d := range digits {
		sb.WriteByte(byte('0' + d))
	}
	sb.WriteByte(byte('0' + check))
	return sb.String()
}

func buildQIMEIPayload(dev fakeDevice) map[string]interface{} {
	now := time.Now()
	fixedRand, _ := rand.Int(rand.Reader, big.NewInt(14400))
	uptimes := now.Add(-time.Duration(fixedRand.Int64()) * time.Second).Format("2006-01-02 15:04:05")

	reserved := map[string]string{
		"harmony":   "0",
		"clone":     "0",
		"containe":  "",
		"oz":        "UhYmelwouA+V2nPWbOvLTgN2/m8jwGB+yUB5v9tysQg=",
		"oo":        "Xecjt+9S1+f8Pz2VLSxgpw==",
		"kelong":    "0",
		"uptimes":   uptimes,
		"multiUser": "0",
		"bod":       dev.Brand,
		"dv":        dev.Device,
		"firstLevel": "",
		"manufact":  dev.Brand,
		"name":      dev.Model,
		"host":      "se.infra",
		"kernel":    dev.ProcVersion,
	}
	reservedJSON, _ := json.Marshal(reserved)

	return map[string]interface{}{
		"androidId":        dev.AndroidID,
		"platformId":       1,
		"appKey":           qimeiAppKey,
		"appVersion":       "13.2.5.8",
		"beaconIdSrc":      randomBeaconID(),
		"brand":            dev.Brand,
		"channelId":        "10003505",
		"cid":              "",
		"imei":             dev.IMEI,
		"imsi":             "",
		"mac":              "",
		"model":            dev.Model,
		"networkType":      "unknown",
		"oaid":             "",
		"osVersion":        fmt.Sprintf("Android %s,level %d", dev.OSRelease, dev.OSSDK),
		"qimei":            "",
		"qimei36":          "",
		"sdkVersion":       "1.2.13.6",
		"targetSdkVersion": "33",
		"audit":            "",
		"userId":           "{}",
		"packageId":        "com.tencent.qqmusic",
		"deviceType":       "Phone",
		"sdkName":          "",
		"reserved":         string(reservedJSON),
	}
}

func randomBeaconID() string {
	var sb strings.Builder
	timeMonth := time.Now().Format("2006-01-") + "01"
	r1, _ := rand.Int(rand.Reader, big.NewInt(900000))
	rand1 := r1.Int64() + 100000
	r2, _ := rand.Int(rand.Reader, big.NewInt(900000000))
	rand2 := r2.Int64() + 100000000

	specialIndexes := map[int]bool{
		1: true, 2: true, 13: true, 14: true, 17: true, 18: true,
		21: true, 22: true, 25: true, 26: true, 29: true, 30: true,
		33: true, 34: true, 37: true, 38: true,
	}

	for i := 1; i <= 40; i++ {
		if specialIndexes[i] {
			sb.WriteString(fmt.Sprintf("k%d:%s%d.%d", i, timeMonth, rand1, rand2))
		} else if i == 3 {
			sb.WriteString("k3:0000000000000000")
		} else if i == 4 {
			sb.WriteString("k4:" + randomHex(16))
		} else {
			r, _ := rand.Int(rand.Reader, big.NewInt(10000))
			sb.WriteString(fmt.Sprintf("k%d:%d", i, r.Int64()))
		}
		sb.WriteByte(';')
	}
	return sb.String()
}

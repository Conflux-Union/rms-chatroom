package music

import (
	"encoding/base64"
	"fmt"
	"math/rand"
	"regexp"
	"strconv"
	"strings"
)

// LyricResult carries raw LRC-format lyric text plus an optional translation
// (also LRC, timestamps aligned with the primary lyric). Parsing happens on
// clients so every platform can render it natively.
type LyricResult struct {
	Lyric       string `json:"lyric"`
	Translation string `json:"translation,omitempty"`
}

// qqPlayLyricKey is the response key of the PlayLyricInfo module.
const qqPlayLyricKey = "music.musichallSong.PlayLyricInfo.GetPlayLyricInfo"

// GetLyric fetches the LRC lyric (and Chinese translation when present) for a
// song mid. QQ serves most songs as plain LRC; songs that only carry QRC get a
// second pass whose XML lines are reduced to LRC-equivalent timestamps.
func (c *QQMusicClient) GetLyric(mid string) (*LyricResult, error) {
	result, err := c.playLyricInfo(mid, 0)
	if err != nil {
		return nil, err
	}
	if result.Lyric != "" {
		return result, nil
	}

	// No plain LRC: retry asking for QRC and flatten its line tags. A failed
	// retry must surface as an error — the handler caches empty results, and
	// a transient failure here would otherwise pin "no lyrics" forever.
	qrc, err := c.playLyricInfo(mid, 1)
	if err != nil {
		return nil, err
	}
	qrc.Lyric = qrcLinesToLrc(qrc.Lyric)
	if qrc.Lyric == "" {
		return result, nil
	}
	return &LyricResult{Lyric: qrc.Lyric, Translation: qrc.Translation}, nil
}

func (c *QQMusicClient) playLyricInfo(mid string, qrc int) (*LyricResult, error) {
	body := c.buildRequestBody("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", map[string]interface{}{
		"songMid": mid,
		"songId":  0,
		"qrc":     qrc,
		"cv":      2018741 + rand.Intn(11),
		"lrc":     1,
		"trans":   1,
	})
	respData, err := c.doRequestOpen(body)
	if err != nil {
		return nil, fmt.Errorf("lyric request failed: %w", err)
	}
	moduleMap, _ := respData[qqPlayLyricKey].(map[string]interface{})
	if moduleMap == nil {
		return nil, fmt.Errorf("missing key %s in lyric response", qqPlayLyricKey)
	}
	data, _ := moduleMap["data"].(map[string]interface{})
	if data == nil {
		return nil, fmt.Errorf("no data in lyric response")
	}

	result := &LyricResult{}
	if v, _ := data["lyric"].(string); v != "" {
		if decoded, err := base64.StdEncoding.DecodeString(v); err == nil {
			result.Lyric = string(decoded)
		}
	}
	if v, _ := data["trans"].(string); v != "" {
		if decoded, err := base64.StdEncoding.DecodeString(v); err == nil {
			result.Translation = qqNormalizeTranslation(string(decoded))
		}
	}
	return result, nil
}

var qqTransTagRe = regexp.MustCompile(`^\[[^]]*\]\s*`)

// qqNormalizeTranslation empties QQ's "no translation" placeholder payloads:
// timed lines of bare "//". Clients then see a clean empty translation.
func qqNormalizeTranslation(trans string) string {
	if trans == "" {
		return ""
	}
	for _, raw := range strings.Split(trans, "\n") {
		line := strings.TrimSpace(qqTransTagRe.ReplaceAllString(strings.TrimSpace(raw), ""))
		if line != "" && line != "//" {
			return trans
		}
	}
	return ""
}

var (
	qrcLineRe = regexp.MustCompile(`(?is)<line[^>]*\stime="(\d+)"[^>]*>(.*?)</line>`)
	qrcTagRe  = regexp.MustCompile(`(?s)<[^>]+>`)
)

// qrcLinesToLrc converts QRC XML into plain LRC by stripping the per-word
// markup and emitting one "[mm:ss.xx]text" line per <line time="..."> element.
// Returns "" when the input is not QRC XML or yields no timed lines.
func qrcLinesToLrc(qrc string) string {
	if !strings.Contains(qrc, "<") {
		return ""
	}
	var sb strings.Builder
	for _, m := range qrcLineRe.FindAllStringSubmatch(qrc, -1) {
		timeMS, err := strconv.ParseInt(m[1], 10, 64)
		if err != nil || timeMS < 0 {
			continue
		}
		text := decodeXMLText(qrcTagRe.ReplaceAllString(m[2], ""))
		if text == "" {
			continue
		}
		fmt.Fprintf(&sb, "[%02d:%02d.%02d]%s\n", timeMS/60000, (timeMS/1000)%60, (timeMS%1000)/10, text)
	}
	return sb.String()
}

// decodeXMLText unescapes the handful of entities QRC lyrics use.
func decodeXMLText(s string) string {
	r := strings.NewReplacer("&lt;", "<", "&gt;", ">", "&quot;", `"`, "&apos;", "'", "&amp;", "&")
	return strings.TrimSpace(r.Replace(s))
}

// GetLyric fetches the LRC lyric and translation for a NetEase song ID.
func (c *NeteaseClient) GetLyric(songID string) (*LyricResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	id, err := strconv.ParseInt(songID, 10, 64)
	if err != nil {
		return nil, fmt.Errorf("invalid song id: %s", songID)
	}
	result, err := c.eapiRequest("/eapi/song/lyric", "/api/song/lyric", map[string]interface{}{
		"id": id,
		"lv": -1,
		"kv": -1,
		"tv": -1,
		"rv": -1,
	})
	if err != nil {
		return nil, err
	}

	lyric := &LyricResult{}
	if lrc, _ := result["lrc"].(map[string]interface{}); lrc != nil {
		lyric.Lyric, _ = lrc["lyric"].(string)
	}
	if tlc, _ := result["tlyric"].(map[string]interface{}); tlc != nil {
		lyric.Translation, _ = tlc["lyric"].(string)
	}
	if lyric.Lyric == "" && lyric.Translation == "" {
		// Distinguish "provider says no lyric" from transport failure: a
		// 200 with no lrc payload (nolyric / uncollected) is a valid empty
		// answer, anything else should have failed above.
		if code, _ := result["code"].(float64); int(code) != 200 {
			return nil, fmt.Errorf("lyric api returned code %v", result["code"])
		}
	}
	return lyric, nil
}

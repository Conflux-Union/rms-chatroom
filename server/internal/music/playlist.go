package music

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
)

// PlaylistResult is the cross-platform playlist card shown in search results.
type PlaylistResult struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Cover      string `json:"cover"`
	TrackCount int    `json:"track_count"`
	PlayCount  int64  `json:"play_count"`
	Creator    string `json:"creator"`
	Platform   string `json:"platform"` // "qq" or "netease"
}

// --- Shared helpers ---

func normalizeCoverURL(cover string) string {
	// QQ CDN covers come back protocol-relative.
	if strings.HasPrefix(cover, "//") {
		return "https:" + cover
	}
	return cover
}

func floatField(m map[string]interface{}, key string) (float64, bool) {
	v, ok := m[key].(float64)
	return v, ok
}

func intField(m map[string]interface{}, keys ...string) int {
	for _, key := range keys {
		if v, ok := floatField(m, key); ok {
			return int(v)
		}
	}
	return 0
}

func int64Field(m map[string]interface{}, keys ...string) int64 {
	for _, key := range keys {
		if v, ok := floatField(m, key); ok {
			return int64(v)
		}
	}
	return 0
}

func stringField(m map[string]interface{}, keys ...string) string {
	for _, key := range keys {
		switch v := m[key].(type) {
		case string:
			if v != "" {
				return v
			}
		case float64:
			// Some QQ responses alternate between numeric and string ids.
			if v != 0 {
				return strconv.FormatFloat(v, 'f', -1, 64)
			}
		}
	}
	return ""
}

// --- NetEase: playlist search / detail / daily mix / personal FM ---

// SearchPlaylists searches playlists by keyword (cloudsearch type=1000).
func (c *NeteaseClient) SearchPlaylists(keyword string, limit int) ([]PlaylistResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if limit <= 0 {
		limit = 20
	}
	result, err := c.eapiRequest("/eapi/cloudsearch/pc", "/api/cloudsearch/pc", map[string]interface{}{
		"s":      keyword,
		"type":   "1000",
		"limit":  strconv.Itoa(limit),
		"offset": "0",
	})
	if err != nil {
		return nil, err
	}
	resultObj, _ := result["result"].(map[string]interface{})
	if resultObj == nil {
		return nil, fmt.Errorf("no result in playlist search response")
	}
	return parseNeteasePlaylists(resultObj["playlists"]), nil
}

func parseNeteasePlaylists(raw interface{}) []PlaylistResult {
	items, _ := raw.([]interface{})
	playlists := make([]PlaylistResult, 0, len(items))
	for _, item := range items {
		m, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		id := stringField(m, "id")
		name := stringField(m, "name")
		if id == "" || name == "" {
			continue
		}
		creator := ""
		if cm, ok := m["creator"].(map[string]interface{}); ok {
			creator = stringField(cm, "nickname", "name")
		}
		playlists = append(playlists, PlaylistResult{
			ID:         id,
			Name:       name,
			Cover:      stringField(m, "coverImgUrl"),
			TrackCount: intField(m, "trackCount"),
			PlayCount:  int64Field(m, "playCount"),
			Creator:    creator,
			Platform:   "netease",
		})
	}
	return playlists
}

func joinNeteaseArtists(raw interface{}) string {
	items, _ := raw.([]interface{})
	names := make([]string, 0, len(items))
	for _, item := range items {
		m, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		if n, ok := m["name"].(string); ok && n != "" {
			names = append(names, n)
		}
	}
	return strings.Join(names, " / ")
}

// parseNeteaseSong maps the NetEase song object. Cloudsearch and song/detail
// use the ar/al/dt shape, but the v1 endpoints (daily recommend, personal
// radio) still return the legacy artists/album/duration shape, so every field
// falls back to it.
func parseNeteaseSong(m map[string]interface{}) (SongResult, bool) {
	sr := SongResult{Platform: "netease"}
	if id, ok := floatField(m, "id"); ok {
		sr.Mid = strconv.FormatInt(int64(id), 10)
	}
	sr.Name, _ = m["name"].(string)
	if sr.Mid == "" || sr.Name == "" {
		return sr, false
	}

	sr.Artist = joinNeteaseArtists(m["ar"])
	if sr.Artist == "" {
		sr.Artist = joinNeteaseArtists(m["artists"])
	}
	album, _ := m["al"].(map[string]interface{})
	if album == nil {
		album, _ = m["album"].(map[string]interface{})
	}
	if album != nil {
		sr.Album, _ = album["name"].(string)
		sr.Cover, _ = album["picUrl"].(string)
	}
	if ms, ok := floatField(m, "dt"); ok {
		sr.Duration = int(ms / 1000)
	} else if ms, ok := floatField(m, "duration"); ok {
		sr.Duration = int(ms / 1000)
	}
	return sr, true
}

// blockedNeteaseSongIDs collects ids whose privilege marks them as unavailable
// (st < 0 means no copyright on any account, so queueing them is pointless).
func blockedNeteaseSongIDs(raw interface{}) map[string]bool {
	blocked := make(map[string]bool)
	items, _ := raw.([]interface{})
	for _, item := range items {
		m, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		if st, ok := floatField(m, "st"); ok && st < 0 {
			if id, ok := floatField(m, "id"); ok {
				blocked[strconv.FormatInt(int64(id), 10)] = true
			}
		}
	}
	return blocked
}

func parseNeteaseSongsWithPrivileges(songsRaw, privilegesRaw interface{}) []SongResult {
	blocked := blockedNeteaseSongIDs(privilegesRaw)
	items, _ := songsRaw.([]interface{})
	results := make([]SongResult, 0, len(items))
	for _, item := range items {
		m, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		sr, ok := parseNeteaseSong(m)
		if !ok || blocked[sr.Mid] {
			continue
		}
		results = append(results, sr)
	}
	return results
}

// GetDailyRecommendSongs returns the logged-in account's daily mix. Requires
// a MUSIC_U session cookie.
func (c *NeteaseClient) GetDailyRecommendSongs() ([]SongResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	result, err := c.weapiRequest("/weapi/v1/discovery/recommend/songs", map[string]interface{}{})
	if err != nil {
		return nil, err
	}
	data, _ := result["data"].(map[string]interface{})
	if data == nil {
		return nil, fmt.Errorf("no data in daily recommend response")
	}
	return parseNeteaseSongsWithPrivileges(data["dailySongs"], data["privileges"]), nil
}

// GetRadioSongs returns personalized radio tracks ("猜你喜欢"-style feed).
// The endpoint returns ~3 songs per call and every call re-rolls the batch,
// so it is polled until count is reached or no new songs arrive.
func (c *NeteaseClient) GetRadioSongs(count int) ([]SongResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if count <= 0 {
		count = 30
	}
	seen := make(map[string]bool)
	var results []SongResult
	maxCalls := count/3 + 4
	for call := 0; call < maxCalls && len(results) < count; call++ {
		result, err := c.weapiRequest("/weapi/v1/radio/get", map[string]interface{}{})
		if err != nil {
			return nil, err
		}
		songs := parseNeteaseSongsWithPrivileges(result["data"], result["privileges"])
		if len(songs) == 0 {
			break
		}
		added := 0
		for _, sr := range songs {
			if seen[sr.Mid] {
				continue
			}
			seen[sr.Mid] = true
			results = append(results, sr)
			added++
			if len(results) >= count {
				break
			}
		}
		if added == 0 {
			break
		}
	}
	return results, nil
}

// GetPlaylistDetail returns playlist metadata plus up to maxSongs songs.
// Track metadata is fetched in batches from song/detail because playlist
// detail only embeds the full id list (trackIds), not full song objects.
func (c *NeteaseClient) GetPlaylistDetail(playlistID string, maxSongs int) (*PlaylistResult, []SongResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	id, err := strconv.ParseInt(playlistID, 10, 64)
	if err != nil || id <= 0 {
		return nil, nil, fmt.Errorf("invalid netease playlist id: %s", playlistID)
	}
	if maxSongs <= 0 {
		maxSongs = 50
	}

	result, err := c.weapiRequest("/weapi/v6/playlist/detail", map[string]interface{}{
		"id": id,
		"n":  "0",
		"s":  "0",
	})
	if err != nil {
		return nil, nil, err
	}
	pl, _ := result["playlist"].(map[string]interface{})
	if pl == nil {
		return nil, nil, fmt.Errorf("no playlist in detail response")
	}

	meta := &PlaylistResult{
		ID:         strconv.FormatInt(id, 10),
		Name:       stringField(pl, "name"),
		Cover:      stringField(pl, "coverImgUrl"),
		TrackCount: intField(pl, "trackCount"),
		PlayCount:  int64Field(pl, "playCount"),
		Platform:   "netease",
	}
	if cm, ok := pl["creator"].(map[string]interface{}); ok {
		meta.Creator = stringField(cm, "nickname", "name")
	}

	trackIDs := make([]string, 0, meta.TrackCount)
	if rawIDs, ok := pl["trackIds"].([]interface{}); ok {
		for _, raw := range rawIDs {
			if tm, ok := raw.(map[string]interface{}); ok {
				if tid, ok := floatField(tm, "id"); ok {
					trackIDs = append(trackIDs, strconv.FormatInt(int64(tid), 10))
				}
			}
		}
	}
	if meta.TrackCount == 0 {
		meta.TrackCount = len(trackIDs)
	}
	if len(trackIDs) > maxSongs {
		trackIDs = trackIDs[:maxSongs]
	}
	if len(trackIDs) == 0 {
		return meta, nil, nil
	}

	var songs []SongResult
	const batch = 100
	for start := 0; start < len(trackIDs); start += batch {
		end := start + batch
		if end > len(trackIDs) {
			end = len(trackIDs)
		}
		criteria := make([]map[string]interface{}, 0, end-start)
		for _, tid := range trackIDs[start:end] {
			n, _ := strconv.ParseInt(tid, 10, 64)
			criteria = append(criteria, map[string]interface{}{"id": n})
		}
		cJSON, _ := json.Marshal(criteria)
		idsJSON, _ := json.Marshal(trackIDs[start:end])
		detail, err := c.weapiRequest("/weapi/v3/song/detail", map[string]interface{}{
			"c":   string(cJSON),
			"ids": string(idsJSON),
		})
		if err != nil {
			return meta, songs, err
		}
		songs = append(songs, parseNeteaseSongsWithPrivileges(detail["songs"], detail["privileges"])...)
	}
	return meta, songs, nil
}

// --- QQ Music: playlist search / detail / daily-30 / radio ("猜你喜欢") ---

// SearchPlaylists searches playlists by keyword (search_type 3).
func (c *QQMusicClient) SearchPlaylists(keyword string, num int) ([]PlaylistResult, error) {
	if num <= 0 {
		num = 20
	}
	params := map[string]interface{}{
		"searchid":     getSearchID(),
		"query":        keyword,
		"search_type":  3,
		"num_per_page": num,
		"page_num":     1,
		"highlight":    0,
		"grp":          1,
	}
	body := c.buildRequestBody("music.search.SearchCgiService", "DoSearchForQQMusicMobile", params)

	respData, err := c.doRequestOpen(body)
	if err != nil {
		return nil, fmt.Errorf("playlist search request failed: %w", err)
	}
	results := parseQQPlaylistSearch(respData)
	if len(results) > 0 {
		return results, nil
	}
	respData, err = c.doRequest(body)
	if err != nil {
		return nil, fmt.Errorf("playlist search request failed: %w", err)
	}
	return parseQQPlaylistSearch(respData), nil
}

// stripHTML removes the <em>...</em> highlight tags QQ search wraps around
// matched keywords.
func stripHTML(s string) string {
	for {
		start := strings.Index(s, "<")
		if start < 0 {
			return s
		}
		end := strings.Index(s[start:], ">")
		if end < 0 {
			return s
		}
		s = s[:start] + s[start+end+1:]
	}
}

func parseQQPlaylistSearch(respData map[string]interface{}) []PlaylistResult {
	key := "music.search.SearchCgiService.DoSearchForQQMusicMobile"
	moduleMap, _ := respData[key].(map[string]interface{})
	if moduleMap == nil {
		return nil
	}
	data, _ := moduleMap["data"].(map[string]interface{})
	body, _ := data["body"].(map[string]interface{})
	if body == nil {
		return nil
	}
	var items []interface{}
	// Verified live response uses item_songlist; keep alternates as fallbacks.
	for _, field := range []string{"item_songlist", "songlist", "item_playlist"} {
		if v, ok := body[field].([]interface{}); ok && len(v) > 0 {
			items = v
			break
		}
	}

	playlists := make([]PlaylistResult, 0, len(items))
	for _, item := range items {
		m, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		id := stringField(m, "dissid", "tid")
		name := stringField(m, "dissname", "title", "name")
		if id == "" || name == "" {
			continue
		}
		creator := stringField(m, "nickname", "creatorname")
		if creator == "" {
			if cm, ok := m["creator"].(map[string]interface{}); ok {
				creator = stringField(cm, "name", "nick")
			}
		}
		playlists = append(playlists, PlaylistResult{
			ID:         id,
			Name:       stripHTML(name),
			Cover:      normalizeCoverURL(stringField(m, "logo", "imgurl", "diss_cover")),
			TrackCount: intField(m, "songnum", "song_num"),
			PlayCount:  int64Field(m, "listennum", "listen_num"),
			Creator:    creator,
			Platform:   "qq",
		})
	}
	return playlists
}

// parseQQDissTrack maps a CgiGetDiss songlist item. Items are normally flat
// but some responses wrap the song in track_info.
func parseQQDissTrack(item map[string]interface{}) (SongResult, bool) {
	track := item
	if ti, ok := item["track_info"].(map[string]interface{}); ok {
		track = ti
	} else if si, ok := item["songInfo"].(map[string]interface{}); ok {
		track = si
	}

	mid := stringField(track, "mid", "songmid")
	name := stringField(track, "name", "songname", "title")
	if mid == "" || name == "" {
		return SongResult{}, false
	}

	var artists []string
	if singers, ok := track["singer"].([]interface{}); ok {
		for _, s := range singers {
			if sm, ok := s.(map[string]interface{}); ok {
				if sn := stringField(sm, "name", "title"); sn != "" {
					artists = append(artists, sn)
				}
			}
		}
	}
	var albumName, albumMid string
	if album, ok := track["album"].(map[string]interface{}); ok {
		albumName = stringField(album, "name", "title")
		albumMid = stringField(album, "mid")
	}
	cover := ""
	if albumMid != "" {
		cover = fmt.Sprintf("https://y.gtimg.cn/music/photo_new/T002R300x300M000%s.jpg", albumMid)
	}
	return SongResult{
		Mid:      mid,
		Name:     name,
		Artist:   strings.Join(artists, " / "),
		Album:    albumName,
		Duration: intField(track, "interval"),
		Cover:    cover,
		Platform: "qq",
	}, true
}

// GetPlaylistDetail returns playlist metadata plus up to maxSongs songs,
// paging through CgiGetDiss (100 tracks per page).
func (c *QQMusicClient) GetPlaylistDetail(playlistID string, maxSongs int) (*PlaylistResult, []SongResult, error) {
	disstid, err := strconv.ParseInt(playlistID, 10, 64)
	if err != nil || disstid <= 0 {
		return nil, nil, fmt.Errorf("invalid qq playlist id: %s", playlistID)
	}
	if maxSongs <= 0 {
		maxSongs = 50
	}

	meta := &PlaylistResult{
		ID:       playlistID,
		Platform: "qq",
	}
	var songs []SongResult
	const pageSize = 100
	offset := 0
	for len(songs) < maxSongs {
		remaining := maxSongs - len(songs)
		fetch := pageSize
		if remaining < fetch {
			fetch = remaining
		}
		body := c.buildRequestBody("music.srfDissInfo.DissInfo", "CgiGetDiss", map[string]interface{}{
			"disstid":     disstid,
			"dirid":       0,
			"tag":         true,
			"song_begin":  offset,
			"song_num":    fetch,
			"userinfo":    true,
			"orderlist":   true,
			"onlysonglist": false,
		})
		respData, err := c.doRequest(body)
		if err != nil {
			return meta, songs, fmt.Errorf("playlist detail request failed: %w", err)
		}
		key := "music.srfDissInfo.DissInfo.CgiGetDiss"
		moduleMap, _ := respData[key].(map[string]interface{})
		if moduleMap == nil {
			return meta, songs, fmt.Errorf("missing response key: %s", key)
		}
		if code := intField(moduleMap, "code"); code != 0 {
			return meta, songs, fmt.Errorf("playlist detail failed with code %d", code)
		}
		data, _ := moduleMap["data"].(map[string]interface{})
		if data == nil {
			return meta, songs, fmt.Errorf("no data in playlist detail response")
		}
		if offset == 0 {
			if dirinfo, ok := data["dirinfo"].(map[string]interface{}); ok {
				meta.Name = stringField(dirinfo, "title", "dissname", "diss_name", "name")
				meta.Cover = normalizeCoverURL(stringField(dirinfo, "picurl", "logo", "diss_cover"))
				meta.TrackCount = intField(dirinfo, "songnum", "song_num", "total_song_num")
				meta.PlayCount = int64Field(dirinfo, "listennum", "listen_num", "visitnum")
				meta.Creator = stringField(dirinfo, "host_nick", "nick", "nickname")
			}
		}
		rawSongs, _ := data["songlist"].([]interface{})
		if len(rawSongs) == 0 {
			break
		}
		for _, raw := range rawSongs {
			m, ok := raw.(map[string]interface{})
			if !ok {
				continue
			}
			sr, ok := parseQQDissTrack(m)
			if !ok {
				continue
			}
			songs = append(songs, sr)
			if len(songs) >= maxSongs {
				break
			}
		}
		if meta.TrackCount == 0 {
			meta.TrackCount = intField(data, "total_song_num", "songlist_size")
		}
		hasMore := false
		switch v := data["hasmore"].(type) {
		case bool:
			hasMore = v
		case float64:
			hasMore = v > 0
		default:
			hasMore = len(rawSongs) >= fetch && offset+len(rawSongs) < meta.TrackCount
		}
		if !hasMore {
			break
		}
		offset += len(rawSongs)
	}
	return meta, songs, nil
}

// GetDailyRecommendSongs resolves the "每日30首" feed. The feed does not
// carry songs directly: it exposes a per-day dynamic playlist id in the
// subtype-510 card, which is then read through CgiGetDiss. Requires login.
func (c *QQMusicClient) GetDailyRecommendSongs() ([]SongResult, error) {
	body := c.buildRequestBody("music.recommend.RecommendFeed", "get_recommend_feed", map[string]interface{}{
		"direction": 0,
		"page":      1,
		"v_cache":   []interface{}{},
		"v_uniq":    []interface{}{},
		"s_num":     0,
	})
	respData, err := c.doRequest(body)
	if err != nil {
		return nil, fmt.Errorf("daily recommend feed request failed: %w", err)
	}
	dailyID := findQQDailyPlaylistID(respData)
	if dailyID == 0 {
		return nil, fmt.Errorf("daily recommend playlist not found in feed")
	}
	_, songs, err := c.GetPlaylistDetail(strconv.FormatInt(dailyID, 10), 30)
	if err != nil {
		return nil, err
	}
	if len(songs) == 0 {
		return nil, fmt.Errorf("daily recommend playlist is empty")
	}
	return songs, nil
}

func findQQDailyPlaylistID(respData map[string]interface{}) int64 {
	key := "music.recommend.RecommendFeed.get_recommend_feed"
	moduleMap, _ := respData[key].(map[string]interface{})
	if moduleMap == nil {
		return 0
	}
	data, _ := moduleMap["data"].(map[string]interface{})
	if data == nil {
		return 0
	}
	shelves, _ := data["v_shelf"].([]interface{})
	for _, shelfRaw := range shelves {
		shelf, ok := shelfRaw.(map[string]interface{})
		if !ok {
			continue
		}
		niches, _ := shelf["v_niche"].([]interface{})
		for _, nicheRaw := range niches {
			niche, ok := nicheRaw.(map[string]interface{})
			if !ok {
				continue
			}
			cards, _ := niche["v_card"].([]interface{})
			for _, cardRaw := range cards {
				card, ok := cardRaw.(map[string]interface{})
				if !ok {
					continue
				}
				if subtype, ok := floatField(card, "subtype"); !ok || subtype != 510 {
					continue
				}
				// The live feed returns the daily playlist id as a string.
				if id, err := strconv.ParseInt(stringField(card, "id"), 10, 64); err == nil && id > 0 {
					return id
				}
			}
		}
	}
	return 0
}

// GetRadioSongs returns personalized radio tracks ("猜你喜欢"). The endpoint
// re-rolls a fresh batch on every call, so it is polled until count is
// reached or a round adds no new songs. Requires login.
func (c *QQMusicClient) GetRadioSongs(count int) ([]SongResult, error) {
	if count <= 0 {
		count = 30
	}
	seen := make(map[string]bool)
	var results []SongResult
	// Each round returns ~20 tracks; bound the loop so a feed that only
	// returns already-seen tracks cannot spin.
	maxCalls := count/10 + 3
	for call := 0; call < maxCalls && len(results) < count; call++ {
		body := c.buildRequestBody("music.radioProxy.MbTrackRadioSvr", "get_radio_track", map[string]interface{}{})
		respData, err := c.doRequest(body)
		if err != nil {
			return nil, fmt.Errorf("radio request failed: %w", err)
		}
		key := "music.radioProxy.MbTrackRadioSvr.get_radio_track"
		moduleMap, _ := respData[key].(map[string]interface{})
		if moduleMap == nil {
			return nil, fmt.Errorf("missing response key: %s", key)
		}
		data, _ := moduleMap["data"].(map[string]interface{})
		if data == nil {
			break
		}
		var items []interface{}
		for _, field := range []string{"track", "songList", "vec_song", "tracks"} {
			if v, ok := data[field].([]interface{}); ok && len(v) > 0 {
				items = v
				break
			}
		}
		if len(items) == 0 {
			break
		}
		added := 0
		for _, raw := range items {
			m, ok := raw.(map[string]interface{})
			if !ok {
				continue
			}
			sr, ok := parseQQDissTrack(m)
			if !ok || seen[sr.Mid] {
				continue
			}
			seen[sr.Mid] = true
			results = append(results, sr)
			added++
			if len(results) >= count {
				break
			}
		}
		if added == 0 {
			break
		}
	}
	return results, nil
}

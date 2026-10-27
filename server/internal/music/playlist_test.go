package music

import (
	"encoding/json"
	"testing"
)

func mustJSON(t *testing.T, raw string) map[string]interface{} {
	t.Helper()
	var m map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		t.Fatalf("invalid fixture: %v", err)
	}
	return m
}

func TestParseNeteasePlaylists(t *testing.T) {
	resp := mustJSON(t, `{
		"result": {
			"playlists": [
				{
					"id": 7213412312,
					"name": "华语经典",
					"coverImgUrl": "http://p.music.126.net/abc.jpg",
					"trackCount": 120,
					"playCount": 9876543,
					"creator": {"nickname": "小明"}
				},
				{"id": 0, "name": ""},
				"garbage"
			]
		}
	}`)
	result, _ := resp["result"].(map[string]interface{})
	playlists := parseNeteasePlaylists(result["playlists"])
	if len(playlists) != 1 {
		t.Fatalf("want 1 playlist, got %d", len(playlists))
	}
	p := playlists[0]
	if p.ID != "7213412312" || p.Name != "华语经典" || p.Creator != "小明" {
		t.Errorf("unexpected playlist: %+v", p)
	}
	if p.TrackCount != 120 || p.PlayCount != 9876543 || p.Platform != "netease" {
		t.Errorf("unexpected counts: %+v", p)
	}
}

func TestParseNeteaseSongsWithPrivileges(t *testing.T) {
	resp := mustJSON(t, `{
		"songs": [
			{"id": 1, "name": "晴天", "ar": [{"name": "周杰伦"}], "al": {"name": "叶惠美", "picUrl": "http://p/1.jpg"}, "dt": 269000},
			{"id": 2, "name": "没有版权的歌", "ar": [], "al": {}, "dt": 1000}
		],
		"privileges": [
			{"id": 1, "st": 0},
			{"id": 2, "st": -1}
		]
	}`)
	songs := parseNeteaseSongsWithPrivileges(resp["songs"], resp["privileges"])
	if len(songs) != 1 {
		t.Fatalf("want blocked song filtered, got %d songs", len(songs))
	}
	s := songs[0]
	if s.Mid != "1" || s.Name != "晴天" || s.Artist != "周杰伦" || s.Album != "叶惠美" {
		t.Errorf("unexpected song: %+v", s)
	}
	if s.Duration != 269 || s.Platform != "netease" {
		t.Errorf("unexpected duration/platform: %+v", s)
	}
}

// The v1 endpoints (daily recommend, personal radio) return the legacy
// artists/album/duration song shape; verified against the live
// /weapi/v1/discovery/recommend/songs response.
func TestParseNeteaseLegacySongShape(t *testing.T) {
	resp := mustJSON(t, `{
		"songs": [
			{
				"id": 3403313136,
				"name": "我们正驶向黎明",
				"artists": [{"name": "言和"}],
				"album": {"name": "我们正驶向黎明", "picUrl": "http://p1.music.126.net/5il3iDLXyNxmSrEDARQV5g==/109951173526100743.jpg"},
				"duration": 226683
			}
		],
		"privileges": [{"id": 3403313136, "st": 0}]
	}`)
	songs := parseNeteaseSongsWithPrivileges(resp["songs"], resp["privileges"])
	if len(songs) != 1 {
		t.Fatalf("want 1 song, got %d", len(songs))
	}
	s := songs[0]
	if s.Mid != "3403313136" || s.Name != "我们正驶向黎明" {
		t.Errorf("unexpected id/name: %+v", s)
	}
	if s.Artist != "言和" || s.Album != "我们正驶向黎明" {
		t.Errorf("legacy artist/album not parsed: %+v", s)
	}
	if s.Duration != 226 {
		t.Errorf("legacy duration not parsed: %+v", s)
	}
	if s.Cover != "http://p1.music.126.net/5il3iDLXyNxmSrEDARQV5g==/109951173526100743.jpg" {
		t.Errorf("legacy cover not parsed: %q", s.Cover)
	}
}

func TestParseQQPlaylistSearch(t *testing.T) {
	// Fixture mirrors the live DoSearchForQQMusicMobile search_type=3 shape:
	// item_songlist entries with nickname/logo and <em> highlight tags.
	resp := mustJSON(t, `{
		"music.search.SearchCgiService.DoSearchForQQMusicMobile": {
			"code": 0,
			"data": {
				"body": {
					"item_songlist": [
						{
							"dissid": "7039749142",
							"dissname": "百听不厌的\u003cem\u003e周杰伦\u003c/em\u003e",
							"logo": "http://qpic.y.qq.com/music_cover/abc/300?n=1",
							"songnum": 99,
							"listennum": 416487928,
							"nickname": "今晚月色很美"
						},
						{"dissid": "stringid123", "dissname": "字符串ID", "songnum": 3, "nickname": "小王"}
					]
				}
			}
		}
	}`)
	playlists := parseQQPlaylistSearch(resp)
	if len(playlists) != 2 {
		t.Fatalf("want 2 playlists, got %d", len(playlists))
	}
	first := playlists[0]
	if first.ID != "7039749142" || first.Name != "百听不厌的周杰伦" || first.Creator != "今晚月色很美" {
		t.Errorf("unexpected playlist: %+v", first)
	}
	if first.Cover != "http://qpic.y.qq.com/music_cover/abc/300?n=1" {
		t.Errorf("unexpected cover: %q", first.Cover)
	}
	if first.TrackCount != 99 || first.PlayCount != 416487928 || first.Platform != "qq" {
		t.Errorf("unexpected counts: %+v", first)
	}
	if playlists[1].ID != "stringid123" {
		t.Errorf("string id not kept: %+v", playlists[1])
	}
}

func TestStripHTML(t *testing.T) {
	if got := stripHTML("百听不厌的<em>周杰伦</em>"); got != "百听不厌的周杰伦" {
		t.Errorf("stripHTML = %q", got)
	}
	if got := stripHTML("无标签"); got != "无标签" {
		t.Errorf("stripHTML = %q", got)
	}
}

func TestParseQQDissTrack(t *testing.T) {
	flat := mustJSON(t, `{
		"mid": "0039MnYb0qxYhV",
		"name": "玫瑰花的葬礼",
		"singer": [{"name": "许嵩"}],
		"album": {"name": "寻雾启示", "mid": "002Neh8l0RxIVZ"},
		"interval": 254
	}`)
	sr, ok := parseQQDissTrack(flat)
	if !ok || sr.Mid != "0039MnYb0qxYhV" || sr.Name != "玫瑰花的葬礼" || sr.Artist != "许嵩" {
		t.Fatalf("flat parse failed: %+v ok=%v", sr, ok)
	}
	if sr.Album != "寻雾启示" || sr.Duration != 254 {
		t.Errorf("unexpected album/duration: %+v", sr)
	}
	if sr.Cover != "https://y.gtimg.cn/music/photo_new/T002R300x300M000002Neh8l0RxIVZ.jpg" {
		t.Errorf("unexpected cover: %q", sr.Cover)
	}

	wrapped := mustJSON(t, `{"track_info": {"mid": "abc", "songname": "wrapped", "singer": [], "interval": 10}}`)
	sr2, ok := parseQQDissTrack(wrapped)
	if !ok || sr2.Mid != "abc" || sr2.Name != "wrapped" {
		t.Fatalf("wrapped parse failed: %+v ok=%v", sr2, ok)
	}

	empty := mustJSON(t, `{"mid": "", "name": ""}`)
	if _, ok := parseQQDissTrack(empty); ok {
		t.Errorf("empty track should be rejected")
	}
}

func TestFindQQDailyPlaylistID(t *testing.T) {
	// Live response shape: the subtype-510 card carries the daily playlist id
	// as a string ("6059606031"), with plenty of unrelated cards around it.
	resp := mustJSON(t, `{
		"music.recommend.RecommendFeed.get_recommend_feed": {
			"code": 0,
			"data": {
				"v_shelf": [
					{"v_niche": [{"v_card": [
						{"subtype": 500, "id": "1"},
						{"subtype": 511, "id": "2"}
					]}]},
					{"v_niche": [{"v_card": [
						{"subtype": 991, "id": "22000", "title": "雷达模式"},
						{"subtype": 510, "id": "6059606031", "title": "每日30首"}
					]}]}
				]
			}
		}
	}`)
	if got := findQQDailyPlaylistID(resp); got != 6059606031 {
		t.Errorf("daily id = %d, want 6059606031", got)
	}

	numeric := mustJSON(t, `{
		"music.recommend.RecommendFeed.get_recommend_feed": {
			"data": {"v_shelf": [{"v_niche": [{"v_card": [{"subtype": 510, "id": 932184032}]}]}]}
		}
	}`)
	if got := findQQDailyPlaylistID(numeric); got != 932184032 {
		t.Errorf("daily id = %d, want 932184032 for numeric id", got)
	}

	missing := mustJSON(t, `{"music.recommend.RecommendFeed.get_recommend_feed": {"data": {"v_shelf": []}}}`)
	if got := findQQDailyPlaylistID(missing); got != 0 {
		t.Errorf("daily id = %d, want 0 for empty feed", got)
	}
}

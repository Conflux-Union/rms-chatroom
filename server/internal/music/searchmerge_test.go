package music

import "testing"

func TestSortSongsBySimilarity(t *testing.T) {
	songs := []SongResult{
		{Mid: "qq-1", Name: "晴天", Artist: "周杰伦", Platform: "qq"},
		{Mid: "qq-2", Name: "晴天(Live)", Artist: "周杰伦", Platform: "qq"},
		{Mid: "ne-1", Name: "晴天", Artist: "周杰伦", Platform: "netease"},
		{Mid: "ne-2", Name: "其他不相关歌曲", Artist: "别人", Platform: "netease"},
	}

	SortSongsBySimilarity(songs, "晴天")

	if songs[0].Name != "晴天" || songs[1].Name != "晴天" {
		t.Fatalf("exact matches should lead, got %s then %s", songs[0].Name, songs[1].Name)
	}
	if songs[0].Platform == songs[1].Platform {
		t.Fatalf("exact qq and netease matches should interleave, both came from %s", songs[0].Platform)
	}
	if songs[len(songs)-1].Name != "其他不相关歌曲" {
		t.Fatalf("unrelated song should sort last, got %s", songs[len(songs)-1].Name)
	}
}

func TestSortSongsBySimilarityArtistQuery(t *testing.T) {
	songs := []SongResult{
		{Mid: "qq-1", Name: "七里香", Artist: "周杰伦", Platform: "qq"},
		{Mid: "ne-1", Name: " unrelated ", Artist: "某人", Platform: "netease"},
	}

	SortSongsBySimilarity(songs, " 周杰伦 ")

	if songs[0].Mid != "qq-1" {
		t.Fatalf("artist match should rank first, got %s", songs[0].Mid)
	}
}

func TestSortSongsBySimilarityEmptyKeyword(t *testing.T) {
	songs := []SongResult{{Mid: "a"}, {Mid: "b"}}
	SortSongsBySimilarity(songs, "  ")
	if songs[0].Mid != "a" || songs[1].Mid != "b" {
		t.Fatal("empty keyword must not reorder")
	}
}

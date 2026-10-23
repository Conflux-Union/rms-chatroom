package handler

import (
	"context"
	"log"
	"sync"

	"github.com/RMS-Server/rms-discord-go/internal/metrics"
	"github.com/RMS-Server/rms-discord-go/internal/music"
)

// Per-song loudness cache. Songs are measured with ffmpeg (ebur128) the
// first time they are queued, and every play broadcast carries a
// loudness_gain that normalizes the song to music.TargetLoudnessLUFS so
// track-to-track volume stays even. The first playback of an unanalyzed
// song passes through at full gain; analysis finishes long before the song
// comes back around.

// analysisSlots bounds concurrent ffmpeg runs; each analysis downloads part
// of the track, so a burst of queued songs must not fetch them all at once.
const analysisSlots = 2

var (
	loudnessAnalysisSem = make(chan struct{}, analysisSlots)
	loudnessInFlight    = make(map[string]struct{})
	loudnessInFlightMu  sync.Mutex
)

// getSongLoudnessGain returns the cached normalization gain for a song, or
// 1.0 (passthrough) when it has not been analyzed yet; a cache miss also
// kicks off analysis so the song is normalized on its next playback.
func getSongLoudnessGain(platform, mid string) float64 {
	lufs, ok := lookupSongLoudness(platform, mid)
	if !ok {
		go analyzeSongLoudness(platform, mid)
		return 1
	}
	return music.LoudnessGain(lufs)
}

func lookupSongLoudness(platform, mid string) (float64, bool) {
	var lufs float64
	err := musicDB.QueryRow(
		"SELECT lufs FROM music_song_loudness WHERE platform = ? AND song_id = ?",
		platform, mid,
	).Scan(&lufs)
	if err != nil {
		return 0, false
	}
	return lufs, true
}

// analyzeSongLoudness fetches a fresh CDN URL, measures the song's
// integrated loudness, and caches the result. Concurrent analyses of the
// same song are collapsed to one.
func analyzeSongLoudness(platform, mid string) {
	key := platform + "/" + mid
	loudnessInFlightMu.Lock()
	if _, busy := loudnessInFlight[key]; busy {
		loudnessInFlightMu.Unlock()
		return
	}
	loudnessInFlight[key] = struct{}{}
	loudnessInFlightMu.Unlock()
	defer func() {
		loudnessInFlightMu.Lock()
		delete(loudnessInFlight, key)
		loudnessInFlightMu.Unlock()
	}()

	if _, cached := lookupSongLoudness(platform, mid); cached {
		return
	}

	loudnessAnalysisSem <- struct{}{}
	audioURL, err := getSongURL(mid, platform)
	<-loudnessAnalysisSem
	if err != nil || audioURL == "" {
		metrics.MusicLoudnessAnalysis.WithLabelValues(platform, "url_error").Inc()
		return
	}

	loudnessAnalysisSem <- struct{}{}
	lufs, err := music.AnalyzeLoudness(context.Background(), audioURL)
	<-loudnessAnalysisSem
	if err != nil {
		log.Printf("music: loudness analysis failed for %s: %v", key, err)
		metrics.MusicLoudnessAnalysis.WithLabelValues(platform, "error").Inc()
		return
	}

	if _, err := musicDB.Exec(
		"INSERT INTO music_song_loudness (platform, song_id, lufs, analyzed_at) VALUES (?, ?, ?, UTC_TIMESTAMP()) "+
			"ON DUPLICATE KEY UPDATE lufs = VALUES(lufs), analyzed_at = UTC_TIMESTAMP()",
		platform, mid, lufs,
	); err != nil {
		log.Printf("music: loudness cache write failed for %s: %v", key, err)
	}
	metrics.MusicLoudnessAnalysis.WithLabelValues(platform, "ok").Inc()
}

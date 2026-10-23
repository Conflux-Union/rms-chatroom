package music

import (
	"context"
	"fmt"
	"math"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Song loudness measurement via ffmpeg's ebur128 filter (EBU R128 integrated
// loudness). The QQ/NetEase CDN encodes are loudness-war masters averaging
// around -8 LUFS, so without normalization track volume swings wildly between
// songs and sits far above a voice-calibrated system volume.

const (
	// TargetLoudnessLUFS is the level every song is normalized to (the
	// Apple/Amazon streaming standard). Normalization is attenuation-only:
	// player volumes cap at 1.0, so quiet masters keep their original level.
	TargetLoudnessLUFS = -16.0

	// loudnessReadCap limits how much of a track is decoded; the integrated
	// value over the first minutes is representative enough and bounds the
	// download size for long tracks.
	loudnessReadCap = 240 * time.Second
	// loudnessRunTimeout bounds the whole ffmpeg run (decode is ~200x
	// realtime, so the network download dominates).
	loudnessRunTimeout = 90 * time.Second
)

// Matches the summary line "  I:         -8.5 LUFS" only: the per-frame
// filter log lines carry the I: value inline with trailing fields, so they
// never end right after LUFS.
var integratedLoudnessRe = regexp.MustCompile(`(?m)^\s+I:\s+(-?[\d.]+|-inf)\s+LUFS\s*$`)

// AnalyzeLoudness decodes audioURL through ffmpeg and returns its EBU R128
// integrated loudness in LUFS. Requires ffmpeg on PATH; URLs may be http(s)
// or file:// paths.
func AnalyzeLoudness(ctx context.Context, audioURL string) (float64, error) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		return 0, fmt.Errorf("ffmpeg not available: %w", err)
	}
	ctx, cancel := context.WithTimeout(ctx, loudnessRunTimeout)
	defer cancel()
	args := []string{"-hide_banner", "-nostats"}
	// -user_agent is a network-protocol option and makes ffmpeg abort on
	// non-http inputs (file://), so it is only added for remote URLs.
	if strings.HasPrefix(audioURL, "http") {
		args = append(args, "-user_agent", "Mozilla/5.0 (rms-chatroom)")
	}
	args = append(args,
		"-t", strconv.FormatInt(int64(loudnessReadCap.Seconds()), 10),
		"-i", audioURL,
		"-map", "0:a:0",
		"-filter:a", "ebur128=peak=true",
		"-f", "null", "-",
	)
	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		tail := string(out)
		if len(tail) > 512 {
			tail = tail[len(tail)-512:]
		}
		return 0, fmt.Errorf("ffmpeg: %w: %s", err, tail)
	}
	return parseIntegratedLoudness(string(out))
}

func parseIntegratedLoudness(output string) (float64, error) {
	matches := integratedLoudnessRe.FindAllStringSubmatch(output, -1)
	if len(matches) == 0 {
		return 0, fmt.Errorf("ebur128 integrated loudness not found in ffmpeg output")
	}
	v := matches[len(matches)-1][1]
	if v == "-inf" {
		return 0, fmt.Errorf("audio is silent (-inf LUFS)")
	}
	lufs, err := strconv.ParseFloat(v, 64)
	if err != nil {
		return 0, fmt.Errorf("invalid LUFS value %q", v)
	}
	return lufs, nil
}

// LoudnessGain converts an integrated loudness in LUFS into the linear gain
// that brings the song to TargetLoudnessLUFS, clamped to (0, 1] so a bad
// measurement can never boost or mute playback. Non-negative measurements
// are physically impossible and treated as missing data.
func LoudnessGain(lufs float64) float64 {
	if math.IsNaN(lufs) || lufs >= 0 {
		return 1
	}
	gain := math.Pow(10, (TargetLoudnessLUFS-lufs)/20)
	if gain <= 0 || gain > 1 {
		return 1
	}
	return gain
}

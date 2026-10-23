package music

import (
	"context"
	"math"
	"os/exec"
	"path/filepath"
	"testing"
)

const ebur128SampleOutput = `[Parsed_ebur128_0 @ 0x64a86e2cbe80] t: 2.99998    TARGET:-23 LUFS    M: -27.8 S:-27.8     I: -27.8 LUFS       LRA:  20.0 LU  FTPK: -24.1 dBFS  TPK: -24.1 dBFS
[out#0/null @ 0x64a86e2c52c0] video:0kB audio:258kB subtitle:0kB global headers:0kB muxing overhead: unknown
size=N/A time=00:00:02.90 bitrate=N/A speed= 230x
[Parsed_ebur128_0 @ 0x64a86e2cbe80] Summary:

  Integrated loudness:
    I:         -8.5 LUFS
    Threshold: -18.7 LUFS

  Loudness range:
    LRA:        8.0 LU
    Threshold: -28.7 LUFS
    LRA low:   -14.2 LUFS
    LRA high:   -6.2 LUFS

  True peak:
    Peak:      -1.0 dBFS
`

func TestParseIntegratedLoudness(t *testing.T) {
	tests := []struct {
		name    string
		output  string
		want    float64
		wantErr bool
	}{
		{
			name:   "summary after per-frame log lines",
			output: ebur128SampleOutput,
			want:   -8.5,
		},
		{
			name:   "positive LUFS",
			output: "  Integrated loudness:\n    I:         1.2 LUFS\n",
			want:   1.2,
		},
		{
			name:    "silence",
			output:  "  Integrated loudness:\n    I:         -inf LUFS\n",
			wantErr: true,
		},
		{
			name:    "no summary",
			output:  "Conversion failed!",
			wantErr: true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := parseIntegratedLoudness(tt.output)
			if tt.wantErr {
				if err == nil {
					t.Fatalf("expected error, got %v", got)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if math.Abs(got-tt.want) > 0.001 {
				t.Errorf("got %v LUFS, want %v", got, tt.want)
			}
		})
	}
}

func TestLoudnessGain(t *testing.T) {
	tests := []struct {
		name string
		lufs float64
		want float64
	}{
		{"loud master attenuated to target", -8.5, 0.4217},
		{"at target stays 1.0", -16.0, 1.0},
		{"quieter than target never boosted", -30.0, 1.0},
		{"impossible measurement ignored", 40.0, 1.0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := LoudnessGain(tt.lufs)
			if math.Abs(got-tt.want) > 0.001 {
				t.Errorf("LoudnessGain(%v) = %v, want %v", tt.lufs, got, tt.want)
			}
		})
	}
}

// TestAnalyzeLoudnessEndToEnd runs the real ffmpeg binary on a generated
// tone so the exec plumbing (flags, URL handling, output parsing) is covered.
func TestAnalyzeLoudnessEndToEnd(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	path := filepath.Join(t.TempDir(), "tone.mp3")
	gen := exec.Command("ffmpeg", "-hide_banner", "-loglevel", "error",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=3",
		"-q:a", "9", path)
	if out, err := gen.CombinedOutput(); err != nil {
		t.Fatalf("generate tone: %v: %s", err, out)
	}
	lufs, err := AnalyzeLoudness(context.Background(), "file://"+path)
	if err != nil {
		t.Fatalf("AnalyzeLoudness: %v", err)
	}
	// A full-scale 440 Hz sine sits around -23 LUFS; allow a wide band so the
	// test asserts "a plausible measurement" rather than an exact value.
	if lufs < -30 || lufs > -16 {
		t.Errorf("unexpected loudness for full-scale sine: %v LUFS", lufs)
	}
}

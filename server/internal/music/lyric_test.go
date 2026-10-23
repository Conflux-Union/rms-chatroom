package music

import "testing"

func TestQQNormalizeTranslation(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want string
	}{
		{
			name: "placeholder only",
			in:   "[ti:Shape of You]\n[00:00.00]//\n[00:02.38]//\n",
			want: "",
		},
		{
			name: "real translation kept",
			in:   "[00:00.85]如果这一切都是梦境该有多好\n[00:06.65]//\n",
			want: "[00:00.85]如果这一切都是梦境该有多好\n[00:06.65]//\n",
		},
		{
			name: "empty",
			in:   "",
			want: "",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := qqNormalizeTranslation(tt.in); got != tt.want {
				t.Errorf("qqNormalizeTranslation() = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestQrcLinesToLrc(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want string
	}{
		{
			name: "qrc xml with word tags",
			in: `<?xml version="1.0" encoding="utf-8"?>
<QrcHeadInfo Title="t" Author="a" Composer=""/>
<LyricInfo LyricCount="1">
<Lyric_1 LyricType="1">
<Text> Lyrics </Text>
<LyricLine>
<Line Time="2860">真的想说<W T="2860">那不是梦</W></Line>
<Line Time="6900">second &amp; line</Line>
</LyricLine>
</Lyric_1>
</LyricInfo>`,
			want: "[00:02.86]真的想说那不是梦\n[00:06.90]second & line\n",
		},
		{
			name: "plain lrc passthrough rejected",
			in:   "[00:01.00]not xml",
			want: "",
		},
		{
			name: "lines without time attr skipped",
			in:   `<LyricInfo><Line>no time</Line></LyricInfo>`,
			want: "",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := qrcLinesToLrc(tt.in); got != tt.want {
				t.Errorf("qrcLinesToLrc() = %q, want %q", got, tt.want)
			}
		})
	}
}

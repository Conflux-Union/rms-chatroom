// Package version holds build-time version info, injected via -ldflags by
// the release workflow:
//
//	-X github.com/RMS-Server/rms-discord-go/internal/version.Name=1.0.8
//	-X github.com/RMS-Server/rms-discord-go/internal/version.Code=34
//	-X github.com/RMS-Server/rms-discord-go/internal/version.Commit=abc1234
package version

import "strconv"

var (
	Name   = "dev"
	Code   = "0"
	Commit = "unknown"
)

// Full returns the canonical version string shared with the Android and
// desktop clients, e.g. "v1.0.8(34)(commit:abc1234)".
func Full() string {
	return "v" + Name + "(" + Code + ")(commit:" + Commit + ")"
}

// CodeInt returns the numeric build code; dev builds ("0" or unparsable)
// return 0 so any released build code compares as newer.
func CodeInt() int {
	n, err := strconv.Atoi(Code)
	if err != nil {
		return 0
	}
	return n
}

package handler

import "testing"

func loopState(mode string, queueLen, current int) *RoomMusicState {
	return &RoomMusicState{
		RoomName:     "test",
		PlayQueue:    make([]queueItem, queueLen),
		CurrentIndex: current,
		LoopMode:     mode,
	}
}

func TestNextQueueIndex(t *testing.T) {
	tests := []struct {
		name    string
		mode    string
		queue   int
		current int
		manual  bool
		want    int
	}{
		{name: "sequence advances", mode: loopModeSequence, queue: 3, current: 1, want: 2},
		{name: "sequence stops at end", mode: loopModeSequence, queue: 3, current: 2, want: -1},
		{name: "empty legacy mode behaves as sequence", mode: "", queue: 3, current: 2, want: -1},
		{name: "empty queue stops", mode: loopModeList, queue: 0, current: 0, want: -1},
		{name: "list advances", mode: loopModeList, queue: 3, current: 1, want: 2},
		{name: "list wraps to first", mode: loopModeList, queue: 3, current: 2, want: 0},
		{name: "list single song stays", mode: loopModeList, queue: 1, current: 0, want: 0},
		{name: "one repeats on natural end", mode: loopModeOne, queue: 3, current: 1, want: 1},
		{name: "one advances on manual skip", mode: loopModeOne, queue: 3, current: 1, manual: true, want: 2},
		{name: "one wraps on manual skip at end", mode: loopModeOne, queue: 3, current: 2, manual: true, want: 0},
		{name: "random single song stays", mode: loopModeRandom, queue: 1, current: 0, want: 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := nextQueueIndex(loopState(tt.mode, tt.queue, tt.current), tt.manual); got != tt.want {
				t.Errorf("nextQueueIndex(%s, queue=%d, current=%d, manual=%v) = %d, want %d",
					tt.mode, tt.queue, tt.current, tt.manual, got, tt.want)
			}
		})
	}
}

func TestNextQueueIndexRandomNeverRepeatsCurrent(t *testing.T) {
	s := loopState(loopModeRandom, 3, 1)
	for i := 0; i < 100; i++ {
		got := nextQueueIndex(s, false)
		if got != 0 && got != 2 {
			t.Fatalf("random next index = %d, want 0 or 2 (never the current song 1)", got)
		}
	}
	// Manual skip in random mode picks a random track too.
	for i := 0; i < 100; i++ {
		got := nextQueueIndex(s, true)
		if got != 0 && got != 2 {
			t.Fatalf("random manual-skip index = %d, want 0 or 2", got)
		}
	}
}

func TestValidLoopMode(t *testing.T) {
	for _, mode := range []string{"sequence", "list", "one", "random"} {
		if !validLoopMode(mode) {
			t.Errorf("validLoopMode(%q) = false, want true", mode)
		}
	}
	for _, mode := range []string{"", "shuffle", "LOOP", "single"} {
		if validLoopMode(mode) {
			t.Errorf("validLoopMode(%q) = true, want false", mode)
		}
	}
}

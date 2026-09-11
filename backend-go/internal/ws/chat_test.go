package ws

import (
	"encoding/json"
	"strings"
	"testing"
)

// Live messages reach clients only through this payload: Main.vue builds the
// store message straight from it (data.avatar_url), so a missing key makes
// every new message render the first-letter fallback. Pin the field in the
// marshaled contract.
func TestChatBroadcastMarshalIncludesAvatarURL(t *testing.T) {
	b, err := json.Marshal(chatBroadcast{
		Type:        "message",
		ID:          1,
		ChannelID:   2,
		UserID:      3,
		Username:    "alice",
		AvatarURL:   "https://sso.example/avatar.png",
		Content:     "hi",
		CreatedAt:   "2026-09-12 00:00:00",
		Attachments: []attachmentPayload{},
		Mentions:    []string{},
	})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if !strings.Contains(string(b), `"avatar_url":"https://sso.example/avatar.png"`) {
		t.Fatalf("broadcast payload missing avatar_url: %s", b)
	}
}

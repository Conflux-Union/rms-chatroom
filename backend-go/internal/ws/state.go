package ws

// GetRoomPlaybackState is set by handler/music.go init() to provide
// current playback state for late-joining music WebSocket clients.
var GetRoomPlaybackState func(roomName string) map[string]interface{}

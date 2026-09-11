// Package chatbridge implements the client side of the ChatBridge v2
// protocol (github.com/TISUnion/ChatBridge), so the backend can join a
// cross-server Minecraft chat network as one more client.
//
// Wire format (mirrors chatbridge/core/network/net_util.py):
// a packet is a 4-byte little-endian length prefix followed by the payload
// bytes. The payload is the packet's JSON, either plain UTF-8 when no AES key
// is configured, or AES-256-CBC encrypted and hex-encoded otherwise.
package chatbridge

import "encoding/json"

const (
	serverName = "#SERVER"

	typeKeepAlive = "chatbridge.keep_alive"
	typeChat      = "chatbridge.chat"
)

// loginPacket is the very first packet on a fresh connection, sent bare (not
// wrapped in packet). The server answers with loginResultPacket.
type loginPacket struct {
	Name     string `json:"name"`
	Password string `json:"password"`
}

type loginResultPacket struct {
	Message string `json:"message"`
}

// packet is the generic envelope used after login. Payload stays raw and is
// decoded per packet type.
type packet struct {
	Sender    string          `json:"sender"`
	Receivers []string        `json:"receivers"`
	Broadcast bool            `json:"broadcast"`
	Type      string          `json:"type"`
	Payload   json.RawMessage `json:"payload"`
}

// chatPayload carries one chat line. author is the in-game player name, or
// empty for system events (player joined/left, server start/stop).
type chatPayload struct {
	Author  string `json:"author"`
	Message string `json:"message"`
}

type keepAlivePayload struct {
	PingType string `json:"ping_type"`
}

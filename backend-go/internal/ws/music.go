package ws

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/jwtutil"
)

// MusicRoomManager manages per-room WebSocket client sets for music sync.
type MusicRoomManager struct {
	mu    sync.RWMutex
	rooms map[string]map[*Conn]struct{} // room_name -> set of connections
	connRoom map[*Conn]string           // reverse lookup: conn -> current room
}

var musicRooms = &MusicRoomManager{
	rooms:    make(map[string]map[*Conn]struct{}),
	connRoom: make(map[*Conn]string),
}

func (m *MusicRoomManager) join(room string, c *Conn) {
	m.mu.Lock()
	defer m.mu.Unlock()

	// Leave old room if any
	if old, ok := m.connRoom[c]; ok && old != room {
		if set, exists := m.rooms[old]; exists {
			delete(set, c)
			if len(set) == 0 {
				delete(m.rooms, old)
			}
		}
	}

	if m.rooms[room] == nil {
		m.rooms[room] = make(map[*Conn]struct{})
	}
	m.rooms[room][c] = struct{}{}
	m.connRoom[c] = room
}

func (m *MusicRoomManager) leave(c *Conn) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if room, ok := m.connRoom[c]; ok {
		if set, exists := m.rooms[room]; exists {
			delete(set, c)
			if len(set) == 0 {
				delete(m.rooms, room)
			}
		}
		delete(m.connRoom, c)
	}
}

// BroadcastToRoom sends a message to all clients in a room.
func (m *MusicRoomManager) BroadcastToRoom(room string, msg interface{}) {
	data, err := json.Marshal(msg)
	if err != nil {
		return
	}
	m.mu.RLock()
	defer m.mu.RUnlock()

	for c := range m.rooms[room] {
		select {
		case c.send <- data:
		default:
		}
	}
}

// BroadcastToAll sends a message to all music clients across all rooms.
func (m *MusicRoomManager) BroadcastToAll(msg interface{}) {
	data, err := json.Marshal(msg)
	if err != nil {
		return
	}
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, conns := range m.rooms {
		for c := range conns {
			select {
			case c.send <- data:
			default:
			}
		}
	}
}

// GetMusicRoomManager returns the singleton music room manager.
func GetMusicRoomManager() *MusicRoomManager {
	return musicRooms
}

// musicWsMessage represents an incoming music WebSocket message.
type musicWsMessage struct {
	Type     string `json:"type"`
	Data     string `json:"data"`
	RoomName string `json:"room_name"`
}

// HandleMusicWS handles the /ws/music WebSocket endpoint.
func HandleMusicWS(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		token := c.QueryParam("token")
		if token == "" {
			return c.JSON(http.StatusUnauthorized, map[string]string{"error": "missing token"})
		}

		user, err := jwtutil.ParseToken(token, jwtSecret)
		if err != nil {
			return c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid token"})
		}

		roomName := c.QueryParam("room_name")

		ws, err := upgrader.Upgrade(c.Response(), c.Request(), nil)
		if err != nil {
			return err
		}

		conn := newConn(ws, user)

		// Join initial room if provided
		if roomName != "" {
			musicRooms.join(roomName, conn)
		}
		defer musicRooms.leave(conn)

		connected, _ := json.Marshal(map[string]string{"type": "connected"})
		conn.send <- connected

		// Send current playback state for late joiners
		if roomName != "" {
			sendCurrentPlaybackState(conn, roomName)
		}

		go conn.WritePump()

		conn.ReadPump(func(raw []byte) {
			var msg musicWsMessage
			if err := json.Unmarshal(raw, &msg); err != nil {
				return
			}

			if msg.Type == "ping" && msg.Data == "tribios" {
				pong, _ := json.Marshal(map[string]string{"type": "pong", "data": "cute"})
				conn.send <- pong
				return
			}

			if msg.Type == "join_room" && msg.RoomName != "" {
				musicRooms.join(msg.RoomName, conn)
				sendCurrentPlaybackState(conn, msg.RoomName)
				return
			}
		})

		return nil
	}
}

func sendCurrentPlaybackState(conn *Conn, roomName string) {
	state := GetRoomPlaybackState(roomName)
	if state == nil {
		return
	}

	state["type"] = "play"
	state["server_time"] = float64(time.Now().UnixMilli()) / 1000.0
	data, err := json.Marshal(state)
	if err != nil {
		log.Printf("ws/music: failed to marshal playback state: %v", err)
		return
	}
	conn.send <- data
}

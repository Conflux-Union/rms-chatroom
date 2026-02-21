package ws

import (
	"encoding/json"
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"github.com/RMS-Server/rms-discord-go/internal/permission"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingInterval   = 5 * time.Second
	maxMessageSize = 64 * 1024

	// Health monitor intervals
	healthScanInterval = 30 * time.Second
	inactiveThreshold  = 60 * time.Second
	deadThreshold      = 90 * time.Second
)

// Conn wraps a single WebSocket connection with user info and a write channel.
type Conn struct {
	ws       *websocket.Conn
	user     *permission.UserInfo
	send     chan []byte
	lastPing time.Time
	mu       sync.Mutex
}

func newConn(ws *websocket.Conn, user *permission.UserInfo) *Conn {
	return &Conn{
		ws:       ws,
		user:     user,
		send:     make(chan []byte, 256),
		lastPing: time.Now(),
	}
}

// ConnectionManager manages WebSocket connections grouped by channel and user.
type ConnectionManager struct {
	mu sync.RWMutex

	// channel_id -> connections (for channel-scoped broadcasts)
	channels map[int64][]*Conn

	// user_id -> connections (for global/user-scoped broadcasts)
	globals map[int64][]*Conn

	stopHeartbeat chan struct{}
	heartbeatOnce sync.Once
}

// NewConnectionManager creates a new manager instance.
func NewConnectionManager() *ConnectionManager {
	return &ConnectionManager{
		channels:      make(map[int64][]*Conn),
		globals:       make(map[int64][]*Conn),
		stopHeartbeat: make(chan struct{}),
	}
}

// ConnectChannel registers a connection under a channel ID.
func (m *ConnectionManager) ConnectChannel(channelID int64, c *Conn) {
	m.mu.Lock()
	m.channels[channelID] = append(m.channels[channelID], c)
	m.mu.Unlock()
}

// DisconnectChannel removes a connection from a channel.
func (m *ConnectionManager) DisconnectChannel(channelID int64, c *Conn) {
	m.mu.Lock()
	conns := m.channels[channelID]
	for i, cc := range conns {
		if cc == c {
			m.channels[channelID] = append(conns[:i], conns[i+1:]...)
			break
		}
	}
	if len(m.channels[channelID]) == 0 {
		delete(m.channels, channelID)
	}
	m.mu.Unlock()
}

// ConnectGlobal registers a connection under the user's ID for global broadcasts.
func (m *ConnectionManager) ConnectGlobal(c *Conn) {
	uid := int64(c.user.ID)
	m.mu.Lock()
	m.globals[uid] = append(m.globals[uid], c)
	m.mu.Unlock()
}

// DisconnectGlobal removes a global connection.
func (m *ConnectionManager) DisconnectGlobal(c *Conn) {
	uid := int64(c.user.ID)
	m.mu.Lock()
	conns := m.globals[uid]
	for i, cc := range conns {
		if cc == c {
			m.globals[uid] = append(conns[:i], conns[i+1:]...)
			break
		}
	}
	if len(m.globals[uid]) == 0 {
		delete(m.globals, uid)
	}
	m.mu.Unlock()
}

// BroadcastToChannel sends a JSON message to all connections in a channel.
func (m *ConnectionManager) BroadcastToChannel(channelID int64, msg interface{}) {
	data, err := json.Marshal(msg)
	if err != nil {
		return
	}
	m.mu.RLock()
	conns := m.channels[channelID]
	for _, c := range conns {
		select {
		case c.send <- data:
		default:
			// Drop message if buffer full
		}
	}
	m.mu.RUnlock()
}

// BroadcastToAllUsers sends a JSON message to every global connection.
func (m *ConnectionManager) BroadcastToAllUsers(msg interface{}) {
	data, err := json.Marshal(msg)
	if err != nil {
		return
	}
	m.mu.RLock()
	for _, conns := range m.globals {
		for _, c := range conns {
			select {
			case c.send <- data:
			default:
			}
		}
	}
	m.mu.RUnlock()
}

// SendToUser sends a JSON message to a specific user's global connections.
func (m *ConnectionManager) SendToUser(userID int64, msg interface{}) {
	data, err := json.Marshal(msg)
	if err != nil {
		return
	}
	m.mu.RLock()
	conns := m.globals[userID]
	for _, c := range conns {
		select {
		case c.send <- data:
		default:
		}
	}
	m.mu.RUnlock()
}

// SendToUserExclude sends to all of a user's connections except the given one.
func (m *ConnectionManager) SendToUserExclude(userID int64, msg interface{}, exclude *Conn) {
	data, err := json.Marshal(msg)
	if err != nil {
		return
	}
	m.mu.RLock()
	conns := m.globals[userID]
	for _, c := range conns {
		if c == exclude {
			continue
		}
		select {
		case c.send <- data:
		default:
		}
	}
	m.mu.RUnlock()
}

// StartHeartbeat launches the health monitor goroutine.
func (m *ConnectionManager) StartHeartbeat() {
	m.heartbeatOnce.Do(func() {
		go m.healthMonitor()
	})
}

// StopHeartbeat signals the health monitor to stop.
func (m *ConnectionManager) StopHeartbeat() {
	select {
	case m.stopHeartbeat <- struct{}{}:
	default:
	}
}

func (m *ConnectionManager) healthMonitor() {
	ticker := time.NewTicker(healthScanInterval)
	defer ticker.Stop()

	for {
		select {
		case <-m.stopHeartbeat:
			return
		case <-ticker.C:
			m.scanConnections()
		}
	}
}

func (m *ConnectionManager) scanConnections() {
	now := time.Now()
	m.mu.RLock()
	var dead []*Conn
	var inactive []*Conn

	scan := func(conns []*Conn) {
		for _, c := range conns {
			c.mu.Lock()
			idle := now.Sub(c.lastPing)
			c.mu.Unlock()
			if idle > deadThreshold {
				dead = append(dead, c)
			} else if idle > inactiveThreshold {
				inactive = append(inactive, c)
			}
		}
	}

	for _, conns := range m.globals {
		scan(conns)
	}
	for _, conns := range m.channels {
		scan(conns)
	}
	m.mu.RUnlock()

	// Send server-initiated ping to inactive connections
	pingMsg, _ := json.Marshal(map[string]string{"type": "ping", "data": "tribios"})
	for _, c := range inactive {
		select {
		case c.send <- pingMsg:
		default:
		}
	}

	// Force close dead connections
	for _, c := range dead {
		log.Printf("ws: force closing dead connection user=%d", c.user.ID)
		c.ws.Close()
	}
}

// ReadPump reads messages from the WebSocket and calls handler for each.
// It updates lastPing on every received message.
func (c *Conn) ReadPump(handler func([]byte)) {
	defer c.ws.Close()
	c.ws.SetReadLimit(maxMessageSize)
	c.ws.SetReadDeadline(time.Now().Add(pongWait))
	c.ws.SetPongHandler(func(string) error {
		c.ws.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		_, msg, err := c.ws.ReadMessage()
		if err != nil {
			break
		}
		c.mu.Lock()
		c.lastPing = time.Now()
		c.mu.Unlock()
		handler(msg)
	}
}

// WritePump drains the send channel and writes to the WebSocket.
func (c *Conn) WritePump() {
	ticker := time.NewTicker(pingInterval)
	defer func() {
		ticker.Stop()
		c.ws.Close()
	}()

	for {
		select {
		case msg, ok := <-c.send:
			c.ws.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				c.ws.WriteMessage(websocket.CloseMessage, nil)
				return
			}
			if err := c.ws.WriteMessage(websocket.TextMessage, msg); err != nil {
				return
			}
		case <-ticker.C:
			c.ws.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.ws.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// Singleton manager instances
var (
	ChatManager        = NewConnectionManager()
	VoiceManager       = NewConnectionManager()
	GlobalStateManager = NewConnectionManager()
)

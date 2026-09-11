package chatbridge

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/RMS-Server/rms-discord-go/internal/metrics"
)

const (
	keepAliveInterval = 60 * time.Second
	keepAliveTimeout  = 15 * time.Second
	loginTimeout      = 10 * time.Second
	maxPacketSize     = 8 << 20
	initialBackoff    = 5 * time.Second
	maxBackoff        = time.Minute
)

// Client is a ChatBridge v2 network client. It maintains one TCP connection
// to the ChatBridge server, answers keep-alive pings, and relays chat in both
// directions. Zero value is not usable; construct with NewClient.
type Client struct {
	cfg    Config
	cr     *cryptor
	onChat func(sender, author, message string)

	mu       sync.Mutex // guards conn and write serialization
	conn     net.Conn
	lastPong atomic.Int64 // unix nano of the last keep-alive pong
}

// Config identifies this backend on the ChatBridge network. ChannelID is
// carried so the package-level SendChat helper can filter which platform
// channel is bridged.
type Config struct {
	ServerHost string
	ServerPort int
	AESKey     string
	Name       string
	Password   string
	ChannelID  int64
}

func NewClient(cfg Config, onChat func(sender, author, message string)) *Client {
	return &Client{
		cfg:    cfg,
		cr:     newCryptor(cfg.AESKey),
		onChat: onChat,
	}
}

// Default is the process-wide client set up at startup; nil when the
// chatbridge config is disabled or absent, making SendChat a no-op.
var Default *Client

// SendChat relays a platform-user message from channelID to the ChatBridge
// network. It is safe to call from any send path; it drops the message with
// a log line when the bridge is disabled, the channel is not bridged, or the
// connection is down.
func SendChat(channelID int64, author, message string) {
	if Default == nil || Default.cfg.ChannelID != channelID {
		return
	}
	Default.SendChat(author, message)
}

// SendChat broadcasts one chat line to every other client on the network.
// The server does not echo broadcasts back to the sender, so this cannot
// loop back into OnChat.
func (c *Client) SendChat(author, message string) {
	payload, err := json.Marshal(chatPayload{Author: author, Message: message})
	if err != nil {
		return
	}
	pkt := packet{
		Sender:    c.cfg.Name,
		Receivers: []string{},
		Broadcast: true,
		Type:      typeChat,
		Payload:   payload,
	}
	if err := c.writePacket(pkt); err != nil {
		log.Printf("chatbridge: dropped chat from %q (not connected): %v", author, err)
	}
}

// Run connects, serves, and reconnects forever until ctx is cancelled.
// The retry backoff doubles per failed attempt and resets once a connection
// gets through login, so transient blips don't leave it at the cap forever.
func (c *Client) Run(ctx context.Context) {
	backoff := initialBackoff
	for {
		if ctx.Err() != nil {
			return
		}
		established, err := c.runOnce(ctx)
		c.setOnline(false)
		if ctx.Err() != nil {
			return
		}
		if established {
			backoff = initialBackoff
		}
		if errors.Is(err, io.EOF) {
			log.Printf("chatbridge: connection to %s closed by server", c.addr())
		} else {
			log.Printf("chatbridge: connection to %s lost: %v", c.addr(), err)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff *= 2
		if backoff > maxBackoff {
			backoff = maxBackoff
		}
	}
}

func (c *Client) addr() string {
	return net.JoinHostPort(c.cfg.ServerHost, fmt.Sprintf("%d", c.cfg.ServerPort))
}

// runOnce dials, logs in, and serves until the connection drops. It reports
// whether login had succeeded, so Run can reset its backoff.
func (c *Client) runOnce(ctx context.Context) (bool, error) {
	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", c.addr())
	if err != nil {
		return false, err
	}
	defer conn.Close()

	if err := c.login(conn); err != nil {
		return false, fmt.Errorf("login: %w", err)
	}

	c.setConn(conn)
	defer c.setConn(nil)
	c.setOnline(true)
	log.Printf("chatbridge: logged in to %s as %q", c.addr(), c.cfg.Name)

	closed := make(chan struct{})
	defer close(closed)
	kaDone := make(chan struct{})
	go func() {
		defer close(kaDone)
		c.keepAliveLoop(conn, closed)
	}()

	err = c.readLoop(conn)
	// Wake the keep-alive loop (its writes error out on the closed conn).
	conn.Close()
	<-kaDone
	return true, err
}

func (c *Client) login(conn net.Conn) error {
	body, err := json.Marshal(loginPacket{Name: c.cfg.Name, Password: c.cfg.Password})
	if err != nil {
		return err
	}
	if err := writeFrame(conn, c.cr, body); err != nil {
		return err
	}
	if err := conn.SetReadDeadline(time.Now().Add(loginTimeout)); err != nil {
		return err
	}
	defer conn.SetReadDeadline(time.Time{})
	raw, err := readFrame(conn, c.cr)
	if err != nil {
		return err
	}
	var res loginResultPacket
	if err := json.Unmarshal([]byte(raw), &res); err != nil {
		return fmt.Errorf("bad login result: %w", err)
	}
	if res.Message != "ok" {
		return fmt.Errorf("login rejected: %s", res.Message)
	}
	return nil
}

func (c *Client) readLoop(conn net.Conn) error {
	for {
		raw, err := readFrame(conn, c.cr)
		if err != nil {
			return err
		}
		var pkt packet
		if err := json.Unmarshal([]byte(raw), &pkt); err != nil {
			return fmt.Errorf("bad packet %q: %w", raw, err)
		}
		if err := c.handlePacket(pkt); err != nil {
			return err
		}
	}
}

func (c *Client) handlePacket(pkt packet) error {
	switch pkt.Type {
	case typeKeepAlive:
		var p keepAlivePayload
		if err := json.Unmarshal(pkt.Payload, &p); err != nil {
			return fmt.Errorf("bad keep_alive payload: %w", err)
		}
		switch p.PingType {
		case "ping":
			payload, _ := json.Marshal(keepAlivePayload{PingType: "pong"})
			return c.writePacket(packet{
				Sender:    c.cfg.Name,
				Receivers: []string{pkt.Sender},
				Type:      typeKeepAlive,
				Payload:   payload,
			})
		case "pong":
			c.lastPong.Store(time.Now().UnixNano())
		}
	case typeChat:
		var p chatPayload
		if err := json.Unmarshal(pkt.Payload, &p); err != nil {
			log.Printf("chatbridge: bad chat payload from %s: %v", pkt.Sender, err)
			return nil
		}
		if c.onChat != nil {
			// Synchronous to preserve message order; inserts are fast.
			c.onChat(pkt.Sender, p.Author, p.Message)
		}
	default:
		// command/custom packets are not used by this client.
	}
	return nil
}

func (c *Client) keepAliveLoop(conn net.Conn, closed chan struct{}) {
	ticker := time.NewTicker(keepAliveInterval)
	defer ticker.Stop()
	for {
		select {
		case <-closed:
			return
		case <-ticker.C:
		}
		payload, _ := json.Marshal(keepAlivePayload{PingType: "ping"})
		sent := time.Now().UnixNano()
		pkt := packet{
			Sender:    c.cfg.Name,
			Receivers: []string{serverName},
			Type:      typeKeepAlive,
			Payload:   payload,
		}
		if err := c.writePacket(pkt); err != nil {
			return // conn already dead; readLoop handles the reconnect
		}
		select {
		case <-closed:
			return
		case <-time.After(keepAliveTimeout):
		}
		if c.lastPong.Load() < sent {
			log.Printf("chatbridge: keep-alive pong timeout, dropping connection")
			conn.Close()
			return
		}
	}
}

func (c *Client) setConn(conn net.Conn) {
	c.mu.Lock()
	c.conn = conn
	c.mu.Unlock()
}

// connected reports whether a logged-in connection is currently active.
func (c *Client) connected() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn != nil
}

func (c *Client) setOnline(v bool) {
	if v {
		metrics.ChatBridgeOnline.Set(1)
	} else {
		metrics.ChatBridgeOnline.Set(0)
	}
}

func (c *Client) writePacket(pkt packet) error {
	body, err := json.Marshal(pkt)
	if err != nil {
		return err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn == nil {
		return errors.New("not connected")
	}
	return writeFrame(c.conn, c.cr, body)
}

// writeFrame sends one length-prefixed packet.
func writeFrame(w io.Writer, cr *cryptor, body []byte) error {
	data := cr.encrypt(string(body))
	var head [4]byte
	binary.LittleEndian.PutUint32(head[:], uint32(len(data)))
	if _, err := w.Write(head[:]); err != nil {
		return err
	}
	_, err := w.Write(data)
	return err
}

// readFrame reads one length-prefixed packet and returns its decrypted JSON.
func readFrame(r io.Reader, cr *cryptor) (string, error) {
	var head [4]byte
	if _, err := io.ReadFull(r, head[:]); err != nil {
		return "", err
	}
	n := binary.LittleEndian.Uint32(head[:])
	if n > maxPacketSize {
		return "", fmt.Errorf("packet length %d exceeds limit", n)
	}
	data := make([]byte, n)
	if _, err := io.ReadFull(r, data); err != nil {
		return "", err
	}
	return cr.decrypt(data)
}

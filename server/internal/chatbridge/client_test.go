package chatbridge

import (
	"context"
	"encoding/json"
	"net"
	"strconv"
	"sync"
	"testing"
	"time"
)

// fakeServer is a minimal ChatBridge v2 server: it expects a login, answers
// it, answers keep-alive pings with pongs, and records packets the client
// sends. It reuses this package's framing code, so the test exercises real
// TCP round-trips end to end.
type fakeServer struct {
	ln       net.Listener
	received chan packet

	mu    sync.Mutex
	conns []net.Conn
}

func startFakeServer(t *testing.T) *fakeServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &fakeServer{ln: ln, received: make(chan packet, 16)}
	go s.acceptLoop()
	t.Cleanup(func() {
		ln.Close()
		s.mu.Lock()
		for _, c := range s.conns {
			c.Close()
		}
		s.mu.Unlock()
	})
	return s
}

func (s *fakeServer) addrParts() (string, int) {
	host, portStr, _ := net.SplitHostPort(s.ln.Addr().String())
	port, err := strconv.Atoi(portStr)
	if err != nil {
		panic(err)
	}
	return host, port
}

func (s *fakeServer) acceptLoop() {
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			return
		}
		s.mu.Lock()
		s.conns = append(s.conns, conn)
		s.mu.Unlock()
		go s.serve(conn)
	}
}

func (s *fakeServer) serve(conn net.Conn) {
	cr := newCryptor("test-aes-key")
	raw, err := readFrame(conn, cr)
	if err != nil {
		return
	}
	var lp loginPacket
	if err := json.Unmarshal([]byte(raw), &lp); err != nil {
		return
	}
	body, _ := json.Marshal(loginResultPacket{Message: "ok"})
	if err := writeFrame(conn, cr, body); err != nil {
		return
	}
	for {
		raw, err := readFrame(conn, cr)
		if err != nil {
			return
		}
		var pkt packet
		if err := json.Unmarshal([]byte(raw), &pkt); err != nil {
			return
		}
		switch {
		case pkt.Type == typeKeepAlive:
			var p keepAlivePayload
			json.Unmarshal(pkt.Payload, &p)
			if p.PingType == "ping" {
				pong, _ := json.Marshal(keepAlivePayload{PingType: "pong"})
				writeFrame(conn, cr, mustPacketJSON(typeKeepAlive, serverName, []string{pkt.Sender}, pong))
			}
		default:
			select {
			case s.received <- pkt:
			default:
			}
		}
	}
}

func mustPacketJSON(typ, sender string, receivers []string, payload []byte) []byte {
	pkt := packet{Sender: sender, Receivers: receivers, Type: typ, Payload: payload}
	b, _ := json.Marshal(pkt)
	return b
}

// pushChat delivers a chat packet to the first connected client.
func (s *fakeServer) pushChat(sender, author, message string) {
	payload, _ := json.Marshal(chatPayload{Author: author, Message: message})
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, c := range s.conns {
		writeFrame(c, newCryptor("test-aes-key"), mustPacketJSON(typeChat, sender, []string{"RMS"}, payload))
	}
}

func TestClientEndToEnd(t *testing.T) {
	srv := startFakeServer(t)
	host, port := srv.addrParts()

	got := make(chan [3]string, 4)
	c := NewClient(Config{
		ServerHost: host, ServerPort: port,
		AESKey: "test-aes-key", Name: "RMS", Password: "pw",
		ChannelID: 36,
	}, func(sender, author, message string) {
		got <- [3]string{sender, author, message}
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go c.Run(ctx)

	// SendChat drops messages while offline, so wait for the login first.
	// Polling the client (not the server's login reply) avoids racing the
	// setConn call that happens after the reply is read.
	deadline := time.Now().Add(2 * time.Second)
	for !c.connected() {
		if time.Now().After(deadline) {
			t.Fatal("client did not log in in time")
		}
		time.Sleep(10 * time.Millisecond)
	}

	// Outbound: client chat reaches the server as a broadcast packet.
	c.SendChat("Trirrin", "hello game")
	select {
	case pkt := <-srv.received:
		if pkt.Sender != "RMS" || !pkt.Broadcast || pkt.Type != typeChat {
			t.Fatalf("unexpected packet: %+v", pkt)
		}
		var p chatPayload
		json.Unmarshal(pkt.Payload, &p)
		if p.Author != "Trirrin" || p.Message != "hello game" {
			t.Fatalf("unexpected chat payload: %+v", p)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("server did not receive client chat in time")
	}

	// Inbound: a game chat packet reaches OnChat.
	srv.pushChat("smp", "Steve", "hi from smp")
	select {
	case g := <-got:
		if g != [3]string{"smp", "Steve", "hi from smp"} {
			t.Fatalf("unexpected onChat args: %v", g)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("client did not deliver inbound chat in time")
	}
}

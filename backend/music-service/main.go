package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/livekit/protocol/auth"
	"github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"github.com/pion/webrtc/v3"
)

const (
	SampleRate    = 48000
	Channels      = 2
	FrameDuration = 20 * time.Millisecond
)

type PlayState string

const (
	StateIdle    PlayState = "idle"
	StateLoading PlayState = "loading"
	StatePlaying PlayState = "playing"
	StatePaused  PlayState = "paused"
	StateStopped PlayState = "stopped"
)

type SongInfo struct {
	Mid      string `json:"mid"`
	Name     string `json:"name"`
	Artist   string `json:"artist"`
	Duration int    `json:"duration"` // seconds
	URL      string `json:"url"`
}

type Player struct {
	mu           sync.RWMutex
	room         *lksdk.Room
	roomName     string
	state        PlayState
	currentSong  *SongInfo
	positionMs   int64
	durationMs   int64
	
	ctx          context.Context
	cancel       context.CancelFunc
	seekCh       chan int64
	
	onFinished   func()
}

type PlayerManager struct {
	mu      sync.RWMutex
	players map[string]*Player
	config  *Config
}

type Config struct {
	LiveKitURL    string
	LiveKitAPIKey string
	LiveKitSecret string
}

var manager *PlayerManager

func NewPlayerManager(config *Config) *PlayerManager {
	return &PlayerManager{
		players: make(map[string]*Player),
		config:  config,
	}
}

func (pm *PlayerManager) GetOrCreatePlayer(roomName string) (*Player, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()
	
	if p, ok := pm.players[roomName]; ok {
		return p, nil
	}
	
	p := &Player{
		roomName: roomName,
		state:    StateIdle,
		seekCh:   make(chan int64, 1),
	}
	
	pm.players[roomName] = p
	return p, nil
}

func (pm *PlayerManager) RemovePlayer(roomName string) {
	pm.mu.Lock()
	defer pm.mu.Unlock()
	
	if p, ok := pm.players[roomName]; ok {
		p.Stop()
		delete(pm.players, roomName)
	}
}

func (p *Player) Connect() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	
	if p.room != nil {
		return nil
	}
	
	// Generate token
	at := auth.NewAccessToken(manager.config.LiveKitAPIKey, manager.config.LiveKitSecret)
	grant := &auth.VideoGrant{
		RoomJoin: true,
		Room:     p.roomName,
	}
	at.AddGrant(grant).
		SetIdentity("MusicBot").
		SetName("Music Bot").
		SetKind(livekit.ParticipantInfo_INGRESS)
	
	token, err := at.ToJWT()
	if err != nil {
		return fmt.Errorf("failed to generate token: %w", err)
	}
	
	// Connect to room
	cb := lksdk.NewRoomCallback()
	cb.OnDisconnected = func() {
		log.Printf("Disconnected from room %s", p.roomName)
		p.mu.Lock()
		p.room = nil
		p.mu.Unlock()
	}
	
	room := lksdk.NewRoom(cb)
	
	err = room.JoinWithToken(manager.config.LiveKitURL, token, lksdk.WithAutoSubscribe(false))
	if err != nil {
		return fmt.Errorf("failed to join room: %w", err)
	}
	
	log.Printf("Connected to room %s", p.roomName)
	p.room = room
	
	return nil
}

func (p *Player) Load(song *SongInfo) error {
	p.mu.Lock()
	p.state = StateLoading
	p.currentSong = song
	p.positionMs = 0
	p.durationMs = int64(song.Duration) * 1000
	p.mu.Unlock()
	
	if err := p.Connect(); err != nil {
		return err
	}
	
	return nil
}

func (p *Player) Play() error {
	p.mu.Lock()
	if p.currentSong == nil {
		p.mu.Unlock()
		return fmt.Errorf("no song loaded")
	}
	
	if p.state == StatePlaying {
		p.mu.Unlock()
		return nil
	}
	
	// Cancel previous playback
	if p.cancel != nil {
		p.cancel()
	}
	
	p.ctx, p.cancel = context.WithCancel(context.Background())
	p.state = StatePlaying
	song := p.currentSong
	startPos := p.positionMs
	p.mu.Unlock()
	
	go p.playbackLoop(song, startPos)
	return nil
}

// pipeReadCloser wraps a pipe to implement io.ReadCloser
type pipeReadCloser struct {
	io.Reader
	cmd *exec.Cmd
}

func (p *pipeReadCloser) Close() error {
	if p.cmd != nil && p.cmd.Process != nil {
		p.cmd.Process.Kill()
		p.cmd.Wait()
	}
	return nil
}

func (p *Player) playbackLoop(song *SongInfo, startPosMs int64) {
	log.Printf("Starting playback: %s from %dms", song.Name, startPosMs)
	
	// Use ffmpeg to transcode to Ogg/Opus format
	startSec := float64(startPosMs) / 1000.0
	
	cmd := exec.CommandContext(p.ctx, "ffmpeg",
		"-ss", fmt.Sprintf("%.3f", startSec),
		"-i", song.URL,
		"-c:a", "libopus",
		"-ar", fmt.Sprintf("%d", SampleRate),
		"-ac", fmt.Sprintf("%d", Channels),
		"-b:a", "128k",
		"-compression_level", "10",
		"-frame_duration", "20",
		"-application", "audio",
		"-vn",
		"-f", "ogg",
		"-",
	)
	
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		log.Printf("Failed to get stdout: %v", err)
		return
	}
	
	if err := cmd.Start(); err != nil {
		log.Printf("Failed to start ffmpeg: %v", err)
		return
	}
	
	// Wrap pipe as ReadCloser
	reader := &pipeReadCloser{Reader: stdout, cmd: cmd}
	defer reader.Close()
	
	// Create track with OGG/Opus reader - use MimeTypeOpus which reads from OGG container
	track, err := lksdk.NewLocalReaderTrack(reader, webrtc.MimeTypeOpus,
		lksdk.ReaderTrackWithOnWriteComplete(func() {
			p.mu.Lock()
			// Only mark as finished if we were playing (not paused/stopped)
			if p.state != StatePlaying {
				p.mu.Unlock()
				return
			}
			log.Printf("Playback finished: %s", song.Name)
			p.state = StateStopped
			p.positionMs = p.durationMs
			onFinished := p.onFinished
			p.mu.Unlock()
			
			if onFinished != nil {
				onFinished()
			}
		}),
	)
	if err != nil {
		log.Printf("Failed to create reader track: %v", err)
		return
	}
	
	// Publish track
	p.mu.Lock()
	if p.room == nil {
		p.mu.Unlock()
		log.Printf("Room not connected")
		return
	}
	room := p.room
	p.mu.Unlock()
	
	pub, err := room.LocalParticipant.PublishTrack(track, &lksdk.TrackPublicationOptions{
		Name:   "music",
		Source: livekit.TrackSource_MICROPHONE,
	})
	if err != nil {
		log.Printf("Failed to publish track: %v", err)
		return
	}
	log.Printf("Published track: %s", pub.SID())
	
	// Track progress using a ticker
	currentPosMs := startPosMs
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	
	for {
		select {
		case <-p.ctx.Done():
			room.LocalParticipant.UnpublishTrack(pub.SID())
			return
			
		case newPos := <-p.seekCh:
			// Stop current and restart from new position
			room.LocalParticipant.UnpublishTrack(pub.SID())
			p.mu.Lock()
			p.positionMs = newPos
			p.mu.Unlock()
			go p.playbackLoop(song, newPos)
			return
			
		case <-ticker.C:
			// Update position estimate
			currentPosMs += 100
			if currentPosMs > p.durationMs {
				currentPosMs = p.durationMs
			}
			p.mu.Lock()
			p.positionMs = currentPosMs
			p.mu.Unlock()
		}
	}
}

func (p *Player) Pause() {
	p.mu.Lock()
	defer p.mu.Unlock()
	
	if p.state != StatePlaying {
		return
	}
	
	// Cancel current playback - position is already tracked
	if p.cancel != nil {
		p.cancel()
		p.cancel = nil
	}
	
	p.state = StatePaused
	log.Printf("Paused at %dms", p.positionMs)
}

func (p *Player) Resume() {
	p.mu.Lock()
	
	if p.state != StatePaused {
		p.mu.Unlock()
		return
	}
	
	// Cancel any previous context
	if p.cancel != nil {
		p.cancel()
	}
	
	p.ctx, p.cancel = context.WithCancel(context.Background())
	p.state = StatePlaying
	song := p.currentSong
	startPos := p.positionMs
	p.mu.Unlock()
	
	log.Printf("Resuming from %dms", startPos)
	go p.playbackLoop(song, startPos)
}

func (p *Player) Seek(positionMs int64) {
	p.mu.RLock()
	state := p.state
	p.mu.RUnlock()
	
	if state == StatePlaying || state == StatePaused {
		select {
		case p.seekCh <- positionMs:
		default:
		}
	}
}

func (p *Player) Stop() {
	p.mu.Lock()
	
	if p.cancel != nil {
		p.cancel()
		p.cancel = nil
	}
	
	p.state = StateStopped
	room := p.room
	p.room = nil
	p.mu.Unlock()
	
	// Disconnect outside lock to avoid deadlock
	if room != nil {
		room.Disconnect()
	}
}

func (p *Player) GetProgress() (positionMs int64, durationMs int64, state PlayState, song *SongInfo) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.positionMs, p.durationMs, p.state, p.currentSong
}

func (p *Player) SetOnFinished(fn func()) {
	p.mu.Lock()
	p.onFinished = fn
	p.mu.Unlock()
}

// HTTP Handlers

type PlayRequest struct {
	RoomName string   `json:"room_name"`
	Song     SongInfo `json:"song"`
}

type SeekRequest struct {
	RoomName   string `json:"room_name"`
	PositionMs int64  `json:"position_ms"`
}

type RoomRequest struct {
	RoomName string `json:"room_name"`
}

type ProgressResponse struct {
	PositionMs int64     `json:"position_ms"`
	DurationMs int64     `json:"duration_ms"`
	State      PlayState `json:"state"`
	Song       *SongInfo `json:"song,omitempty"`
}

func handlePlay(c *gin.Context) {
	var req PlayRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	player, err := manager.GetOrCreatePlayer(req.RoomName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	
	if err := player.Load(&req.Song); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	
	if err := player.Play(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	
	c.JSON(http.StatusOK, gin.H{"success": true})
}

func handlePause(c *gin.Context) {
	var req RoomRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	player, err := manager.GetOrCreatePlayer(req.RoomName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	
	player.Pause()
	c.JSON(http.StatusOK, gin.H{"success": true})
}

func handleResume(c *gin.Context) {
	var req RoomRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	player, err := manager.GetOrCreatePlayer(req.RoomName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	
	player.Resume()
	c.JSON(http.StatusOK, gin.H{"success": true})
}

func handleSeek(c *gin.Context) {
	var req SeekRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	player, err := manager.GetOrCreatePlayer(req.RoomName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	
	player.Seek(req.PositionMs)
	c.JSON(http.StatusOK, gin.H{"success": true})
}

func handleStop(c *gin.Context) {
	var req RoomRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	manager.RemovePlayer(req.RoomName)
	c.JSON(http.StatusOK, gin.H{"success": true})
}

func handleProgress(c *gin.Context) {
	roomName := c.Query("room_name")
	if roomName == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "room_name required"})
		return
	}
	
	player, err := manager.GetOrCreatePlayer(roomName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	
	posMs, durMs, state, song := player.GetProgress()
	c.JSON(http.StatusOK, ProgressResponse{
		PositionMs: posMs,
		DurationMs: durMs,
		State:      state,
		Song:       song,
	})
}

func handleHealth(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

func main() {
	// Load config from environment or config file
	config := &Config{
		LiveKitURL:    getEnv("LIVEKIT_URL", "ws://127.0.0.1:7880"),
		LiveKitAPIKey: getEnv("LIVEKIT_API_KEY", "rms_discord"),
		LiveKitSecret: getEnv("LIVEKIT_API_SECRET", "rmsdiscordsecretkey123456"),
	}
	
	manager = NewPlayerManager(config)
	
	gin.SetMode(gin.ReleaseMode)
	r := gin.Default()
	
	r.GET("/health", handleHealth)
	r.POST("/play", handlePlay)
	r.POST("/pause", handlePause)
	r.POST("/resume", handleResume)
	r.POST("/seek", handleSeek)
	r.POST("/stop", handleStop)
	r.GET("/progress", handleProgress)
	
	port := getEnv("PORT", "9100")
	log.Printf("Music service starting on port %s", port)
	
	if err := r.Run(":" + port); err != nil {
		log.Fatal(err)
	}
}

func getEnv(key, defaultVal string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return defaultVal
}

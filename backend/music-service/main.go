package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/go-gst/go-glib/glib"
	"github.com/go-gst/go-gst/gst"
	"github.com/go-gst/go-gst/gst/app"
	"github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"github.com/pion/webrtc/v3"
	"github.com/pion/webrtc/v3/pkg/media"
)

// Constants matching Ingress exactly
const (
	SampleRate    = 48000
	Channels      = 2
	OpusBitrate   = 256000
	OpusFrameSize = 20
	DefaultVolume = 0.5 // Default volume (0.0-1.0)
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
	Duration int    `json:"duration"`
	URL      string `json:"url"`
}

type Player struct {
	mu          sync.RWMutex
	room        *lksdk.Room
	track       *lksdk.LocalTrack
	roomName    string
	state       PlayState
	currentSong *SongInfo
	positionMs  int64
	durationMs  int64

	pipeline *gst.Pipeline
	loop     *glib.MainLoop

	ctx    context.Context
	cancel context.CancelFunc

	// Pause timeout: disconnect from room after 30s of pause
	pauseTimer *time.Timer
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
	CallbackURL   string // Python backend callback URL
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
	}

	pm.players[roomName] = p
	return p, nil
}

func (pm *PlayerManager) RemovePlayer(roomName string) {
	pm.mu.Lock()
	defer pm.mu.Unlock()
	delete(pm.players, roomName)
}

// notifySongEnded calls Python backend when a song finishes playing
func notifySongEnded(roomName string) {
	if manager == nil || manager.config.CallbackURL == "" {
		return
	}

	url := manager.config.CallbackURL + "/api/music/internal/song-ended"
	payload := map[string]string{"room_name": roomName}
	jsonData, err := json.Marshal(payload)
	if err != nil {
		log.Printf("Failed to marshal callback payload: %v", err)
		return
	}

	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Post(url, "application/json", bytes.NewBuffer(jsonData))
	if err != nil {
		log.Printf("Failed to notify song ended: %v", err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		log.Printf("Callback returned status: %d", resp.StatusCode)
	} else {
		log.Printf("Song ended callback sent for room: %s", roomName)
	}
}

func (p *Player) Connect() error {
	p.mu.Lock()
	if p.room != nil {
		p.mu.Unlock()
		return nil
	}
	p.mu.Unlock()

	cb := lksdk.NewRoomCallback()
	cb.OnDisconnected = func() {
		log.Printf("Disconnected from room %s", p.roomName)
		p.mu.Lock()
		p.room = nil
		p.track = nil
		// Cancel any running playback to clean up GStreamer resources
		if p.cancel != nil {
			p.cancel()
			p.cancel = nil
		}
		if p.loop != nil {
			p.loop.Quit()
			p.loop = nil
		}
		if p.pipeline != nil {
			p.pipeline.SetState(gst.StateNull)
			p.pipeline = nil
		}
		p.state = StateStopped
		p.mu.Unlock()
	}

	room := lksdk.NewRoom(cb)
	err := room.Join(manager.config.LiveKitURL, lksdk.ConnectInfo{
		APIKey:              manager.config.LiveKitAPIKey,
		APISecret:           manager.config.LiveKitSecret,
		RoomName:            p.roomName,
		ParticipantIdentity: fmt.Sprintf("music-bot-%s", p.roomName),
		ParticipantName:     "Music Bot",
	}, lksdk.WithAutoSubscribe(false))
	if err != nil {
		return fmt.Errorf("failed to join room: %w", err)
	}

	p.mu.Lock()
	p.room = room
	p.mu.Unlock()

	log.Printf("Connected to room %s", p.roomName)
	return nil
}

func (p *Player) Load(song *SongInfo) error {
	p.mu.Lock()
	p.state = StateLoading
	p.currentSong = song
	p.positionMs = 0
	p.durationMs = int64(song.Duration) * 1000
	p.mu.Unlock()

	return p.Connect()
}

func (p *Player) Play() error {
	p.mu.Lock()

	// Cancel pause timeout timer if any
	p.cancelPauseTimer()

	if p.state == StatePlaying {
		p.mu.Unlock()
		return nil
	}

	song := p.currentSong
	if song == nil {
		p.mu.Unlock()
		return fmt.Errorf("no song loaded")
	}

	if p.cancel != nil {
		p.cancel()
	}

	p.ctx, p.cancel = context.WithCancel(context.Background())
	p.state = StatePlaying
	startPos := p.positionMs
	p.mu.Unlock()

	// Ensure room connection
	if err := p.Connect(); err != nil {
		p.mu.Lock()
		p.state = StateIdle
		p.mu.Unlock()
		return fmt.Errorf("failed to connect: %w", err)
	}

	go p.playbackLoop(song, startPos)
	return nil
}

// cleanupPlayback handles resource cleanup on error or completion
func (p *Player) cleanupPlayback(pipeline *gst.Pipeline, room *lksdk.Room, pubSID string) {
	if pipeline != nil {
		pipeline.SetState(gst.StateNull)
	}
	if room != nil && pubSID != "" {
		room.LocalParticipant.UnpublishTrack(pubSID)
	}
	p.mu.Lock()
	p.pipeline = nil
	p.loop = nil
	p.mu.Unlock()
}

// playbackLoop - copied from Ingress implementation with timeout protection
func (p *Player) playbackLoop(song *SongInfo, startPosMs int64) {
	log.Printf("Starting playback: %s from %dms", song.Name, startPosMs)

	// Overall timeout: song duration + 60 seconds buffer for loading
	maxDuration := time.Duration(song.Duration)*time.Second + 60*time.Second
	if maxDuration < 2*time.Minute {
		maxDuration = 2 * time.Minute
	}
	overallTimeout := time.AfterFunc(maxDuration, func() {
		log.Printf("Playback timeout for %s, forcing cleanup", song.Name)
		p.mu.Lock()
		if p.cancel != nil {
			p.cancel()
		}
		if p.loop != nil {
			p.loop.Quit()
		}
		p.state = StateStopped
		p.mu.Unlock()
	})
	defer overallTimeout.Stop()

	// Build GStreamer pipeline exactly like Ingress
	// uridecodebin -> audioconvert -> audioresample -> capsfilter -> opusenc -> appsink
	pipeline, err := gst.NewPipeline("music-pipeline")
	if err != nil {
		log.Printf("Failed to create pipeline: %v", err)
		p.mu.Lock()
		p.state = StateIdle
		p.mu.Unlock()
		return
	}

	// Create elements - exactly like Ingress output.go
	uridecodebin, _ := gst.NewElement("uridecodebin")
	uridecodebin.Set("uri", song.URL)
	// Set buffer size and timeout for network streams
	uridecodebin.Set("buffer-size", 2*1024*1024) // 2MB buffer
	uridecodebin.Set("download", true)           // Enable download buffering

	audioconvert, _ := gst.NewElement("audioconvert")
	audioresample, _ := gst.NewElement("audioresample")

	// Capsfilter - exactly like Ingress
	capsfilter, _ := gst.NewElement("capsfilter")
	caps := gst.NewCapsFromString(fmt.Sprintf(
		"audio/x-raw,format=S16LE,layout=interleaved,rate=%d,channels=%d",
		SampleRate, Channels,
	))
	capsfilter.Set("caps", caps)

	// Opus encoder - optimized for music (not voice)
	opusenc, _ := gst.NewElement("opusenc")
	opusenc.Set("bitrate", OpusBitrate)
	opusenc.Set("frame-size", OpusFrameSize)
	opusenc.Set("audio-type", 2049)  // generic (music), not voice
	opusenc.Set("dtx", false)        // disable DTX for music quality

	// Appsink - exactly like Ingress
	appsinkElem, _ := gst.NewElement("appsink")
	appsink := app.SinkFromElement(appsinkElem)
	appsink.SetProperty("emit-signals", true)
	appsink.SetProperty("sync", true)

	// Add all elements to pipeline
	pipeline.AddMany(uridecodebin, audioconvert, audioresample, capsfilter, opusenc, appsinkElem)

	// Link static elements
	gst.ElementLinkMany(audioconvert, audioresample, capsfilter, opusenc, appsinkElem)

	// Handle dynamic pad from uridecodebin
	uridecodebin.Connect("pad-added", func(self *gst.Element, pad *gst.Pad) {
		sinkPad := audioconvert.GetStaticPad("sink")
		if sinkPad.IsLinked() {
			return
		}
		padCaps := pad.GetCurrentCaps()
		if padCaps == nil {
			return
		}
		structure := padCaps.GetStructureAt(0)
		if structure == nil {
			return
		}
		name := structure.Name()
		if len(name) >= 5 && name[:5] == "audio" {
			pad.Link(sinkPad)
		}
	})

	p.mu.Lock()
	p.pipeline = pipeline
	p.mu.Unlock()

	// Create LocalSampleTrack - exactly like Ingress lksdk_output.go
	track, err := lksdk.NewLocalSampleTrack(webrtc.RTPCodecCapability{
		MimeType:  webrtc.MimeTypeOpus,
		ClockRate: SampleRate,
		Channels:  Channels,
	})
	if err != nil {
		log.Printf("Failed to create track: %v", err)
		p.cleanupPlayback(pipeline, nil, "")
		p.mu.Lock()
		p.state = StateIdle
		p.mu.Unlock()
		return
	}

	p.mu.Lock()
	if p.room == nil {
		p.mu.Unlock()
		log.Printf("Room not connected")
		p.cleanupPlayback(pipeline, nil, "")
		p.mu.Lock()
		p.state = StateIdle
		p.mu.Unlock()
		return
	}
	room := p.room
	p.track = track
	p.mu.Unlock()

	// Publish track with music-optimized settings
	pub, err := room.LocalParticipant.PublishTrack(track, &lksdk.TrackPublicationOptions{
		Name:       "music",
		Source:     livekit.TrackSource_MICROPHONE,
		DisableDTX: true,  // critical for music quality
		Stereo:     true,  // enable stereo
	})
	if err != nil {
		log.Printf("Failed to publish track: %v", err)
		p.cleanupPlayback(pipeline, nil, "")
		p.mu.Lock()
		p.state = StateIdle
		p.mu.Unlock()
		return
	}
	log.Printf("Published track: %s", pub.SID())
	pubSID := pub.SID()

	// Handle samples from appsink - exactly like Ingress output.go handleSample
	appsink.SetCallbacks(&app.SinkCallbacks{
		EOSFunc: func(sink *app.Sink) {
			log.Printf("EOS received")
		},
		NewSampleFunc: func(sink *app.Sink) gst.FlowReturn {
			sample := sink.PullSample()
			if sample == nil {
				return gst.FlowEOS
			}

			buffer := sample.GetBuffer()
			if buffer == nil {
				return gst.FlowError
			}

			duration := time.Duration(buffer.Duration())

			// WriteSample - exactly like Ingress
			err := track.WriteSample(media.Sample{
				Data:     buffer.Bytes(),
				Duration: duration,
			}, nil)
			if err != nil {
				log.Printf("WriteSample error: %v", err)
			}

			// Update position
			p.mu.Lock()
			p.positionMs += int64(duration / time.Millisecond)
			if p.positionMs > p.durationMs {
				p.positionMs = p.durationMs
			}
			p.mu.Unlock()

			return gst.FlowOK
		},
	})

	// Seek if needed
	if startPosMs > 0 {
		pipeline.SetState(gst.StatePaused)
		pipeline.Bin.Element.GetState(gst.StateNull, gst.ClockTimeNone)
		pipeline.Bin.Element.SeekSimple(int64(startPosMs)*int64(time.Millisecond), gst.FormatTime, gst.SeekFlagFlush|gst.SeekFlagKeyUnit)
	}

	// Start pipeline with loading timeout
	loadingTimeout := time.AfterFunc(30*time.Second, func() {
		log.Printf("Loading timeout for %s", song.Name)
		p.mu.Lock()
		if p.state == StateLoading || p.state == StatePlaying {
			if p.cancel != nil {
				p.cancel()
			}
			if p.loop != nil {
				p.loop.Quit()
			}
			p.state = StateStopped
		}
		p.mu.Unlock()
	})

	pipeline.SetState(gst.StatePlaying)

	// Main loop
	loop := glib.NewMainLoop(glib.MainContextDefault(), false)
	p.mu.Lock()
	p.loop = loop
	p.mu.Unlock()

	bus := pipeline.GetPipelineBus()
	bus.AddWatch(func(msg *gst.Message) bool {
		switch msg.Type() {
		case gst.MessageStateChanged:
			// Cancel loading timeout once we start playing
			_, newState := msg.ParseStateChanged()
			if newState == gst.StatePlaying {
				loadingTimeout.Stop()
			}
		case gst.MessageEOS:
			log.Printf("Playback finished: %s", song.Name)
			p.mu.Lock()
			wasPlaying := p.state == StatePlaying
			if wasPlaying {
				p.state = StateStopped
				p.positionMs = p.durationMs
			}
			roomName := p.roomName
			p.mu.Unlock()
			// Notify Python backend to play next song
			if wasPlaying {
				go notifySongEnded(roomName)
			}
			loop.Quit()
			return false
		case gst.MessageError:
			err := msg.ParseError()
			log.Printf("Pipeline error: %v", err)
			p.mu.Lock()
			p.state = StateStopped
			p.mu.Unlock()
			loop.Quit()
			return false
		}
		return true
	})

	go func() {
		<-p.ctx.Done()
		loop.Quit()
	}()

	loop.Run()

	// Cleanup
	loadingTimeout.Stop()
	p.cleanupPlayback(pipeline, room, pubSID)
}

const PauseTimeoutSeconds = 30

func (p *Player) Pause() {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.state != StatePlaying {
		return
	}

	if p.cancel != nil {
		p.cancel()
		p.cancel = nil
	}

	p.state = StatePaused
	log.Printf("Paused at %dms", p.positionMs)

	// Start pause timeout timer - disconnect from room after 30s
	p.cancelPauseTimer()
	p.pauseTimer = time.AfterFunc(PauseTimeoutSeconds*time.Second, func() {
		p.mu.Lock()
		if p.state != StatePaused {
			p.mu.Unlock()
			return
		}
		room := p.room
		p.room = nil
		p.track = nil
		p.pauseTimer = nil
		p.mu.Unlock()

		if room != nil {
			room.Disconnect()
			log.Printf("Disconnected from room %s due to pause timeout", p.roomName)
		}
	})
}

func (p *Player) cancelPauseTimer() {
	if p.pauseTimer != nil {
		p.pauseTimer.Stop()
		p.pauseTimer = nil
	}
}

func (p *Player) Resume() error {
	p.mu.Lock()

	// Cancel pause timeout timer
	p.cancelPauseTimer()

	if p.state != StatePaused {
		log.Printf("Resume: not paused, state=%s", p.state)
		p.mu.Unlock()
		return fmt.Errorf("not paused, state=%s", p.state)
	}

	song := p.currentSong
	if song == nil {
		log.Printf("Resume: no song loaded")
		p.mu.Unlock()
		return fmt.Errorf("no song loaded")
	}

	if p.cancel != nil {
		p.cancel()
	}

	p.ctx, p.cancel = context.WithCancel(context.Background())
	p.state = StatePlaying
	startPos := p.positionMs
	p.mu.Unlock()

	// Ensure room connection before resuming (may need to reconnect after timeout)
	if err := p.Connect(); err != nil {
		log.Printf("Resume: failed to connect: %v", err)
		p.mu.Lock()
		p.state = StatePaused
		p.mu.Unlock()
		return fmt.Errorf("failed to connect: %w", err)
	}

	log.Printf("Resuming from %dms", startPos)
	go p.playbackLoop(song, startPos)
	return nil
}

func (p *Player) Seek(positionMs int64) {
	p.mu.Lock()
	wasPlaying := p.state == StatePlaying
	p.positionMs = positionMs

	if p.cancel != nil {
		p.cancel()
	}

	song := p.currentSong
	p.mu.Unlock()

	if wasPlaying && song != nil {
		p.mu.Lock()
		p.ctx, p.cancel = context.WithCancel(context.Background())
		p.state = StatePlaying
		p.mu.Unlock()
		go p.playbackLoop(song, positionMs)
	}
}

func (p *Player) Stop() {
	p.mu.Lock()

	// Cancel pause timeout timer
	p.cancelPauseTimer()

	if p.cancel != nil {
		p.cancel()
		p.cancel = nil
	}

	if p.loop != nil {
		p.loop.Quit()
		p.loop = nil
	}

	if p.pipeline != nil {
		p.pipeline.SetState(gst.StateNull)
		p.pipeline = nil
	}

	p.state = StateStopped
	room := p.room
	p.room = nil
	p.track = nil
	p.mu.Unlock()

	if room != nil {
		room.Disconnect()
	}
}

func (p *Player) GetProgress() (positionMs int64, durationMs int64, state PlayState, song *SongInfo) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.positionMs, p.durationMs, p.state, p.currentSong
}

// HTTP Handlers
type PlayRequest struct {
	RoomName string    `json:"room_name"`
	Song     *SongInfo `json:"song"`
}

type RoomRequest struct {
	RoomName   string `json:"room_name"`
	PositionMs int64  `json:"position_ms,omitempty"`
}

func handlePlay(c *gin.Context) {
	var req PlayRequest
	if err := c.BindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	player, err := manager.GetOrCreatePlayer(req.RoomName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	if err := player.Load(req.Song); err != nil {
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
	if err := c.BindJSON(&req); err != nil {
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
	if err := c.BindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	player, err := manager.GetOrCreatePlayer(req.RoomName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	if err := player.Resume(); err != nil {
		c.JSON(http.StatusOK, gin.H{"success": false, "error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": true})
}

func handleSeek(c *gin.Context) {
	var req RoomRequest
	if err := c.BindJSON(&req); err != nil {
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
	if err := c.BindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	player, err := manager.GetOrCreatePlayer(req.RoomName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	player.Stop()
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

	pos, dur, state, song := player.GetProgress()
	c.JSON(http.StatusOK, gin.H{
		"position_ms": pos,
		"duration_ms": dur,
		"state":       state,
		"song":        song,
	})
}

func handleHealth(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

func main() {
	// Initialize GStreamer
	gst.Init(nil)

	config := &Config{
		LiveKitURL:    os.Getenv("LIVEKIT_URL"),
		LiveKitAPIKey: os.Getenv("LIVEKIT_API_KEY"),
		LiveKitSecret: os.Getenv("LIVEKIT_API_SECRET"),
		CallbackURL:   os.Getenv("CALLBACK_URL"),
	}

	if config.LiveKitURL == "" {
		config.LiveKitURL = "ws://127.0.0.1:7880"
	}
	if config.LiveKitAPIKey == "" {
		config.LiveKitAPIKey = "devkey"
	}
	if config.LiveKitSecret == "" {
		config.LiveKitSecret = "secret"
	}
	if config.CallbackURL == "" {
		config.CallbackURL = "http://127.0.0.1:8000"
	}

	manager = NewPlayerManager(config)

	port := os.Getenv("PORT")
	if port == "" {
		port = "9100"
	}

	gin.SetMode(gin.ReleaseMode)
	r := gin.Default()

	r.GET("/health", handleHealth)
	r.POST("/play", handlePlay)
	r.POST("/pause", handlePause)
	r.POST("/resume", handleResume)
	r.POST("/seek", handleSeek)
	r.POST("/stop", handleStop)
	r.GET("/progress", handleProgress)

	log.Printf("Music service starting on port %s", port)
	if err := r.Run(":" + port); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}

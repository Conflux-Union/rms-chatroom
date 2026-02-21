package lk

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/livekit/protocol/auth"
	"github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"

	"github.com/RMS-Server/rms-discord-go/internal/config"
)

// Client wraps LiveKit server SDK operations.
type Client struct {
	host      string
	apiKey    string
	apiSecret string
	roomSvc   *lksdk.RoomServiceClient
}

// New creates a LiveKit client from config.
func New(cfg *config.Config) *Client {
	// RoomServiceClient needs http(s) URL
	httpURL := cfg.LivekitInternalHost
	if httpURL == "" {
		httpURL = cfg.LivekitHost
	}
	httpURL = strings.Replace(httpURL, "ws://", "http://", 1)
	httpURL = strings.Replace(httpURL, "wss://", "https://", 1)

	return &Client{
		host:      cfg.LivekitHost,
		apiKey:    cfg.LivekitAPIKey,
		apiSecret: cfg.LivekitAPISecret,
		roomSvc:   lksdk.NewRoomServiceClient(httpURL, cfg.LivekitAPIKey, cfg.LivekitAPISecret),
	}
}

// Host returns the public LiveKit WebSocket URL for clients.
func (c *Client) Host() string { return c.host }

// CreateToken generates a JWT access token for a user joining a room.
func (c *Client) CreateToken(identity, name, room string) (string, error) {
	at := auth.NewAccessToken(c.apiKey, c.apiSecret)
	grant := &auth.VideoGrant{
		RoomJoin: true,
		Room:     room,
		CanPublish: boolPtr(true),
		CanPublishSources: []string{
			"camera", "microphone", "screen_share", "screen_share_audio",
		},
		CanSubscribe: boolPtr(true),
	}
	at.SetVideoGrant(grant).
		SetIdentity(identity).
		SetName(name).
		SetValidFor(time.Hour)

	return at.ToJWT()
}

// ListParticipants returns all participants in a room.
func (c *Client) ListParticipants(ctx context.Context, room string) ([]*livekit.ParticipantInfo, error) {
	resp, err := c.roomSvc.ListParticipants(ctx, &livekit.ListParticipantsRequest{Room: room})
	if err != nil {
		return nil, err
	}
	return resp.Participants, nil
}

// GetParticipant returns a single participant.
func (c *Client) GetParticipant(ctx context.Context, room, identity string) (*livekit.ParticipantInfo, error) {
	return c.roomSvc.GetParticipant(ctx, &livekit.RoomParticipantIdentity{
		Room:     room,
		Identity: identity,
	})
}

// RemoveParticipant kicks a participant from a room.
func (c *Client) RemoveParticipant(ctx context.Context, room, identity string) error {
	_, err := c.roomSvc.RemoveParticipant(ctx, &livekit.RoomParticipantIdentity{
		Room:     room,
		Identity: identity,
	})
	return err
}

// MuteMicrophone mutes or unmutes a participant's microphone track.
// Returns true if a mic track was found and mutated.
func (c *Client) MuteMicrophone(ctx context.Context, room, identity string, muted bool) (bool, error) {
	p, err := c.roomSvc.GetParticipant(ctx, &livekit.RoomParticipantIdentity{
		Room:     room,
		Identity: identity,
	})
	if err != nil {
		return false, err
	}
	for _, t := range p.Tracks {
		if t.Source == livekit.TrackSource_MICROPHONE {
			_, err := c.roomSvc.MutePublishedTrack(ctx, &livekit.MuteRoomTrackRequest{
				Room:     room,
				Identity: identity,
				TrackSid: t.Sid,
				Muted:    muted,
			})
			return err == nil, err
		}
	}
	return false, nil
}

// ParticipantIDs returns a set of identity strings for all participants in a room.
func (c *Client) ParticipantIDs(ctx context.Context, room string) (map[string]struct{}, error) {
	participants, err := c.ListParticipants(ctx, room)
	if err != nil {
		return nil, err
	}
	ids := make(map[string]struct{}, len(participants))
	for _, p := range participants {
		ids[p.Identity] = struct{}{}
	}
	return ids, nil
}

// HasRealUsers checks if a room has non-bot participants.
func (c *Client) HasRealUsers(ctx context.Context, room string) bool {
	participants, err := c.ListParticipants(ctx, room)
	if err != nil {
		return false
	}
	for _, p := range participants {
		if p.Identity != "MusicBot" && !strings.HasPrefix(p.Identity, "music-bot-") {
			return true
		}
	}
	return false
}

// RoomName returns the canonical room name for a voice channel.
func RoomName(channelID string) string {
	return fmt.Sprintf("voice_%s", channelID)
}

func boolPtr(b bool) *bool { return &b }

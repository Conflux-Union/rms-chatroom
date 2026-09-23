// Package readstate is the single server-side computation point for per-user
// channel read state: unread counts and mention flags are derived from
// read_positions + messages + message_mentions, never reported by clients.
package readstate

import (
	"database/sql"
	"errors"
)

// ChannelState is the derived read state of one (user, channel) pair.
type ChannelState struct {
	LastReadMessageID    int64
	UnreadCount          int
	HasMention           bool
	LastMentionMessageID *int64
	// Exists reports whether a read_positions row exists for the pair. A
	// missing row means the user never read the channel; callers that need a
	// count must seed a baseline first (SeedIfMissing).
	Exists bool
}

// GetChannelState loads the stored read position and derives the unread count
// and mention flag for one channel.
func GetChannelState(db *sql.DB, userID, channelID int64) (ChannelState, error) {
	var lastRead int64
	err := db.QueryRow(
		"SELECT last_read_message_id FROM read_positions WHERE user_id = ? AND channel_id = ?",
		userID, channelID,
	).Scan(&lastRead)
	if errors.Is(err, sql.ErrNoRows) {
		return ChannelState{LastReadMessageID: 0}, nil
	}
	if err != nil {
		return ChannelState{}, err
	}
	return DeriveFromPosition(db, userID, channelID, lastRead)
}

// DeriveFromPosition computes the unread count and mention flag for a known
// read position, skipping the read_positions lookup the caller already did.
// The count excludes the user's own messages and deleted messages; a mention
// is any mention of the user on a message newer than the read position.
func DeriveFromPosition(db *sql.DB, userID, channelID, lastReadID int64) (ChannelState, error) {
	st := ChannelState{LastReadMessageID: lastReadID, Exists: true}

	if err := db.QueryRow(
		`SELECT COUNT(*) FROM messages
		 WHERE channel_id = ? AND id > ? AND user_id != ? AND is_deleted = FALSE`,
		channelID, lastReadID, userID,
	).Scan(&st.UnreadCount); err != nil {
		return ChannelState{}, err
	}

	var lastMention sql.NullInt64
	if err := db.QueryRow(
		`SELECT MAX(m.id) FROM message_mentions mm
		 JOIN messages m ON m.id = mm.message_id
		 WHERE mm.user_id = ? AND m.channel_id = ? AND m.id > ? AND m.is_deleted = FALSE`,
		userID, channelID, lastReadID,
	).Scan(&lastMention); err != nil {
		return ChannelState{}, err
	}
	if lastMention.Valid {
		st.HasMention = true
		st.LastMentionMessageID = &lastMention.Int64
	}
	return st, nil
}

// SeedIfMissing creates a read_positions row at the given baseline when the
// user has never read the channel. Used on first live activity so a fresh
// install starts counting from now instead of the channel's whole history.
func SeedIfMissing(db *sql.DB, userID, channelID, baselineID int64) error {
	_, err := db.Exec(
		`INSERT IGNORE INTO read_positions (user_id, channel_id, last_read_message_id)
		 VALUES (?, ?, ?)`,
		userID, channelID, baselineID,
	)
	return err
}

// AdvanceReadPosition moves the read cursor forward (never backward) and
// refreshes the derived mention columns to match the resulting position. The
// returned state reflects the stored position, which may be higher than the
// request when another device was already ahead.
func AdvanceReadPosition(db *sql.DB, userID, channelID, lastReadID int64) (ChannelState, error) {
	if _, err := db.Exec(
		`INSERT INTO read_positions (user_id, channel_id, last_read_message_id)
		 VALUES (?, ?, ?)
		 ON DUPLICATE KEY UPDATE
		   last_read_message_id = GREATEST(last_read_message_id, VALUES(last_read_message_id)),
		   updated_at = UTC_TIMESTAMP()`,
		userID, channelID, lastReadID,
	); err != nil {
		return ChannelState{}, err
	}

	var stored int64
	err := db.QueryRow(
		"SELECT last_read_message_id FROM read_positions WHERE user_id = ? AND channel_id = ?",
		userID, channelID,
	).Scan(&stored)
	if err != nil {
		return ChannelState{}, err
	}

	st, err := DeriveFromPosition(db, userID, channelID, stored)
	if err != nil {
		return ChannelState{}, err
	}

	var lastMention sql.NullInt64
	if st.LastMentionMessageID != nil {
		lastMention = sql.NullInt64{Int64: *st.LastMentionMessageID, Valid: true}
	}
	if _, err := db.Exec(
		`UPDATE read_positions SET has_mention = ?, last_mention_message_id = ?, updated_at = UTC_TIMESTAMP()
		 WHERE user_id = ? AND channel_id = ?`,
		st.HasMention, lastMention, userID, channelID,
	); err != nil {
		return ChannelState{}, err
	}
	return st, nil
}

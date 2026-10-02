package ws

import (
	"database/sql"
	"log"

	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/readstate"
)

// PushUnreadAfterMessage recomputes the server-derived unread state for every
// online user who can access the channel and pushes it over /ws/global.
// Called after a new message lands; a user without a read row gets one seeded
// just below the new message so it counts as exactly one unread.
func PushUnreadAfterMessage(db *sql.DB, rule permission.PermRule, channelID, messageID, senderUserID int64) {
	pushUnreadToOnline(db, rule, channelID, senderUserID, func(userID int64) (readstate.ChannelState, error) {
		// First activity in a channel this user never opened: baseline just
		// below the new message so it counts as exactly one unread.
		if err := readstate.SeedIfMissing(db, userID, channelID, messageID-1); err != nil {
			return readstate.ChannelState{}, err
		}
		return readstate.GetChannelState(db, userID, channelID)
	})
}

// PushUnreadAfterMutation recomputes and pushes for online users who already
// have read rows. Used after mutations that change existing counts (e.g. a
// soft delete) without introducing a message to anchor a first-activity
// baseline — users without a row never had a badge and stay untouched.
func PushUnreadAfterMutation(db *sql.DB, rule permission.PermRule, channelID int64) {
	pushUnreadToOnline(db, rule, channelID, -1, nil)
}

// pushUnreadToOnline walks the permitted online users, optionally seeds a
// baseline for rowless ones, and pushes their recomputed state.
func pushUnreadToOnline(db *sql.DB, rule permission.PermRule, channelID, skipUserID int64, seedBaseline func(userID int64) (readstate.ChannelState, error)) {
	GlobalStateManager.ForEachUser(func(user *permission.UserInfo) bool {
		return permission.CanAccess(user, rule)
	}, func(userID int64) {
		if userID == skipUserID {
			// The sender's own message is never unread for the sender.
			return
		}

		state, err := readstate.GetChannelState(db, userID, channelID)
		if err != nil {
			log.Printf("unread push: state query failed for user %d channel %d: %v", userID, channelID, err)
			return
		}
		if !state.Exists {
			if seedBaseline == nil {
				return
			}
			if state, err = seedBaseline(userID); err != nil {
				log.Printf("unread push: seed failed for user %d channel %d: %v", userID, channelID, err)
				return
			}
		}

		GlobalStateManager.SendToUser(userID, map[string]interface{}{
			"type":                     "unread_update",
			"channel_id":              channelID,
			"unread_count":            state.UnreadCount,
			"has_mention":             state.HasMention,
			"last_mention_message_id": state.LastMentionMessageID,
		})
	})
}

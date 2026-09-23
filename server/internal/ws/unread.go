package ws

import (
	"database/sql"
	"log"

	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/readstate"
)

// PushUnreadUpdates recomputes the server-derived unread state for every
// online user who can access the channel and pushes it over /ws/global.
// Called after a new message is broadcast; counts are absolute values, so
// ordering against the message broadcast on the separate /ws/chat socket is
// irrelevant.
func PushUnreadUpdates(db *sql.DB, rule permission.PermRule, channelID, messageID, senderUserID int64) {
	GlobalStateManager.ForEachUser(func(user *permission.UserInfo) bool {
		return permission.CanAccess(user, rule)
	}, func(userID int64) {
		if userID == senderUserID {
			// The sender's own message is never unread for the sender.
			return
		}

		state, err := readstate.GetChannelState(db, userID, channelID)
		if err != nil {
			log.Printf("unread push: state query failed for user %d channel %d: %v", userID, channelID, err)
			return
		}
		if !state.Exists {
			// First activity in a channel this user never opened: baseline
			// just below the new message so it counts as exactly one unread.
			if err := readstate.SeedIfMissing(db, userID, channelID, messageID-1); err != nil {
				log.Printf("unread push: seed failed for user %d channel %d: %v", userID, channelID, err)
				return
			}
			if state, err = readstate.GetChannelState(db, userID, channelID); err != nil {
				log.Printf("unread push: state query failed for user %d channel %d: %v", userID, channelID, err)
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

package lk

import (
	"net/http"

	"github.com/labstack/echo/v4"
	"github.com/livekit/protocol/auth"
	"github.com/livekit/protocol/webhook"
)

// WebhookHandler returns an Echo handler that verifies and processes LiveKit webhooks.
// onEvent is called for each verified event with the event type string.
func WebhookHandler(apiKey, apiSecret string, onEvent func(eventType string)) echo.HandlerFunc {
	provider := auth.NewSimpleKeyProvider(apiKey, apiSecret)

	return func(c echo.Context) error {
		event, err := webhook.ReceiveWebhookEvent(c.Request(), provider)
		if err != nil {
			return c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid webhook signature"})
		}

		switch event.GetEvent() {
		case "participant_joined", "participant_left", "track_published", "track_unpublished":
			if onEvent != nil {
				onEvent(event.GetEvent())
			}
		}

		return c.JSON(http.StatusOK, map[string]string{"status": "ok"})
	}
}

package sso

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/RMS-Server/rms-discord-go/internal/permission"
)

type avatarEntry struct {
	url       string
	fetchedAt time.Time
}

// Client fetches user info and avatars from RMSSSO.
type Client struct {
	baseURL     string
	httpClient  *http.Client
	avatarCache sync.Map // map[int]*avatarEntry
	avatarTTL   time.Duration
}

// NewClient creates an SSO client with the given base URL.
func NewClient(baseURL string) *Client {
	return &Client{
		baseURL:    baseURL,
		httpClient: &http.Client{Timeout: 10 * time.Second},
		avatarTTL:  5 * time.Minute,
	}
}

// ssoAccountInfoResponse is the JSON envelope from the account_info endpoint.
type ssoAccountInfoResponse struct {
	Success bool `json:"success"`
	User    struct {
		ID              int    `json:"id"`
		Username        string `json:"username"`
		Nickname        string `json:"nickname"`
		Email           string `json:"email"`
		PermissionLevel int    `json:"permission_level"`
		AvatarURL       string `json:"avatar_url"`
		Group           struct {
			ID    int    `json:"id"`
			Name  string `json:"name"`
			Level int    `json:"level"`
		} `json:"group"`
	} `json:"user"`
}

// GetUserByID fetches user info from SSO by user ID, including group level.
func (c *Client) GetUserByID(userID int) (*permission.UserInfo, error) {
	url := fmt.Sprintf("%s/api/account_info?uid=%d", c.baseURL, userID)
	resp, err := c.httpClient.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("account_info returned status %d", resp.StatusCode)
	}

	var result ssoAccountInfoResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}
	if !result.Success || result.User.ID == 0 {
		return nil, fmt.Errorf("user %d not found", userID)
	}

	u := &permission.UserInfo{
		ID:              result.User.ID,
		Username:        result.User.Username,
		Nickname:        result.User.Nickname,
		Email:           result.User.Email,
		PermissionLevel: result.User.PermissionLevel,
		GroupLevel:      result.User.Group.Level,
		AvatarURL:       result.User.AvatarURL,
	}

	// Cache avatar URL
	if u.AvatarURL != "" {
		c.avatarCache.Store(userID, &avatarEntry{
			url:       u.AvatarURL,
			fetchedAt: time.Now(),
		})
	}

	return u, nil
}

// GetAvatarURL fetches the avatar URL for a user, with 5-minute caching.
func (c *Client) GetAvatarURL(userID int) (string, error) {
	if v, ok := c.avatarCache.Load(userID); ok {
		entry := v.(*avatarEntry)
		if time.Since(entry.fetchedAt) < c.avatarTTL {
			return entry.url, nil
		}
	}

	url := fmt.Sprintf("%s/api/account_info?uid=%d", c.baseURL, userID)
	resp, err := c.httpClient.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("account_info returned status %d", resp.StatusCode)
	}

	var result ssoAccountInfoResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return "", err
	}
	if !result.Success || result.User.AvatarURL == "" {
		return "", fmt.Errorf("no avatar for user %d", userID)
	}

	c.avatarCache.Store(userID, &avatarEntry{
		url:       result.User.AvatarURL,
		fetchedAt: time.Now(),
	})
	return result.User.AvatarURL, nil
}

// GetAvatarURLsBatch fetches avatar URLs for multiple users in parallel.
func (c *Client) GetAvatarURLsBatch(userIDs []int) map[int]string {
	result := make(map[int]string, len(userIDs))
	var mu sync.Mutex
	var wg sync.WaitGroup

	for _, uid := range userIDs {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			avatarURL, err := c.GetAvatarURL(id)
			if err != nil || avatarURL == "" {
				return
			}
			mu.Lock()
			result[id] = avatarURL
			mu.Unlock()
		}(uid)
	}

	wg.Wait()
	return result
}

// GetLoginURL generates the SSO login redirect URL.
func (c *Client) GetLoginURL(redirectURL string) string {
	return fmt.Sprintf("%s/?redirect_url=%s", c.baseURL, redirectURL)
}

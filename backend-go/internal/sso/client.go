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

// Client verifies tokens against RMSSSO and caches avatar URLs.
type Client struct {
	baseURL        string
	verifyEndpoint string
	verifyClient   *http.Client
	avatarClient   *http.Client
	avatarCache    sync.Map // map[int]*avatarEntry
	avatarTTL      time.Duration
}

// NewClient creates an SSO client with the given base URL and verify endpoint.
func NewClient(baseURL, verifyEndpoint string) *Client {
	return &Client{
		baseURL:        baseURL,
		verifyEndpoint: verifyEndpoint,
		verifyClient:   &http.Client{Timeout: 10 * time.Second},
		avatarClient:   &http.Client{Timeout: 5 * time.Second},
		avatarTTL:      5 * time.Minute,
	}
}

// ssoVerifyResponse is the JSON envelope from the SSO verify endpoint.
type ssoVerifyResponse struct {
	Success bool                 `json:"success"`
	User    *permission.UserInfo `json:"user"`
}

// VerifyToken validates a Bearer token against RMSSSO and returns user info.
func (c *Client) VerifyToken(token string) (*permission.UserInfo, error) {
	url := c.baseURL + c.verifyEndpoint
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)

	resp, err := c.verifyClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("sso returned status %d", resp.StatusCode)
	}

	var result ssoVerifyResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}
	if !result.Success || result.User == nil {
		return nil, fmt.Errorf("sso verify failed")
	}

	// Cache avatar URL from verify response
	if result.User.ID != 0 && result.User.AvatarURL != "" {
		c.avatarCache.Store(result.User.ID, &avatarEntry{
			url:       result.User.AvatarURL,
			fetchedAt: time.Now(),
		})
	}

	return result.User, nil
}

// ssoAccountInfoResponse is the JSON envelope from the account_info endpoint.
type ssoAccountInfoResponse struct {
	Success bool `json:"success"`
	User    struct {
		AvatarURL string `json:"avatar_url"`
	} `json:"user"`
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
	resp, err := c.avatarClient.Get(url)
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

// GetUserByID fetches basic user info from SSO by user ID (for token refresh).
func (c *Client) GetUserByID(userID int) (*permission.UserInfo, error) {
	url := fmt.Sprintf("%s/api/account_info?uid=%d", c.baseURL, userID)
	resp, err := c.verifyClient.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("account_info returned status %d", resp.StatusCode)
	}

	var result ssoVerifyResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}
	if !result.Success || result.User == nil {
		return nil, fmt.Errorf("user %d not found", userID)
	}
	return result.User, nil
}

// GetLoginURL generates the SSO login redirect URL.
func (c *Client) GetLoginURL(redirectURL string) string {
	return fmt.Sprintf("%s/?redirect_url=%s", c.baseURL, redirectURL)
}

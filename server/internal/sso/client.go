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

// lookupEntry caches an account_info lookup result. A miss (user == nil) is
// cached too, so unbound QQ senders don't hammer SSO on every forwarded
// message.
type lookupEntry struct {
	user      *permission.UserInfo
	fetchedAt time.Time
}

// Client fetches user info and avatars from RMSSSO.
type Client struct {
	baseURL     string
	httpClient  *http.Client
	avatarCache sync.Map // map[int]*avatarEntry
	// lookupCache is keyed by the account_info query string ("email=x", "username=y").
	lookupCache sync.Map // map[string]*lookupEntry
	avatarTTL   time.Duration
	lookupTTL   time.Duration
}

// NewClient creates an SSO client with the given base URL.
func NewClient(baseURL string) *Client {
	return &Client{
		baseURL:    baseURL,
		httpClient: &http.Client{Timeout: 10 * time.Second},
		avatarTTL:  5 * time.Minute,
		lookupTTL:  10 * time.Minute,
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
		Group           *struct {
			ID    int    `json:"id"`
			Name  string `json:"name"`
			Level int    `json:"level"`
		} `json:"group"`
	} `json:"user"`
}

// accountInfoUser converts a decoded account_info user into a UserInfo,
// recording whether the response actually carried a group.
func accountInfoUser(resp *ssoAccountInfoResponse) *permission.UserInfo {
	u := &permission.UserInfo{
		ID:              resp.User.ID,
		Username:        resp.User.Username,
		Nickname:        resp.User.Nickname,
		Email:           resp.User.Email,
		PermissionLevel: resp.User.PermissionLevel,
		AvatarURL:       resp.User.AvatarURL,
	}
	if resp.User.Group != nil {
		u.GroupLevel = resp.User.Group.Level
		u.GroupLevelPresent = true
	}
	return u
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

	u := accountInfoUser(&result)

	// Cache avatar URL
	if u.AvatarURL != "" {
		c.avatarCache.Store(userID, &avatarEntry{
			url:       u.AvatarURL,
			fetchedAt: time.Now(),
		})
	}

	return u, nil
}

// GetUserByIDCached fetches user info from SSO by user ID with lookupTTL
// caching (positive and negative). Used for the forward bot's proxy account,
// which is looked up on every unmatched forwarded message.
func (c *Client) GetUserByIDCached(userID int) (*permission.UserInfo, error) {
	return c.lookupCached(fmt.Sprintf("uid=%d", userID))
}

// GetUserByEmail fetches user info from SSO by email, with caching (including
// negative results). Requires the SSO account_info endpoint to support ?email=.
func (c *Client) GetUserByEmail(email string) (*permission.UserInfo, error) {
	return c.lookupCached("email=" + email)
}

// GetUserByUsername fetches user info from SSO by username, with caching
// (including negative results). Requires the SSO account_info endpoint to
// support ?username=.
func (c *Client) GetUserByUsername(username string) (*permission.UserInfo, error) {
	return c.lookupCached("username=" + username)
}

// lookupCached returns a cached account_info result or fetches it. Negative
// results are cached for lookupTTL as well: unbound senders would otherwise
// trigger one SSO request per forwarded message.
func (c *Client) lookupCached(query string) (*permission.UserInfo, error) {
	if v, ok := c.lookupCache.Load(query); ok {
		entry := v.(*lookupEntry)
		if time.Since(entry.fetchedAt) < c.lookupTTL {
			if entry.user == nil {
				return nil, fmt.Errorf("user not found (%s)", query)
			}
			return entry.user, nil
		}
	}

	user, err := c.fetchAccountInfo(query)
	if err != nil {
		c.lookupCache.Store(query, &lookupEntry{user: nil, fetchedAt: time.Now()})
		return nil, err
	}
	c.lookupCache.Store(query, &lookupEntry{user: user, fetchedAt: time.Now()})
	return user, nil
}

// fetchAccountInfo calls the account_info endpoint with a raw query string
// (e.g. "email=a@b.com") and decodes the response.
func (c *Client) fetchAccountInfo(query string) (*permission.UserInfo, error) {
	url := fmt.Sprintf("%s/api/account_info?%s", c.baseURL, query)
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
		return nil, fmt.Errorf("user not found (%s)", query)
	}

	u := accountInfoUser(&result)

	// Warm the avatar cache so message broadcasts don't refetch.
	if u.AvatarURL != "" {
		c.avatarCache.Store(u.ID, &avatarEntry{
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

package permission

// UserInfo represents an authenticated user.
type UserInfo struct {
	ID              int    `json:"id"`
	Username        string `json:"username"`
	Nickname        string `json:"nickname"`
	Email           string `json:"email"`
	PermissionLevel int    `json:"permission_level"`
	GroupLevel      int    `json:"group_level"`
	AvatarURL       string `json:"avatar_url"`
}

// CanAccess checks if user meets the minimum group level requirement.
func CanAccess(u *UserInfo, minLevel int) bool {
	return u.GroupLevel >= minLevel
}

// CanSpeak checks if user meets the minimum speak level requirement.
func CanSpeak(u *UserInfo, speakMinLevel int) bool {
	return u.GroupLevel >= speakMinLevel
}

// IsAdmin returns true if user has admin privileges (permission_level >= 3).
func IsAdmin(u *UserInfo) bool {
	return u.PermissionLevel >= 3
}

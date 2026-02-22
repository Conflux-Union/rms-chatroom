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

// PermRule defines a dual-dimension permission rule.
type PermRule struct {
	PermMinLevel  int
	GroupMinLevel int
	LogicOperator string // "AND" or "OR"
}

// Check evaluates whether the user satisfies this permission rule.
func (r PermRule) Check(u *UserInfo) bool {
	permOK := u.PermissionLevel >= r.PermMinLevel
	groupOK := u.GroupLevel >= r.GroupMinLevel
	if r.LogicOperator == "OR" {
		return permOK || groupOK
	}
	return permOK && groupOK
}

// CanAccess checks if user meets the permission rule for access.
func CanAccess(u *UserInfo, rule PermRule) bool {
	return rule.Check(u)
}

// CanSpeak checks if user meets the permission rule for speaking.
func CanSpeak(u *UserInfo, rule PermRule) bool {
	return rule.Check(u)
}

// IsAdmin returns true if user has admin privileges (permission_level >= 3).
func IsAdmin(u *UserInfo) bool {
	return u.PermissionLevel >= 3
}

package permission

// UserInfo represents an authenticated user from RMSSSO.
type UserInfo struct {
	ID                    int    `json:"id"`
	Username              string `json:"username"`
	Nickname              string `json:"nickname"`
	Email                 string `json:"email"`
	PermissionLevel       int    `json:"permission_level"`
	ServerPermissionLevel int    `json:"server_permission_level"`
	InternalLevel         int    `json:"internal_level"`
	AvatarURL             string `json:"avatar_url"`
}

// CanAccessServer checks if user can access a server.
// Servers only check internal level; server_permission_level is ignored.
func CanAccessServer(u *UserInfo, minServerLevel, minInternalLevel int) bool {
	return u.InternalLevel >= minInternalLevel
}

// CanAccessChannelGroup checks if user can see a channel group.
// Checks BOTH server permission level AND internal level.
func CanAccessChannelGroup(u *UserInfo, minServerLevel, minInternalLevel int) bool {
	return u.ServerPermissionLevel >= minServerLevel && u.InternalLevel >= minInternalLevel
}

// CanSeeChannel checks if user has visibility permission for a channel.
func CanSeeChannel(u *UserInfo, visMinServer, visMinInternal int) bool {
	return u.ServerPermissionLevel >= visMinServer && u.InternalLevel >= visMinInternal
}

// CanSpeakInChannel checks if user can post/speak in a channel.
func CanSpeakInChannel(u *UserInfo, speakMinServer, speakMinInternal int) bool {
	return u.ServerPermissionLevel >= speakMinServer && u.InternalLevel >= speakMinInternal
}

// IsAdmin returns true if user has admin privileges (permission_level >= 3).
func IsAdmin(u *UserInfo) bool {
	return u.PermissionLevel >= 3
}

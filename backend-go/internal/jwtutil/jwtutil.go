package jwtutil

import (
	"fmt"

	"github.com/golang-jwt/jwt/v5"

	"github.com/RMS-Server/rms-discord-go/internal/permission"
)

// ParseToken validates a JWT string and extracts user info from claims.
func ParseToken(tokenStr, secret string) (*permission.UserInfo, error) {
	token, err := jwt.Parse(tokenStr, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		return []byte(secret), nil
	})
	if err != nil {
		return nil, err
	}

	claims, ok := token.Claims.(jwt.MapClaims)
	if !ok || !token.Valid {
		return nil, fmt.Errorf("invalid token claims")
	}

	user := &permission.UserInfo{
		ID:              intFromClaims(claims, "id"),
		Username:        stringFromClaims(claims, "username"),
		Nickname:        stringFromClaims(claims, "nickname"),
		Email:           stringFromClaims(claims, "email"),
		PermissionLevel: intFromClaims(claims, "permission_level"),
		GroupLevel:      intFromClaims(claims, "group_level"),
		AvatarURL:       stringFromClaims(claims, "avatar_url"),
	}
	if user.ID == 0 {
		return nil, fmt.Errorf("token missing user id")
	}
	return user, nil
}

func intFromClaims(claims jwt.MapClaims, key string) int {
	v, ok := claims[key]
	if !ok {
		return 0
	}
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	case int64:
		return int(n)
	default:
		return 0
	}
}

func stringFromClaims(claims jwt.MapClaims, key string) string {
	v, ok := claims[key]
	if !ok {
		return ""
	}
	s, _ := v.(string)
	return s
}

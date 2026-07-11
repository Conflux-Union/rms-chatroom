package music

import (
	"testing"
	"time"
)

func TestNeedsRefresh(t *testing.T) {
	now := time.Now().Unix()
	threshold := 24 * time.Hour

	cases := []struct {
		name string
		cred *QQCredential
		want bool
	}{
		{"nil credential", nil, false},
		{"empty credential", &QQCredential{}, false},
		{
			"no refresh material",
			&QQCredential{MusicID: 1, MusicKey: "k", CreateTime: now, ExpiresIn: 60},
			false,
		},
		{
			"no expiry info",
			&QQCredential{MusicID: 1, MusicKey: "k", RefreshKey: "r"},
			false,
		},
		{
			"fresh key",
			&QQCredential{MusicID: 1, MusicKey: "k", RefreshKey: "r", CreateTime: now, ExpiresIn: 3 * 86400},
			false,
		},
		{
			"expiring within threshold",
			&QQCredential{MusicID: 1, MusicKey: "k", RefreshKey: "r", CreateTime: now, ExpiresIn: 3600},
			true,
		},
		{
			"already expired",
			&QQCredential{MusicID: 1, MusicKey: "k", RefreshToken: "r", CreateTime: now - 10*86400, ExpiresIn: 3 * 86400},
			true,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c := &QQMusicClient{credential: tc.cred}
			if got := c.NeedsRefresh(threshold); got != tc.want {
				t.Errorf("NeedsRefresh() = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestCredentialFromLoginData(t *testing.T) {
	data := map[string]interface{}{
		"musicid":            float64(12345),
		"musickey":           "newkey",
		"musickeyCreateTime": float64(1700000000),
		"keyExpiresIn":       float64(259200),
		"refresh_key":        "rk",
		"refresh_token":      "rt",
		"str_musicid":        "12345",
	}
	cred := credentialFromLoginData(data, 2)
	if cred.MusicID != 12345 || cred.MusicKey != "newkey" || cred.LoginType != 2 {
		t.Errorf("unexpected base fields: %+v", cred)
	}
	if cred.CreateTime != 1700000000 || cred.ExpiresIn != 259200 {
		t.Errorf("unexpected expiry fields: %+v", cred)
	}
	if cred.RefreshKey != "rk" || cred.RefreshToken != "rt" || cred.StrMusicID != "12345" {
		t.Errorf("unexpected extended fields: %+v", cred)
	}
}

func TestCredentialFromLoginDataFallsBackCreateTime(t *testing.T) {
	before := time.Now().Unix()
	cred := credentialFromLoginData(map[string]interface{}{
		"musicid":      float64(1),
		"musickey":     "k",
		"keyExpiresIn": float64(259200),
	}, 1)
	if cred.CreateTime < before {
		t.Errorf("CreateTime fallback not applied: %d", cred.CreateTime)
	}
}

func TestMergeMissingCredFields(t *testing.T) {
	old := &QQCredential{
		MusicID:      1,
		OpenID:       "old-openid",
		RefreshToken: "old-rt",
		AccessToken:  "old-at",
		RefreshKey:   "old-rk",
		UnionID:      "old-union",
		StrMusicID:   "1",
		EncryptUin:   "enc",
		ExpiredAt:    100,
	}
	cred := &QQCredential{MusicKey: "new", RefreshKey: "new-rk"}
	mergeMissingCredFields(cred, old)

	if cred.RefreshKey != "new-rk" {
		t.Errorf("new value overwritten: %s", cred.RefreshKey)
	}
	if cred.MusicID != 1 || cred.OpenID != "old-openid" || cred.RefreshToken != "old-rt" ||
		cred.AccessToken != "old-at" || cred.UnionID != "old-union" ||
		cred.StrMusicID != "1" || cred.EncryptUin != "enc" || cred.ExpiredAt != 100 {
		t.Errorf("missing fields not carried over: %+v", cred)
	}
}

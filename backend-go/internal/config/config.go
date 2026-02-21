package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

type Config struct {
	DatabaseURL      string   `json:"database_url"`
	SSOBaseURL       string   `json:"sso_base_url"`
	SSOVerifyEndpoint string  `json:"sso_verify_endpoint"`
	Host             string   `json:"host"`
	Port             int      `json:"port"`
	Debug            bool     `json:"debug"`
	FrontendDistPath string   `json:"frontend_dist_path"`
	CORSOrigins      []string `json:"cors_origins"`
	DeployToken      string   `json:"deploy_token"`
	LivekitHost      string   `json:"livekit_host"`
	LivekitInternalHost string `json:"livekit_internal_host"`
	LivekitAPIKey    string   `json:"livekit_api_key"`
	LivekitAPISecret string   `json:"livekit_api_secret"`
	JWTSecret        string   `json:"jwt_secret"`

	// OAuth 2.0 configuration
	OAuthBaseURL            string `json:"oauth_base_url"`
	OAuthAuthorizeEndpoint  string `json:"oauth_authorize_endpoint"`
	OAuthTokenEndpoint      string `json:"oauth_token_endpoint"`
	OAuthUserinfoEndpoint   string `json:"oauth_userinfo_endpoint"`
	OAuthClientID           string `json:"oauth_client_id"`
	OAuthClientSecret       string `json:"oauth_client_secret"`
	OAuthRedirectURI        string `json:"oauth_redirect_uri"`
	OAuthScope              string `json:"oauth_scope"`
}

func defaults() Config {
	return Config{
		DatabaseURL:            "sqlite3://./discord.db",
		SSOBaseURL:             "https://sso.rms.net.cn",
		SSOVerifyEndpoint:      "/api/user",
		Host:                   "0.0.0.0",
		Port:                   8000,
		Debug:                  true,
		FrontendDistPath:       "../packages/web/dist",
		CORSOrigins:            []string{"http://localhost:5173", "http://127.0.0.1:5173"},
		JWTSecret:              "dev-secret-change-in-production",
		OAuthBaseURL:           "https://sso.rms.net.cn",
		OAuthAuthorizeEndpoint: "/oauth/authorize",
		OAuthTokenEndpoint:     "/oauth/token",
		OAuthUserinfoEndpoint:  "/oauth/userinfo",
		OAuthScope:             "openid profile",
	}
}

func Load() (*Config, error) {
	cfg := defaults()

	// Find config.json relative to the binary or working directory
	configPath := findConfigPath()
	if configPath != "" {
		data, err := os.ReadFile(configPath)
		if err == nil {
			_ = json.Unmarshal(data, &cfg)
		}
	}

	// Environment variable overrides
	applyEnvOverrides(&cfg)

	return &cfg, nil
}

func findConfigPath() string {
	candidates := []string{"config.json"}

	// Also check relative to the executable
	if ex, err := os.Executable(); err == nil {
		candidates = append(candidates, filepath.Join(filepath.Dir(ex), "config.json"))
	}

	// Check relative to source file (for development)
	if _, filename, _, ok := runtime.Caller(0); ok {
		candidates = append(candidates, filepath.Join(filepath.Dir(filename), "..", "..", "config.json"))
	}

	for _, p := range candidates {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

func applyEnvOverrides(cfg *Config) {
	if v := os.Getenv("DATABASE_URL"); v != "" {
		cfg.DatabaseURL = v
	}
	if v := os.Getenv("SSO_BASE_URL"); v != "" {
		cfg.SSOBaseURL = v
	}
	if v := os.Getenv("HOST"); v != "" {
		cfg.Host = v
	}
	if v := os.Getenv("JWT_SECRET"); v != "" {
		cfg.JWTSecret = v
	}
	if v := os.Getenv("CORS_ORIGINS"); v != "" {
		cfg.CORSOrigins = strings.Split(v, ",")
		for i := range cfg.CORSOrigins {
			cfg.CORSOrigins[i] = strings.TrimSpace(cfg.CORSOrigins[i])
		}
	}
}

package main

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadConfigRejectsDevIdentityOnNonLoopbackBind(t *testing.T) {
	dist := t.TempDir()
	setBaseEnvironment(t, dist)
	t.Setenv("AE2_DEV_IDENTITY", "developer@example.test")

	for _, address := range []string{"0.0.0.0:8080", "[::]:8080", "localhost:8080", ":8080"} {
		t.Run(address, func(t *testing.T) {
			t.Setenv("AE2_LISTEN_ADDR", address)
			_, err := LoadConfig()
			if err == nil || !strings.Contains(err.Error(), "literal loopback") {
				t.Fatalf("LoadConfig() error = %v, want loopback rejection", err)
			}
		})
	}

	t.Setenv("AE2_LISTEN_ADDR", "127.0.0.1:8080")
	if _, err := LoadConfig(); err != nil {
		t.Fatalf("loopback development config rejected: %v", err)
	}
}

func TestLoadConfigProductionFailsClosed(t *testing.T) {
	setBaseEnvironment(t, t.TempDir())
	t.Setenv("AE2_DEV_IDENTITY", "")
	t.Setenv("AE2_CF_ISSUER", "")
	t.Setenv("AE2_CF_AUDIENCE", "")
	if _, err := LoadConfig(); err == nil {
		t.Fatal("LoadConfig() accepted production config without Access settings")
	}

	t.Setenv("AE2_CF_ISSUER", "https://team.cloudflareaccess.com")
	t.Setenv("AE2_CF_AUDIENCE", "audience")
	t.Setenv("AE2_PUBLIC_ORIGIN", "https://ae2.example.test")
	if _, err := LoadConfig(); err != nil {
		t.Fatalf("valid production config rejected: %v", err)
	}
}

func TestLoadConfigRejectsProductionNonLoopbackBind(t *testing.T) {
	setBaseEnvironment(t, t.TempDir())
	t.Setenv("AE2_DEV_IDENTITY", "")
	t.Setenv("AE2_CF_ISSUER", "https://team.cloudflareaccess.com")
	t.Setenv("AE2_CF_AUDIENCE", "audience")
	t.Setenv("AE2_PUBLIC_ORIGIN", "https://ae2.example.test")

	for _, address := range []string{"0.0.0.0:8080", "[::]:8080", "192.0.2.10:8080", "localhost:8080", ":8080"} {
		t.Run(address, func(t *testing.T) {
			t.Setenv("AE2_LISTEN_ADDR", address)
			_, err := LoadConfig()
			if err == nil || !strings.Contains(err.Error(), "literal loopback") {
				t.Fatalf("LoadConfig() error = %v, want production loopback rejection", err)
			}
		})
	}
}

func TestLoadConfigScopesDevIdentityToLoopbackOrigin(t *testing.T) {
	setBaseEnvironment(t, t.TempDir())
	t.Setenv("AE2_DEV_IDENTITY", "developer@example.test")
	t.Setenv("AE2_PUBLIC_ORIGIN", "http://dashboard.example.test:8080")
	if _, err := LoadConfig(); err == nil || !strings.Contains(err.Error(), "PUBLIC_ORIGIN") {
		t.Fatalf("LoadConfig() error = %v, want public origin loopback rejection", err)
	}
}

func TestLoadConfigRejectsUnsafeModURL(t *testing.T) {
	setBaseEnvironment(t, t.TempDir())
	t.Setenv("AE2_DEV_IDENTITY", "developer@example.test")
	for _, modURL := range []string{
		"http://localhost:8765",
		"http://192.168.1.2:8765",
		"https://127.0.0.1:8765",
		"http://127.0.0.1:8765/admin",
		"http://user:pass@127.0.0.1:8765",
	} {
		t.Run(modURL, func(t *testing.T) {
			t.Setenv("AE2_MOD_URL", modURL)
			if _, err := LoadConfig(); err == nil {
				t.Fatalf("LoadConfig() accepted unsafe mod URL %q", modURL)
			}
		})
	}
}

func setBaseEnvironment(t *testing.T, dist string) {
	t.Helper()
	t.Setenv("AE2_LISTEN_ADDR", "127.0.0.1:8080")
	t.Setenv("AE2_DIST_DIR", filepath.Clean(dist))
	t.Setenv("AE2_PUBLIC_ORIGIN", "http://127.0.0.1:8080")
	t.Setenv("AE2_MOD_URL", "http://127.0.0.1:8765")
	t.Setenv("AE2_MOD_SECRET", strings.Repeat("s", 32))
	t.Setenv("AE2_DEV_IDENTITY", "")
	t.Setenv("AE2_CF_ISSUER", "")
	t.Setenv("AE2_CF_AUDIENCE", "")
	t.Setenv("AE2_MAX_BODY_BYTES", "")
	t.Setenv("AE2_PROXY_TIMEOUT", "")
	t.Setenv("AE2_RATE_PER_MINUTE", "")
	t.Setenv("AE2_RATE_BURST", "")
	t.Setenv("AE2_MUTATION_RATE_PER_MINUTE", "")
	t.Setenv("AE2_MUTATION_RATE_BURST", "")
}

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

func TestLoadConfigAcceptsTailscaleBind(t *testing.T) {
	setBaseEnvironment(t, t.TempDir())
	t.Setenv("AE2_TRUST_TAILSCALE", "1")
	t.Setenv("AE2_TAILSCALE_IDENTITY", "pixie@example.test")
	t.Setenv("AE2_LISTEN_ADDR", "100.81.146.95:8080")
	t.Setenv("AE2_PUBLIC_ORIGIN", "http://100.81.146.95:8080")

	if _, err := LoadConfig(); err != nil {
		t.Fatalf("tailscale config rejected: %v", err)
	}

	for _, address := range []string{"0.0.0.0:8080", "192.0.2.10:8080", "127.0.0.1:8080"} {
		t.Run(address, func(t *testing.T) {
			t.Setenv("AE2_LISTEN_ADDR", address)
			_, err := LoadConfig()
			if address == "127.0.0.1:8080" {
				if err != nil {
					t.Fatalf("loopback listener rejected in tailscale mode: %v", err)
				}
				return
			}
			if err == nil || !strings.Contains(err.Error(), "literal loopback") {
				t.Fatalf("LoadConfig() error = %v, want non-tailscale bind rejection", err)
			}
		})
	}
}

func TestLoadConfigAcceptsMultipleTailscaleOrigins(t *testing.T) {
	setBaseEnvironment(t, t.TempDir())
	t.Setenv("AE2_TRUST_TAILSCALE", "1")
	t.Setenv("AE2_TAILSCALE_IDENTITY", "pixie@example.test")
	t.Setenv("AE2_LISTEN_ADDR", "100.81.146.95:8080")
	t.Setenv("AE2_PUBLIC_ORIGIN", "http://100.81.146.95:8080, http://star-tech-2.tail11be65.ts.net:8080, http://star-tech-2:8080")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("multi-origin tailscale config rejected: %v", err)
	}
	if cfg.PublicOrigin.Host != "100.81.146.95:8080" {
		t.Fatalf("canonical origin = %s, want the first entry", cfg.PublicOrigin.Host)
	}
	if len(cfg.AllowedOrigins) != 3 {
		t.Fatalf("allowed origins = %d, want 3", len(cfg.AllowedOrigins))
	}
}

func TestLoadConfigRequiresTailscaleIdentity(t *testing.T) {
	setBaseEnvironment(t, t.TempDir())
	t.Setenv("AE2_TRUST_TAILSCALE", "1")
	t.Setenv("AE2_LISTEN_ADDR", "100.81.146.95:8080")
	t.Setenv("AE2_PUBLIC_ORIGIN", "http://100.81.146.95:8080")
	if _, err := LoadConfig(); err == nil || !strings.Contains(err.Error(), "AE2_TAILSCALE_IDENTITY") {
		t.Fatalf("LoadConfig() error = %v, want missing identity rejection", err)
	}

	t.Setenv("AE2_TAILSCALE_IDENTITY", "pixie@example.test")
	t.Setenv("AE2_DEV_IDENTITY", "pixie@example.test")
	if _, err := LoadConfig(); err == nil || !strings.Contains(err.Error(), "mutually exclusive") {
		t.Fatalf("LoadConfig() error = %v, want mutually exclusive rejection", err)
	}
}

func TestLoadConfigRejectsTailscaleOriginOnPublicAddress(t *testing.T) {
	setBaseEnvironment(t, t.TempDir())
	t.Setenv("AE2_TRUST_TAILSCALE", "1")
	t.Setenv("AE2_TAILSCALE_IDENTITY", "pixie@example.test")
	t.Setenv("AE2_LISTEN_ADDR", "100.81.146.95:8080")
	t.Setenv("AE2_PUBLIC_ORIGIN", "http://192.0.2.10:8080")
	if _, err := LoadConfig(); err == nil || !strings.Contains(err.Error(), "PUBLIC_ORIGIN") {
		t.Fatalf("LoadConfig() error = %v, want public origin rejection", err)
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
	t.Setenv("AE2_TRUST_TAILSCALE", "")
	t.Setenv("AE2_TAILSCALE_IDENTITY", "")
	t.Setenv("AE2_CF_ISSUER", "")
	t.Setenv("AE2_CF_AUDIENCE", "")
	t.Setenv("AE2_MAX_BODY_BYTES", "")
	t.Setenv("AE2_PROXY_TIMEOUT", "")
	t.Setenv("AE2_RATE_PER_MINUTE", "")
	t.Setenv("AE2_RATE_BURST", "")
	t.Setenv("AE2_MUTATION_RATE_PER_MINUTE", "")
	t.Setenv("AE2_MUTATION_RATE_BURST", "")
}

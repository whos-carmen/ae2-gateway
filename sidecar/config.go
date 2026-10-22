package main

import (
	"errors"
	"fmt"
	"net"
	"net/mail"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultMaxBodyBytes = int64(64 << 10)
	minimumSecretBytes  = 32
)

type Config struct {
	ListenAddr        string
	DistDir           string
	ModURL            *url.URL
	ModSecret         []byte
	PublicOrigin      *url.URL
	AllowedOrigins    []*url.URL
	DevIdentity       string
	TrustTailscale    bool
	TailscaleIdentity string
	CFIssuer          string
	CFAudience        string
	JWKSURL           string
	MaxBodyBytes      int64
	ProxyTimeout      time.Duration
	RatePerMinute     int
	RateBurst         int
	MutatePerMinute   int
	MutateBurst       int
}

func LoadConfig() (Config, error) {
	cfg := Config{
		ListenAddr:        strings.TrimSpace(os.Getenv("AE2_LISTEN_ADDR")),
		DistDir:           strings.TrimSpace(os.Getenv("AE2_DIST_DIR")),
		DevIdentity:       strings.TrimSpace(os.Getenv("AE2_DEV_IDENTITY")),
		TailscaleIdentity: strings.TrimSpace(os.Getenv("AE2_TAILSCALE_IDENTITY")),
		CFIssuer:          strings.TrimRight(strings.TrimSpace(os.Getenv("AE2_CF_ISSUER")), "/"),
		CFAudience:        strings.TrimSpace(os.Getenv("AE2_CF_AUDIENCE")),
		MaxBodyBytes:      defaultMaxBodyBytes,
		ProxyTimeout:      15 * time.Second,
		RatePerMinute:     120,
		RateBurst:         30,
		MutatePerMinute:   12,
		MutateBurst:       4,
	}

	var err error
	if cfg.TrustTailscale, err = envBool("AE2_TRUST_TAILSCALE"); err != nil {
		return Config{}, err
	}
	if cfg.ListenAddr == "" {
		return Config{}, errors.New("AE2_LISTEN_ADDR is required")
	}
	if err = validateListenAddress(cfg.ListenAddr, cfg.TrustTailscale); err != nil {
		return Config{}, err
	}
	if cfg.DistDir == "" {
		return Config{}, errors.New("AE2_DIST_DIR is required")
	}
	info, err := os.Stat(cfg.DistDir)
	if err != nil {
		return Config{}, fmt.Errorf("AE2_DIST_DIR: %w", err)
	}
	if !info.IsDir() {
		return Config{}, errors.New("AE2_DIST_DIR must be a directory")
	}

	cfg.ModURL, err = parseModURL(os.Getenv("AE2_MOD_URL"))
	if err != nil {
		return Config{}, err
	}
	cfg.ModSecret = []byte(os.Getenv("AE2_MOD_SECRET"))
	if len(cfg.ModSecret) < minimumSecretBytes {
		return Config{}, fmt.Errorf("AE2_MOD_SECRET must be at least %d bytes", minimumSecretBytes)
	}
	if cfg.AllowedOrigins, err = parseOrigins(os.Getenv("AE2_PUBLIC_ORIGIN")); err != nil {
		return Config{}, err
	}
	cfg.PublicOrigin = cfg.AllowedOrigins[0]

	if cfg.TrustTailscale {
		if cfg.DevIdentity != "" {
			return Config{}, errors.New("AE2_DEV_IDENTITY and AE2_TRUST_TAILSCALE are mutually exclusive")
		}
		if err := validateDevIdentity(cfg.TailscaleIdentity); err != nil {
			return Config{}, fmt.Errorf("AE2_TAILSCALE_IDENTITY: %w", err)
		}
		originIP := net.ParseIP(cfg.PublicOrigin.Hostname())
		if originIP == nil || !(originIP.IsLoopback() || isTailscaleAddress(originIP)) {
			return Config{}, errors.New("AE2_TRUST_TAILSCALE requires the first AE2_PUBLIC_ORIGIN to use a literal loopback or Tailscale IP")
		}
	} else if cfg.DevIdentity != "" {
		if err := validateDevIdentity(cfg.DevIdentity); err != nil {
			return Config{}, err
		}
		if !allOriginsLoopback(cfg.AllowedOrigins) {
			return Config{}, errors.New("AE2_DEV_IDENTITY requires AE2_PUBLIC_ORIGIN to use a literal loopback IP")
		}
	} else {
		if cfg.PublicOrigin.Scheme != "https" {
			return Config{}, errors.New("AE2_PUBLIC_ORIGIN must use https outside development mode")
		}
		if cfg.CFIssuer == "" || cfg.CFAudience == "" {
			return Config{}, errors.New("AE2_CF_ISSUER and AE2_CF_AUDIENCE are required outside development mode")
		}
		cfg.JWKSURL, err = cloudflareJWKSURL(cfg.CFIssuer)
		if err != nil {
			return Config{}, err
		}
	}

	if cfg.MaxBodyBytes, err = envInt64("AE2_MAX_BODY_BYTES", cfg.MaxBodyBytes, 1024, 1<<20); err != nil {
		return Config{}, err
	}
	if cfg.ProxyTimeout, err = envDuration("AE2_PROXY_TIMEOUT", cfg.ProxyTimeout, time.Second, 60*time.Second); err != nil {
		return Config{}, err
	}
	if cfg.RatePerMinute, err = envInt("AE2_RATE_PER_MINUTE", cfg.RatePerMinute, 1, 10000); err != nil {
		return Config{}, err
	}
	if cfg.RateBurst, err = envInt("AE2_RATE_BURST", cfg.RateBurst, 1, 1000); err != nil {
		return Config{}, err
	}
	if cfg.MutatePerMinute, err = envInt("AE2_MUTATION_RATE_PER_MINUTE", cfg.MutatePerMinute, 1, 1000); err != nil {
		return Config{}, err
	}
	if cfg.MutateBurst, err = envInt("AE2_MUTATION_RATE_BURST", cfg.MutateBurst, 1, 100); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func validateListenAddress(address string, allowTailscale bool) error {
	host, port, err := net.SplitHostPort(address)
	if err != nil || port == "" {
		return fmt.Errorf("AE2_LISTEN_ADDR must be a host:port address: %w", err)
	}
	parsedPort, err := strconv.ParseUint(port, 10, 16)
	if err != nil || parsedPort == 0 {
		return errors.New("AE2_LISTEN_ADDR port must be numeric and nonzero")
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return errors.New("AE2_LISTEN_ADDR must use a literal loopback IP")
	}
	if ip.IsLoopback() {
		return nil
	}
	if allowTailscale && isTailscaleAddress(ip) {
		return nil
	}
	return errors.New("AE2_LISTEN_ADDR must use a literal loopback IP")
}

// isTailscaleAddress reports whether ip is in the 100.64.0.0/10 range that
// Tailscale assigns to tailnet nodes.
func isTailscaleAddress(ip net.IP) bool {
	v4 := ip.To4()
	return v4 != nil && v4[0] == 100 && v4[1] >= 64 && v4[1] <= 127
}

func envBool(name string) (bool, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return false, nil
	}
	value, err := strconv.ParseBool(raw)
	if err != nil {
		return false, fmt.Errorf("%s must be a boolean", name)
	}
	return value, nil
}

func parseModURL(raw string) (*url.URL, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme == "" || u.Host == "" {
		return nil, errors.New("AE2_MOD_URL must be an absolute URL")
	}
	if u.Scheme != "http" || u.User != nil || u.RawQuery != "" || u.Fragment != "" ||
		(u.Path != "" && u.Path != "/") {
		return nil, errors.New("AE2_MOD_URL must be an http origin without credentials, path, query, or fragment")
	}
	ip := net.ParseIP(u.Hostname())
	if ip == nil || !ip.IsLoopback() {
		return nil, errors.New("AE2_MOD_URL must use a literal loopback IP")
	}
	u.Path = ""
	return u, nil
}

func parseOrigin(raw string) (*url.URL, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme == "" || u.Host == "" {
		return nil, errors.New("AE2_PUBLIC_ORIGIN must be an absolute origin")
	}
	if (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.RawQuery != "" ||
		u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return nil, errors.New("AE2_PUBLIC_ORIGIN must contain only an http or https origin")
	}
	u.Path = ""
	return u, nil
}

// parseOrigins accepts a comma-separated list. The first entry is canonical:
// its scheme drives cookie and security-header policy. The rest are additional
// hosts and origins the browser may use.
func parseOrigins(raw string) ([]*url.URL, error) {
	seen := make(map[string]bool)
	var origins []*url.URL
	for _, part := range strings.Split(raw, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		origin, err := parseOrigin(part)
		if err != nil {
			return nil, err
		}
		key := origin.String()
		if seen[key] {
			continue
		}
		seen[key] = true
		origins = append(origins, origin)
	}
	if len(origins) == 0 {
		return nil, errors.New("AE2_PUBLIC_ORIGIN must contain at least one origin")
	}
	return origins, nil
}

func allOriginsLoopback(origins []*url.URL) bool {
	for _, origin := range origins {
		ip := net.ParseIP(origin.Hostname())
		if ip == nil || !ip.IsLoopback() {
			return false
		}
	}
	return true
}

func validateDevIdentity(identity string) error {
	address, err := mail.ParseAddress(identity)
	if err != nil || address.Address != identity || len(identity) > 254 {
		return errors.New("AE2_DEV_IDENTITY must be a plain email address")
	}
	return nil
}

func cloudflareJWKSURL(issuer string) (string, error) {
	u, err := url.Parse(issuer)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.Port() != "" ||
		u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Path != "" {
		return "", errors.New("AE2_CF_ISSUER must be an https Cloudflare Access team origin")
	}
	host := strings.ToLower(u.Hostname())
	if !strings.HasSuffix(host, ".cloudflareaccess.com") || host == ".cloudflareaccess.com" {
		return "", errors.New("AE2_CF_ISSUER must use a cloudflareaccess.com team domain")
	}
	return issuer + "/cdn-cgi/access/certs", nil
}

func envInt(name string, fallback, minValue, maxValue int) (int, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < minValue || value > maxValue {
		return 0, fmt.Errorf("%s must be between %d and %d", name, minValue, maxValue)
	}
	return value, nil
}

func envInt64(name string, fallback, minValue, maxValue int64) (int64, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || value < minValue || value > maxValue {
		return 0, fmt.Errorf("%s must be between %d and %d", name, minValue, maxValue)
	}
	return value, nil
}

func envDuration(name string, fallback, minValue, maxValue time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := time.ParseDuration(raw)
	if err != nil || value < minValue || value > maxValue {
		return 0, fmt.Errorf("%s must be between %s and %s", name, minValue, maxValue)
	}
	return value, nil
}

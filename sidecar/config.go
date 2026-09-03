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
	ListenAddr      string
	DistDir         string
	ModURL          *url.URL
	ModSecret       []byte
	PublicOrigin    *url.URL
	DevIdentity     string
	CFIssuer        string
	CFAudience      string
	JWKSURL         string
	MaxBodyBytes    int64
	ProxyTimeout    time.Duration
	RatePerMinute   int
	RateBurst       int
	MutatePerMinute int
	MutateBurst     int
}

func LoadConfig() (Config, error) {
	cfg := Config{
		ListenAddr:      strings.TrimSpace(os.Getenv("AE2_LISTEN_ADDR")),
		DistDir:         strings.TrimSpace(os.Getenv("AE2_DIST_DIR")),
		DevIdentity:     strings.TrimSpace(os.Getenv("AE2_DEV_IDENTITY")),
		CFIssuer:        strings.TrimRight(strings.TrimSpace(os.Getenv("AE2_CF_ISSUER")), "/"),
		CFAudience:      strings.TrimSpace(os.Getenv("AE2_CF_AUDIENCE")),
		MaxBodyBytes:    defaultMaxBodyBytes,
		ProxyTimeout:    15 * time.Second,
		RatePerMinute:   120,
		RateBurst:       30,
		MutatePerMinute: 12,
		MutateBurst:     4,
	}

	var err error
	if cfg.ListenAddr == "" {
		return Config{}, errors.New("AE2_LISTEN_ADDR is required")
	}
	if err = validateListenAddress(cfg.ListenAddr); err != nil {
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
	cfg.PublicOrigin, err = parseOrigin(os.Getenv("AE2_PUBLIC_ORIGIN"))
	if err != nil {
		return Config{}, err
	}

	if cfg.DevIdentity != "" {
		if err := validateDevIdentity(cfg.DevIdentity); err != nil {
			return Config{}, err
		}
		originIP := net.ParseIP(cfg.PublicOrigin.Hostname())
		if originIP == nil || !originIP.IsLoopback() {
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

func validateListenAddress(address string) error {
	host, port, err := net.SplitHostPort(address)
	if err != nil || port == "" {
		return fmt.Errorf("AE2_LISTEN_ADDR must be a host:port address: %w", err)
	}
	parsedPort, err := strconv.ParseUint(port, 10, 16)
	if err != nil || parsedPort == 0 {
		return errors.New("AE2_LISTEN_ADDR port must be numeric and nonzero")
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return errors.New("AE2_LISTEN_ADDR must use a literal loopback IP")
	}
	return nil
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

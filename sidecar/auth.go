package main

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/mail"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

var errUnauthorized = errors.New("unauthorized")

type Identity struct {
	Subject string `json:"sub"`
	Email   string `json:"email"`
}

type Authenticator interface {
	Authenticate(*http.Request) (Identity, error)
}

type DevAuthenticator struct {
	Email string
}

func (a DevAuthenticator) Authenticate(r *http.Request) (Identity, error) {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return Identity{}, errUnauthorized
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return Identity{}, errUnauthorized
	}
	return Identity{Subject: "dev:" + a.Email, Email: a.Email}, nil
}

type accessClaims struct {
	Email string `json:"email"`
	jwt.RegisteredClaims
}

type jwkSet struct {
	Keys []jsonWebKey `json:"keys"`
}

type jsonWebKey struct {
	Kid string `json:"kid"`
	Kty string `json:"kty"`
	Alg string `json:"alg"`
	N   string `json:"n"`
	E   string `json:"e"`
}

type CloudflareAuthenticator struct {
	issuer   string
	audience string
	jwksURL  string
	client   *http.Client
	now      func() time.Time

	mu        sync.RWMutex
	keys      map[string]*rsa.PublicKey
	expiresAt time.Time
	refreshed time.Time
}

func NewCloudflareAuthenticator(issuer, audience, jwksURL string, client *http.Client) *CloudflareAuthenticator {
	if client == nil {
		client = &http.Client{Timeout: 5 * time.Second}
	}
	return &CloudflareAuthenticator{
		issuer:   issuer,
		audience: audience,
		jwksURL:  jwksURL,
		client:   client,
		now:      time.Now,
		keys:     make(map[string]*rsa.PublicKey),
	}
}

func (a *CloudflareAuthenticator) Authenticate(r *http.Request) (Identity, error) {
	raw, err := accessToken(r)
	if err != nil {
		return Identity{}, errUnauthorized
	}

	claims := &accessClaims{}
	token, err := jwt.ParseWithClaims(raw, claims, func(token *jwt.Token) (any, error) {
		if token.Method.Alg() != jwt.SigningMethodRS256.Alg() {
			return nil, errors.New("unexpected signing method")
		}
		kid, ok := token.Header["kid"].(string)
		if !ok || kid == "" {
			return nil, errors.New("missing key id")
		}
		return a.lookupKey(r.Context(), kid)
	},
		jwt.WithValidMethods([]string{jwt.SigningMethodRS256.Alg()}),
		jwt.WithIssuer(a.issuer),
		jwt.WithAudience(a.audience),
		jwt.WithExpirationRequired(),
		jwt.WithIssuedAt(),
		jwt.WithLeeway(30*time.Second),
		jwt.WithTimeFunc(a.now),
	)
	if err != nil || !token.Valid || claims.Subject == "" || claims.Email == "" {
		return Identity{}, errUnauthorized
	}
	address, err := mail.ParseAddress(claims.Email)
	if err != nil || address.Address != claims.Email || len(claims.Subject) > 512 {
		return Identity{}, errUnauthorized
	}
	return Identity{Subject: claims.Subject, Email: claims.Email}, nil
}

func accessToken(r *http.Request) (string, error) {
	headerToken := strings.TrimSpace(r.Header.Get("Cf-Access-Jwt-Assertion"))
	cookie, cookieErr := r.Cookie("CF_Authorization")
	cookieToken := ""
	if cookieErr == nil {
		cookieToken = strings.TrimSpace(cookie.Value)
	}
	if headerToken != "" && cookieToken != "" && headerToken != cookieToken {
		return "", errUnauthorized
	}
	if headerToken != "" {
		return headerToken, nil
	}
	if cookieToken != "" {
		return cookieToken, nil
	}
	return "", errUnauthorized
}

func (a *CloudflareAuthenticator) lookupKey(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	a.mu.RLock()
	key := a.keys[kid]
	fresh := a.now().Before(a.expiresAt)
	recentlyRefreshed := a.now().Sub(a.refreshed) < time.Minute
	a.mu.RUnlock()
	if key != nil && fresh {
		return key, nil
	}
	if key == nil && fresh && recentlyRefreshed {
		return nil, errors.New("unknown key id")
	}
	if err := a.refreshKeys(ctx); err != nil {
		return nil, err
	}
	a.mu.RLock()
	key = a.keys[kid]
	a.mu.RUnlock()
	if key == nil {
		return nil, errors.New("unknown key id")
	}
	return key, nil
}

func (a *CloudflareAuthenticator) refreshKeys(ctx context.Context) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, a.jwksURL, nil)
	if err != nil {
		return err
	}
	request.Header.Set("Accept", "application/json")
	response, err := a.client.Do(request)
	if err != nil {
		return fmt.Errorf("fetch JWKS: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("fetch JWKS: unexpected status %d", response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return fmt.Errorf("read JWKS: %w", err)
	}
	var set jwkSet
	if err := json.Unmarshal(body, &set); err != nil {
		return fmt.Errorf("decode JWKS: %w", err)
	}
	keys := make(map[string]*rsa.PublicKey, len(set.Keys))
	for _, item := range set.Keys {
		if item.Kid == "" || item.Kty != "RSA" || (item.Alg != "" && item.Alg != "RS256") {
			continue
		}
		key, err := rsaKey(item)
		if err != nil {
			continue
		}
		keys[item.Kid] = key
	}
	if len(keys) == 0 {
		return errors.New("JWKS contains no usable RSA keys")
	}
	a.mu.Lock()
	a.keys = keys
	a.refreshed = a.now()
	a.expiresAt = a.refreshed.Add(15 * time.Minute)
	a.mu.Unlock()
	return nil
}

func rsaKey(item jsonWebKey) (*rsa.PublicKey, error) {
	modulusBytes, err := base64.RawURLEncoding.DecodeString(item.N)
	if err != nil {
		return nil, err
	}
	exponentBytes, err := base64.RawURLEncoding.DecodeString(item.E)
	if err != nil || len(exponentBytes) == 0 || len(exponentBytes) > 4 {
		return nil, errors.New("invalid RSA exponent")
	}
	exponent := 0
	for _, value := range exponentBytes {
		exponent = exponent<<8 | int(value)
	}
	modulus := new(big.Int).SetBytes(modulusBytes)
	if modulus.BitLen() < 2048 || exponent < 3 || exponent%2 == 0 {
		return nil, errors.New("invalid RSA key")
	}
	return &rsa.PublicKey{N: modulus, E: exponent}, nil
}

package main

import (
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

func TestCloudflareJWTClaimValidation(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	wrongKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	const kid = "test-key"
	jwks := jwkSet{Keys: []jsonWebKey{publicJWK(kid, &key.PublicKey)}}
	jwksServer := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(writer).Encode(jwks)
	}))
	defer jwksServer.Close()

	now := time.Unix(1_800_000_000, 0)
	issuer := jwksServer.URL
	audience := "expected-audience"
	authenticator := NewCloudflareAuthenticator(issuer, audience, jwksServer.URL, jwksServer.Client())
	authenticator.now = func() time.Time { return now }

	validClaims := accessClaims{
		Email: "user@example.test",
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   "access-subject",
			Issuer:    issuer,
			Audience:  jwt.ClaimStrings{audience},
			IssuedAt:  jwt.NewNumericDate(now.Add(-time.Minute)),
			NotBefore: jwt.NewNumericDate(now.Add(-time.Minute)),
			ExpiresAt: jwt.NewNumericDate(now.Add(time.Minute)),
		},
	}
	tests := []struct {
		name   string
		claims accessClaims
		key    *rsa.PrivateKey
		valid  bool
	}{
		{name: "valid", claims: validClaims, key: key, valid: true},
		{name: "wrong issuer", claims: withClaims(validClaims, func(c *accessClaims) { c.Issuer = "https://other.example" }), key: key},
		{name: "wrong audience", claims: withClaims(validClaims, func(c *accessClaims) { c.Audience = jwt.ClaimStrings{"other"} }), key: key},
		{name: "expired", claims: withClaims(validClaims, func(c *accessClaims) { c.ExpiresAt = jwt.NewNumericDate(now.Add(-2 * time.Minute)) }), key: key},
		{name: "not active", claims: withClaims(validClaims, func(c *accessClaims) { c.NotBefore = jwt.NewNumericDate(now.Add(2 * time.Minute)) }), key: key},
		{name: "missing subject", claims: withClaims(validClaims, func(c *accessClaims) { c.Subject = "" }), key: key},
		{name: "missing email", claims: withClaims(validClaims, func(c *accessClaims) { c.Email = "" }), key: key},
		{name: "wrong signature", claims: validClaims, key: wrongKey},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			token := signedAccessToken(t, kid, test.claims, test.key)
			request := httptest.NewRequest(http.MethodGet, "http://gateway.test/api/v1/grids", nil)
			request.Header.Set("Cf-Access-Jwt-Assertion", token)
			identity, err := authenticator.Authenticate(request)
			if test.valid {
				if err != nil {
					t.Fatalf("Authenticate() rejected valid token: %v", err)
				}
				if identity.Subject != validClaims.Subject || identity.Email != validClaims.Email {
					t.Fatalf("unexpected identity: %+v", identity)
				}
			} else if err == nil {
				t.Fatal("Authenticate() accepted invalid token")
			}
		})
	}
}

func TestCloudflareJWTRejectsAmbiguousSources(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "http://gateway.test/", nil)
	request.Header.Set("Cf-Access-Jwt-Assertion", "header-token")
	request.AddCookie(&http.Cookie{Name: "CF_Authorization", Value: "cookie-token"})
	if _, err := accessToken(request); err == nil {
		t.Fatal("accessToken() accepted mismatched header and cookie")
	}
}

func TestDevAuthenticatorRequiresLoopbackClient(t *testing.T) {
	authenticator := DevAuthenticator{Email: "developer@example.test"}
	request := httptest.NewRequest(http.MethodGet, "http://127.0.0.1/", nil)
	request.RemoteAddr = "203.0.113.4:1234"
	if _, err := authenticator.Authenticate(request); err == nil {
		t.Fatal("development identity accepted a non-loopback client")
	}
	request.RemoteAddr = "127.0.0.1:1234"
	if _, err := authenticator.Authenticate(request); err != nil {
		t.Fatalf("development identity rejected loopback client: %v", err)
	}
}

func withClaims(source accessClaims, change func(*accessClaims)) accessClaims {
	clone := source
	clone.Audience = append(jwt.ClaimStrings(nil), source.Audience...)
	change(&clone)
	return clone
}

func signedAccessToken(t *testing.T, kid string, claims accessClaims, key *rsa.PrivateKey) string {
	t.Helper()
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
	token.Header["kid"] = kid
	signed, err := token.SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	return signed
}

func publicJWK(kid string, key *rsa.PublicKey) jsonWebKey {
	exponent := big.NewInt(int64(key.E)).Bytes()
	return jsonWebKey{
		Kid: kid,
		Kty: "RSA",
		Alg: "RS256",
		N:   base64.RawURLEncoding.EncodeToString(key.N.Bytes()),
		E:   base64.RawURLEncoding.EncodeToString(exponent),
	}
}

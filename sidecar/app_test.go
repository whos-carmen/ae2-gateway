package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type fixedAuthenticator struct {
	identity Identity
}

func (a fixedAuthenticator) Authenticate(*http.Request) (Identity, error) {
	return a.identity, nil
}

func TestOriginPolicyRejectsBypasses(t *testing.T) {
	var upstreamCalls atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		upstreamCalls.Add(1)
		writer.WriteHeader(http.StatusCreated)
	}))
	defer upstream.Close()
	app := newTestApp(t, upstream.URL)
	csrf := base64.RawURLEncoding.EncodeToString([]byte(strings.Repeat("c", 32)))

	tests := []struct {
		name    string
		origins []string
		want    int
	}{
		{name: "missing", want: http.StatusForbidden},
		{name: "attacker suffix", origins: []string{"https://dashboard.test.evil"}, want: http.StatusForbidden},
		{name: "userinfo confusion", origins: []string{"https://dashboard.test@evil.test"}, want: http.StatusForbidden},
		{name: "trailing slash", origins: []string{"https://dashboard.test/"}, want: http.StatusForbidden},
		{name: "null", origins: []string{"null"}, want: http.StatusForbidden},
		{name: "comma joined", origins: []string{"https://dashboard.test, https://evil.test"}, want: http.StatusForbidden},
		{name: "multiple headers", origins: []string{"https://dashboard.test", "https://evil.test"}, want: http.StatusForbidden},
		{name: "case mismatch", origins: []string{"https://DASHBOARD.test"}, want: http.StatusForbidden},
		{name: "exact", origins: []string{"https://dashboard.test"}, want: http.StatusCreated},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := newAppRequest(t, http.MethodPost, "/api/v1/identity/link-code", nil)
			for _, origin := range test.origins {
				request.Header.Add("Origin", origin)
			}
			request.Header.Set("X-CSRF-Token", csrf)
			request.AddCookie(&http.Cookie{Name: csrfCookieName, Value: csrf})
			response := httptest.NewRecorder()
			app.ServeHTTP(response, request)
			if response.Code != test.want {
				t.Fatalf("status = %d, want %d; body = %s", response.Code, test.want, response.Body.String())
			}
		})
	}
	if upstreamCalls.Load() != 1 {
		t.Fatalf("upstream calls = %d, want 1", upstreamCalls.Load())
	}
}

func TestHostPolicyRejectsLookalikes(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer upstream.Close()
	app := newTestApp(t, upstream.URL)
	for _, host := range []string{"dashboard.test.evil", "dashboard.test:443", "dashboard.test.", "evil.test"} {
		t.Run(host, func(t *testing.T) {
			request := newAppRequest(t, http.MethodGet, "/healthz", nil)
			request.Host = host
			response := httptest.NewRecorder()
			app.ServeHTTP(response, request)
			if response.Code != http.StatusMisdirectedRequest {
				t.Fatalf("status = %d, want %d", response.Code, http.StatusMisdirectedRequest)
			}
		})
	}
}

func TestProxyStripsIdentityHeadersAndSignsAssertion(t *testing.T) {
	var outboundHeaders http.Header
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		outboundHeaders = request.Header.Clone()
		writer.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(writer, `{"ok":true}`)
	}))
	defer upstream.Close()
	app := newTestApp(t, upstream.URL)

	request := newAppRequest(t, http.MethodGet, "/api/v1/grids", nil)
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Cf-Access-Jwt-Assertion", "untrusted-access-token")
	request.Header.Set("CF-Ray", "untrusted-ray")
	request.Header.Set("X-AE2-Identity-Assertion", "forged")
	request.Header.Set("X-AE2-Mod-Secret", "forged")
	request.Header.Set("X-Forwarded-For", "10.0.0.1")
	request.Header.Set("Forwarded", "for=10.0.0.1")
	request.Header.Set("Authorization", "Bearer client-secret")
	request.Header.Set("Cookie", "session=client")
	request.Header.Set("X-Untrusted", "value")
	response := httptest.NewRecorder()
	app.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d; body = %s", response.Code, response.Body.String())
	}

	for _, name := range []string{
		"Cf-Access-Jwt-Assertion", "CF-Ray", "X-Forwarded-For", "Forwarded",
		"Authorization", "Cookie", "X-Untrusted",
	} {
		if value := outboundHeaders.Get(name); value != "" {
			t.Errorf("untrusted header %s was forwarded as %q", name, value)
		}
	}
	if got := outboundHeaders.Get(secretHeader); got != strings.Repeat("m", 32) {
		t.Fatalf("mod secret = %q, want configured secret", got)
	}
	if got := outboundHeaders.Get(requestIDHeader); got == "" || got != response.Header().Get(requestIDHeader) {
		t.Fatalf("outbound request ID %q does not match response %q", got, response.Header().Get(requestIDHeader))
	}
	verifyAssertion(
		t,
		outboundHeaders.Get(assertionHeader),
		[]byte(strings.Repeat("m", 32)),
		outboundHeaders.Get(requestIDHeader),
		http.MethodGet,
		"/api/v1/grids",
	)
}

func TestProxyPreservesOpenAPIPathAndBindsAssertion(t *testing.T) {
	var method, requestPath, rawQuery, assertion, requestID, requestBody string
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		method = request.Method
		requestPath = request.URL.Path
		rawQuery = request.URL.RawQuery
		assertion = request.Header.Get(assertionHeader)
		requestID = request.Header.Get(requestIDHeader)
		body, _ := io.ReadAll(request.Body)
		requestBody = string(body)
		writer.WriteHeader(http.StatusAccepted)
	}))
	defer upstream.Close()
	app := newTestApp(t, upstream.URL)

	const routePath = "/api/v1/grids/11111111-1111-4111-8111-111111111111/plans"
	body := `{"keyToken":"opaque","amount":2}`
	csrf := base64.RawURLEncoding.EncodeToString([]byte(strings.Repeat("p", 32)))
	request := newAppRequest(t, http.MethodPost, routePath, strings.NewReader(body))
	request.Header.Set("Origin", "https://dashboard.test")
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-CSRF-Token", csrf)
	request.AddCookie(&http.Cookie{Name: csrfCookieName, Value: csrf})
	response := httptest.NewRecorder()
	app.ServeHTTP(response, request)
	if response.Code != http.StatusAccepted {
		t.Fatalf("status = %d; body = %s", response.Code, response.Body.String())
	}
	if method != http.MethodPost || requestPath != routePath || rawQuery != "" || requestBody != body {
		t.Fatalf("upstream request = %s %s?%s body %q", method, requestPath, rawQuery, requestBody)
	}
	verifyAssertion(t, assertion, []byte(strings.Repeat("m", 32)), requestID, http.MethodPost, routePath)
}

func TestSPAFallbackAndAPINamespaceIsolation(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer upstream.Close()
	app := newTestApp(t, upstream.URL)

	request := newAppRequest(t, http.MethodGet, "/grids/selected", nil)
	response := httptest.NewRecorder()
	app.ServeHTTP(response, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), "dashboard shell") {
		t.Fatalf("SPA fallback response = %d %q", response.Code, response.Body.String())
	}
	cookies := response.Result().Cookies()
	if len(cookies) != 1 || cookies[0].Name != csrfCookieName || !cookies[0].Secure ||
		cookies[0].SameSite != http.SameSiteStrictMode {
		t.Fatalf("unexpected CSRF cookie: %+v", cookies)
	}

	request = newAppRequest(t, http.MethodGet, "/assets/app.js", nil)
	response = httptest.NewRecorder()
	app.ServeHTTP(response, request)
	if response.Code != http.StatusOK || response.Body.String() != "console.log('asset');" {
		t.Fatalf("asset response = %d %q", response.Code, response.Body.String())
	}

	for _, apiPath := range []string{"/api", "/api/v1/not-allowed"} {
		request = newAppRequest(t, http.MethodGet, apiPath, nil)
		response = httptest.NewRecorder()
		app.ServeHTTP(response, request)
		if response.Code != http.StatusNotFound || strings.Contains(response.Body.String(), "dashboard shell") {
			t.Fatalf("%s unexpectedly used SPA fallback: %d %q", apiPath, response.Code, response.Body.String())
		}
	}
}

func TestMutationRequiresCSRFAndEnforcesBodyLimit(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusAccepted)
	}))
	defer upstream.Close()
	app := newTestApp(t, upstream.URL)
	app.config.MaxBodyBytes = 16
	path := "/api/v1/grids/11111111-1111-4111-8111-111111111111/plans"

	request := newAppRequest(t, http.MethodPost, path, strings.NewReader(`{"amount":1}`))
	request.Header.Set("Origin", "https://dashboard.test")
	request.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	app.ServeHTTP(response, request)
	if response.Code != http.StatusForbidden {
		t.Fatalf("missing CSRF status = %d, want %d", response.Code, http.StatusForbidden)
	}

	csrf := base64.RawURLEncoding.EncodeToString([]byte(strings.Repeat("x", 32)))
	request = newAppRequest(t, http.MethodPost, path, strings.NewReader(`{"keyToken":"too-long","amount":1}`))
	request.Header.Set("Origin", "https://dashboard.test")
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-CSRF-Token", csrf)
	request.AddCookie(&http.Cookie{Name: csrfCookieName, Value: csrf})
	response = httptest.NewRecorder()
	app.ServeHTTP(response, request)
	if response.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized body status = %d, want %d; body = %s", response.Code, http.StatusRequestEntityTooLarge, response.Body.String())
	}
}

func newTestApp(t *testing.T, upstreamURL string) *App {
	t.Helper()
	dist := t.TempDir()
	if err := os.MkdirAll(dist+"/assets", 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(dist+"/index.html", []byte("<html>dashboard shell</html>"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(dist+"/assets/app.js", []byte("console.log('asset');"), 0o600); err != nil {
		t.Fatal(err)
	}
	modURL, err := url.Parse(upstreamURL)
	if err != nil {
		t.Fatal(err)
	}
	publicOrigin, _ := url.Parse("https://dashboard.test")
	config := Config{
		DistDir:         dist,
		ModURL:          modURL,
		ModSecret:       []byte(strings.Repeat("m", 32)),
		PublicOrigin:    publicOrigin,
		MaxBodyBytes:    defaultMaxBodyBytes,
		ProxyTimeout:    2 * time.Second,
		RatePerMinute:   10000,
		RateBurst:       1000,
		MutatePerMinute: 1000,
		MutateBurst:     100,
	}
	app, err := NewApp(config, fixedAuthenticator{identity: Identity{
		Subject: "subject-123",
		Email:   "user@example.test",
	}}, nil)
	if err != nil {
		t.Fatal(err)
	}
	return app
}

func newAppRequest(t *testing.T, method, target string, body io.Reader) *http.Request {
	t.Helper()
	request := httptest.NewRequest(method, "https://dashboard.test"+target, body)
	request.Host = "dashboard.test"
	request.RemoteAddr = "192.0.2.10:4321"
	return request
}

func verifyAssertion(t *testing.T, assertion string, secret []byte, requestID, method, requestPath string) {
	t.Helper()
	parts := strings.Split(assertion, ".")
	if len(parts) != 2 {
		t.Fatalf("assertion has %d parts", len(parts))
	}
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write([]byte(parts[0]))
	expectedSignature := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(parts[1]), []byte(expectedSignature)) {
		t.Fatal("assertion HMAC is invalid")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		t.Fatal(err)
	}
	var claims assertionClaims
	if err := json.Unmarshal(payload, &claims); err != nil {
		t.Fatal(err)
	}
	if claims.Subject != "subject-123" || claims.Email != "user@example.test" ||
		claims.ID != requestID || claims.Method != method || claims.Path != requestPath {
		t.Fatalf("unexpected assertion claims: %+v", claims)
	}
	if claims.ExpiresAt-claims.IssuedAt != 30 {
		t.Fatalf("assertion lifetime = %d seconds, want 30", claims.ExpiresAt-claims.IssuedAt)
	}
}

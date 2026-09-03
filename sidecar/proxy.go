package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"time"
)

const (
	assertionHeader = "X-AE2-Identity-Assertion"
	secretHeader    = "X-AE2-Mod-Secret"
	requestIDHeader = "X-Request-ID"
)

type identityContextKey struct{}
type requestIDContextKey struct{}

type assertionClaims struct {
	Subject   string `json:"sub"`
	Email     string `json:"email"`
	IssuedAt  int64  `json:"iat"`
	ExpiresAt int64  `json:"exp"`
	ID        string `json:"jti"`
	Method    string `json:"method"`
	Path      string `json:"path"`
}

type ModProxy struct {
	handler http.Handler
	now     func() time.Time
	secret  []byte
}

func NewModProxy(target *url.URL, secret []byte) *ModProxy {
	proxy := &ModProxy{
		now:    time.Now,
		secret: append([]byte(nil), secret...),
	}
	reverseProxy := &httputil.ReverseProxy{
		Transport: &http.Transport{
			Proxy:                 nil,
			DialContext:           (&net.Dialer{Timeout: 3 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
			ForceAttemptHTTP2:     false,
			MaxIdleConns:          20,
			MaxIdleConnsPerHost:   20,
			IdleConnTimeout:       60 * time.Second,
			ResponseHeaderTimeout: 10 * time.Second,
		},
		Rewrite: func(request *httputil.ProxyRequest) {
			request.SetURL(target)
			request.Out.Host = target.Host
			request.Out.Header = make(http.Header)
			copyProxyHeader(request.Out.Header, request.In.Header, "Accept")
			if request.In.Body != http.NoBody && request.In.ContentLength != 0 {
				copyProxyHeader(request.Out.Header, request.In.Header, "Content-Type")
			}
			copyProxyHeader(request.Out.Header, request.In.Header, "If-None-Match")
			copyProxyHeader(request.Out.Header, request.In.Header, "Idempotency-Key")
			request.Out.Header.Set("User-Agent", "ae2-gateway-sidecar")

			identity, _ := request.In.Context().Value(identityContextKey{}).(Identity)
			requestID, _ := request.In.Context().Value(requestIDContextKey{}).(string)
			assertion, err := proxy.sign(identity, requestID, request.In.Method, request.In.URL.Path)
			if err == nil {
				request.Out.Header.Set(assertionHeader, assertion)
			}
			request.Out.Header.Set(secretHeader, string(proxy.secret))
			request.Out.Header.Set(requestIDHeader, requestID)
		},
		ModifyResponse: func(response *http.Response) error {
			for name := range response.Header {
				canonical := http.CanonicalHeaderKey(name)
				if canonical == "Server" || canonical == "Set-Cookie" || canonical == "X-Powered-By" ||
					canonical == "Location" || canonical == "Content-Security-Policy" ||
					len(canonical) >= 6 && canonical[:6] == "X-Ae2-" ||
					len(canonical) >= 21 && canonical[:21] == "Access-Control-Allow-" {
					response.Header.Del(name)
				}
			}
			return nil
		},
		ErrorHandler: func(writer http.ResponseWriter, _ *http.Request, _ error) {
			writeJSONError(writer, http.StatusBadGateway, "upstream unavailable")
		},
	}
	proxy.handler = reverseProxy
	return proxy
}

func (p *ModProxy) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	p.handler.ServeHTTP(writer, request)
}

func (p *ModProxy) sign(identity Identity, requestID, method, path string) (string, error) {
	if identity.Subject == "" || identity.Email == "" || requestID == "" {
		return "", errors.New("missing assertion input")
	}
	now := p.now().UTC()
	claims := assertionClaims{
		Subject:   identity.Subject,
		Email:     identity.Email,
		IssuedAt:  now.Unix(),
		ExpiresAt: now.Add(30 * time.Second).Unix(),
		ID:        requestID,
		Method:    method,
		Path:      path,
	}
	payload, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	encodedPayload := base64.RawURLEncoding.EncodeToString(payload)
	mac := hmac.New(sha256.New, p.secret)
	_, _ = mac.Write([]byte(encodedPayload))
	signature := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return encodedPayload + "." + signature, nil
}

func copyProxyHeader(destination, source http.Header, name string) {
	values := source.Values(name)
	if len(values) == 0 {
		return
	}
	destination[name] = append([]string(nil), values...)
}

func newRequestID() string {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		sum := sha256.Sum256([]byte(time.Now().String()))
		return hex.EncodeToString(sum[:16])
	}
	return hex.EncodeToString(value[:])
}

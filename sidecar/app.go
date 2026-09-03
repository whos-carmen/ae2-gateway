package main

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/json"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"strings"
	"time"
)

var version = "dev"

type App struct {
	config          Config
	authenticator   Authenticator
	proxy           http.Handler
	static          http.Handler
	rateLimiter     *RateLimiter
	mutationLimiter *RateLimiter
	logger          *slog.Logger
}

func NewApp(config Config, authenticator Authenticator, logger *slog.Logger) (*App, error) {
	staticHandler, err := NewStaticHandler(config.DistDir, config.PublicOrigin.Scheme == "https")
	if err != nil {
		return nil, err
	}
	if logger == nil {
		logger = slog.New(slog.NewJSONHandler(io.Discard, nil))
	}
	return &App{
		config:          config,
		authenticator:   authenticator,
		proxy:           NewModProxy(config.ModURL, config.ModSecret),
		static:          staticHandler,
		rateLimiter:     NewRateLimiter(config.RatePerMinute, config.RateBurst),
		mutationLimiter: NewRateLimiter(config.MutatePerMinute, config.MutateBurst),
		logger:          logger,
	}, nil
}

func (a *App) ServeHTTP(baseWriter http.ResponseWriter, request *http.Request) {
	started := time.Now()
	requestID := newRequestID()
	writer := &statusWriter{ResponseWriter: baseWriter, status: http.StatusOK}
	setSecureHeaders(writer.Header(), a.config.PublicOrigin.Scheme == "https")
	writer.Header().Set(requestIDHeader, requestID)
	defer func() {
		ip, _ := remoteIP(request.RemoteAddr)
		a.logger.Info("request",
			"request_id", requestID,
			"method", request.Method,
			"path", request.URL.Path,
			"status", writer.status,
			"duration_ms", time.Since(started).Milliseconds(),
			"remote_ip", ip,
		)
	}()

	if !strings.EqualFold(request.Host, a.config.PublicOrigin.Host) {
		writeJSONError(writer, http.StatusMisdirectedRequest, "invalid host")
		return
	}
	if !validOriginHeader(request, a.config.PublicOrigin.String(), false) {
		writeJSONError(writer, http.StatusForbidden, "invalid origin")
		return
	}
	clientIP, ok := remoteIP(request.RemoteAddr)
	if !ok {
		writeJSONError(writer, http.StatusBadRequest, "invalid client address")
		return
	}
	if !a.rateLimiter.Allow(clientIP) {
		writer.Header().Set("Retry-After", "60")
		writeJSONError(writer, http.StatusTooManyRequests, "rate limit exceeded")
		return
	}

	if request.URL.Path == "/healthz" {
		a.serveHealth(writer, request)
		return
	}
	if request.URL.Path != "/api" && !strings.HasPrefix(request.URL.Path, "/api/") {
		a.static.ServeHTTP(writer, request)
		return
	}

	route, matched, pathKnown := matchAPIRoute(request)
	if !matched {
		if pathKnown {
			writer.Header().Set("Allow", allowedMethodForPath(request.URL.Path))
			writeJSONError(writer, http.StatusMethodNotAllowed, "method not allowed")
		} else {
			writeJSONError(writer, http.StatusNotFound, "route not found")
		}
		return
	}
	if !validRouteQuery(request.URL, route) {
		writeJSONError(writer, http.StatusBadRequest, "invalid query parameters")
		return
	}
	if route.mutation {
		if !validOriginHeader(request, a.config.PublicOrigin.String(), true) {
			writeJSONError(writer, http.StatusForbidden, "origin required")
			return
		}
		if !validCSRFRequest(request) {
			writeJSONError(writer, http.StatusForbidden, "invalid CSRF token")
			return
		}
		if !a.mutationLimiter.Allow(clientIP) {
			writer.Header().Set("Retry-After", "60")
			writeJSONError(writer, http.StatusTooManyRequests, "mutation rate limit exceeded")
			return
		}
	}
	if err := validateAndBufferBody(writer, request, route, a.config.MaxBodyBytes); err != nil {
		return
	}
	if !validateIdempotencyKey(request) {
		writeJSONError(writer, http.StatusBadRequest, "invalid idempotency key")
		return
	}

	identity, err := a.authenticator.Authenticate(request)
	if err != nil {
		writeJSONError(writer, http.StatusUnauthorized, "authentication required")
		return
	}
	ctx, cancel := context.WithTimeout(request.Context(), a.config.ProxyTimeout)
	defer cancel()
	ctx = context.WithValue(ctx, identityContextKey{}, identity)
	ctx = context.WithValue(ctx, requestIDContextKey{}, requestID)
	a.proxy.ServeHTTP(writer, request.WithContext(ctx))
}

func (a *App) serveHealth(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		writer.Header().Set("Allow", "GET, HEAD")
		writeJSONError(writer, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writer.Header().Set("Cache-Control", "no-store")
	writer.Header().Set("Content-Type", "application/json")
	if request.Method == http.MethodHead {
		writer.WriteHeader(http.StatusOK)
		return
	}
	_ = json.NewEncoder(writer).Encode(map[string]string{"status": "ok", "version": version})
}

func validOriginHeader(request *http.Request, expected string, required bool) bool {
	values := request.Header.Values("Origin")
	if len(values) == 0 {
		return !required
	}
	if len(values) != 1 || strings.Contains(values[0], ",") {
		return false
	}
	return values[0] == expected
}

func validCSRFRequest(request *http.Request) bool {
	headers := request.Header.Values("X-CSRF-Token")
	if len(headers) != 1 || !validCSRFToken(headers[0]) {
		return false
	}
	cookie, err := request.Cookie(csrfCookieName)
	if err != nil || !validCSRFToken(cookie.Value) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(headers[0]), []byte(cookie.Value)) == 1
}

func validateAndBufferBody(writer http.ResponseWriter, request *http.Request, route apiRoute, limit int64) error {
	hasBody := request.ContentLength > 0 || len(request.TransferEncoding) > 0
	requiresBody := route.method == http.MethodPost && strings.HasSuffix(request.URL.Path, "/plans")
	allowsBody := requiresBody
	if !allowsBody && hasBody {
		writeJSONError(writer, http.StatusBadRequest, "request body not allowed")
		return errRequestHandled
	}
	if !allowsBody {
		request.Body = http.NoBody
		request.ContentLength = 0
		return nil
	}

	contentTypes := request.Header.Values("Content-Type")
	if len(contentTypes) != 1 {
		writeJSONError(writer, http.StatusUnsupportedMediaType, "Content-Type must be application/json")
		return errRequestHandled
	}
	contentType, parameters, err := mime.ParseMediaType(contentTypes[0])
	if err != nil || contentType != "application/json" {
		writeJSONError(writer, http.StatusUnsupportedMediaType, "Content-Type must be application/json")
		return errRequestHandled
	}
	for key, value := range parameters {
		if !strings.EqualFold(key, "charset") || !strings.EqualFold(value, "utf-8") {
			writeJSONError(writer, http.StatusUnsupportedMediaType, "unsupported Content-Type parameter")
			return errRequestHandled
		}
	}
	body, err := io.ReadAll(io.LimitReader(request.Body, limit+1))
	if err != nil {
		writeJSONError(writer, http.StatusBadRequest, "invalid request body")
		return errRequestHandled
	}
	_ = request.Body.Close()
	if int64(len(body)) > limit {
		writeJSONError(writer, http.StatusRequestEntityTooLarge, "request body too large")
		return errRequestHandled
	}
	if len(bytes.TrimSpace(body)) == 0 || !json.Valid(body) {
		writeJSONError(writer, http.StatusBadRequest, "request body must contain valid JSON")
		return errRequestHandled
	}
	request.Body = io.NopCloser(bytes.NewReader(body))
	request.ContentLength = int64(len(body))
	return nil
}

var errRequestHandled = &handledError{}

type handledError struct{}

func (*handledError) Error() string { return "request handled" }

func validateIdempotencyKey(request *http.Request) bool {
	values := request.Header.Values("Idempotency-Key")
	isSubmit := request.Method == http.MethodPost && strings.HasSuffix(request.URL.Path, "/submit")
	if !isSubmit {
		return len(values) == 0
	}
	if len(values) != 1 || len(values[0]) < 16 || len(values[0]) > 128 {
		return false
	}
	for _, char := range values[0] {
		if char < 0x21 || char > 0x7e {
			return false
		}
	}
	return true
}

func setSecureHeaders(header http.Header, hsts bool) {
	header.Set("Content-Security-Policy", "default-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; object-src 'none'")
	header.Set("Cross-Origin-Opener-Policy", "same-origin")
	header.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()")
	header.Set("Referrer-Policy", "no-referrer")
	header.Set("X-Content-Type-Options", "nosniff")
	header.Set("X-Frame-Options", "DENY")
	if hsts {
		header.Set("Strict-Transport-Security", "max-age=31536000")
	}
}

func writeJSONError(writer http.ResponseWriter, status int, message string) {
	writer.Header().Set("Cache-Control", "no-store")
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(map[string]string{"error": message})
}

type statusWriter struct {
	http.ResponseWriter
	status int
	wrote  bool
}

func (writer *statusWriter) WriteHeader(status int) {
	if writer.wrote {
		return
	}
	writer.status = status
	writer.wrote = true
	writer.ResponseWriter.WriteHeader(status)
}

func (writer *statusWriter) Write(body []byte) (int, error) {
	if !writer.wrote {
		writer.WriteHeader(http.StatusOK)
	}
	return writer.ResponseWriter.Write(body)
}

func (writer *statusWriter) Flush() {
	_ = http.NewResponseController(writer.ResponseWriter).Flush()
}

func (writer *statusWriter) Unwrap() http.ResponseWriter {
	return writer.ResponseWriter
}

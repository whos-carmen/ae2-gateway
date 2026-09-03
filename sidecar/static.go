package main

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"io/fs"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
)

const csrfCookieName = "ae2_csrf"

type StaticHandler struct {
	root         string
	secureCookie bool
}

func NewStaticHandler(directory string, secureCookie bool) (*StaticHandler, error) {
	root, err := filepath.EvalSymlinks(directory)
	if err != nil {
		return nil, err
	}
	root, err = filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	return &StaticHandler{root: root, secureCookie: secureCookie}, nil
}

func (h *StaticHandler) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		writer.Header().Set("Allow", "GET, HEAD")
		writeJSONError(writer, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if request.URL.RawPath != "" || strings.Contains(request.URL.Path, `\`) {
		http.NotFound(writer, request)
		return
	}

	name := strings.TrimPrefix(path.Clean("/"+request.URL.Path), "/")
	file, info, err := h.open(name)
	if err == nil && info.IsDir() {
		_ = file.Close()
		file, info, err = h.open(path.Join(name, "index.html"))
	}
	if err != nil || info.IsDir() {
		if file != nil {
			_ = file.Close()
		}
		file, info, err = h.open("index.html")
	}
	if err != nil {
		http.NotFound(writer, request)
		return
	}
	defer file.Close()

	if request.Method == http.MethodGet {
		ensureCSRFCookie(writer, request, h.secureCookie)
	}
	writer.Header().Set("Cache-Control", "no-cache")
	http.ServeContent(writer, request, info.Name(), info.ModTime(), file)
}

func (h *StaticHandler) open(name string) (*os.File, fs.FileInfo, error) {
	candidate := filepath.Join(h.root, filepath.FromSlash(name))
	resolved, err := filepath.EvalSymlinks(candidate)
	if err != nil {
		return nil, nil, err
	}
	relative, err := filepath.Rel(h.root, resolved)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return nil, nil, errors.New("path escapes static root")
	}
	file, err := os.Open(resolved)
	if err != nil {
		return nil, nil, err
	}
	info, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return nil, nil, err
	}
	return file, info, nil
}

func ensureCSRFCookie(writer http.ResponseWriter, request *http.Request, secure bool) {
	if cookie, err := request.Cookie(csrfCookieName); err == nil && validCSRFToken(cookie.Value) {
		return
	}
	var random [32]byte
	if _, err := rand.Read(random[:]); err != nil {
		return
	}
	http.SetCookie(writer, &http.Cookie{
		Name:     csrfCookieName,
		Value:    base64.RawURLEncoding.EncodeToString(random[:]),
		Path:     "/",
		MaxAge:   int((8 * time.Hour).Seconds()),
		Secure:   secure,
		HttpOnly: false,
		SameSite: http.SameSiteStrictMode,
	})
}

func validCSRFToken(value string) bool {
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	return err == nil && len(decoded) == 32
}

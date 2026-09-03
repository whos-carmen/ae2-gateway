package main

import (
	"net/http"
	"net/url"
	"path"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

type apiRoute struct {
	method       string
	pattern      *regexp.Regexp
	mutation     bool
	allowedQuery map[string]bool
}

const uuidPattern = `[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}`

var apiRoutes = []apiRoute{
	{method: http.MethodGet, pattern: regexp.MustCompile(`^/api/v1/health$`)},
	{method: http.MethodGet, pattern: regexp.MustCompile(`^/api/v1/identity$`)},
	{method: http.MethodPost, pattern: regexp.MustCompile(`^/api/v1/identity/link-code$`), mutation: true},
	{method: http.MethodGet, pattern: regexp.MustCompile(`^/api/v1/grids$`)},
	{
		method:  http.MethodGet,
		pattern: regexp.MustCompile(`^/api/v1/grids/` + uuidPattern + `/items$`),
		allowedQuery: map[string]bool{
			"cursor": true, "limit": true, "query": true, "craftable": true,
		},
	},
	{method: http.MethodGet, pattern: regexp.MustCompile(`^/api/v1/grids/` + uuidPattern + `/cpus$`)},
	{method: http.MethodGet, pattern: regexp.MustCompile(`^/api/v1/grids/` + uuidPattern + `/cpus/[^/]+$`)},
	{method: http.MethodGet, pattern: regexp.MustCompile(`^/api/v1/grids/` + uuidPattern + `/metrics$`)},
	{method: http.MethodPost, pattern: regexp.MustCompile(`^/api/v1/grids/` + uuidPattern + `/plans$`), mutation: true},
	{method: http.MethodGet, pattern: regexp.MustCompile(`^/api/v1/plans/` + uuidPattern + `$`)},
	{method: http.MethodPost, pattern: regexp.MustCompile(`^/api/v1/plans/` + uuidPattern + `/submit$`), mutation: true},
	{method: http.MethodGet, pattern: regexp.MustCompile(`^/api/v1/requests/` + uuidPattern + `$`)},
}

func matchAPIRoute(r *http.Request) (apiRoute, bool, bool) {
	if r.URL.RawPath != "" || strings.Contains(r.URL.Path, `\`) || path.Clean(r.URL.Path) != r.URL.Path {
		return apiRoute{}, false, false
	}
	pathKnown := false
	for _, route := range apiRoutes {
		if !route.pattern.MatchString(r.URL.Path) {
			continue
		}
		pathKnown = true
		if route.method != r.Method {
			continue
		}
		return route, true, pathKnown
	}
	return apiRoute{}, false, pathKnown
}

func validRouteQuery(requestURL *url.URL, route apiRoute) bool {
	if len(requestURL.RawQuery) > 2048 {
		return false
	}
	query, err := url.ParseQuery(requestURL.RawQuery)
	if err != nil {
		return false
	}
	for key, values := range query {
		if !route.allowedQuery[key] || len(values) != 1 {
			return false
		}
		value := values[0]
		switch key {
		case "limit":
			limit, err := strconv.Atoi(value)
			if err != nil || limit < 1 || limit > 5000 {
				return false
			}
		case "craftable":
			if value != "true" && value != "false" {
				return false
			}
		case "query":
			if !utf8.ValidString(value) || utf8.RuneCountInString(value) > 128 || hasControlCharacter(value) {
				return false
			}
		case "cursor":
			if hasControlCharacter(value) {
				return false
			}
		}
	}
	return true
}

func hasControlCharacter(value string) bool {
	return strings.IndexFunc(value, func(char rune) bool {
		return char < 0x20 || char == 0x7f
	}) >= 0
}

func allowedMethodForPath(requestPath string) string {
	for _, route := range apiRoutes {
		if route.pattern.MatchString(requestPath) {
			return route.method
		}
	}
	return ""
}

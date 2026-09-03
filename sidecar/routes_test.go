package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRouteAllowlist(t *testing.T) {
	gridID := "11111111-1111-4111-8111-111111111111"
	planID := "22222222-2222-4222-8222-222222222222"
	requestID := "33333333-3333-4333-8333-333333333333"
	allowed := []struct {
		method string
		path   string
	}{
		{http.MethodGet, "/api/v1/health"},
		{http.MethodGet, "/api/v1/identity"},
		{http.MethodPost, "/api/v1/identity/link-code"},
		{http.MethodGet, "/api/v1/grids"},
		{http.MethodGet, "/api/v1/grids/" + gridID + "/items?limit=20&craftable=true"},
		{http.MethodGet, "/api/v1/grids/" + gridID + "/cpus"},
		{http.MethodGet, "/api/v1/grids/" + gridID + "/metrics"},
		{http.MethodPost, "/api/v1/grids/" + gridID + "/plans"},
		{http.MethodGet, "/api/v1/plans/" + planID},
		{http.MethodPost, "/api/v1/plans/" + planID + "/submit"},
		{http.MethodGet, "/api/v1/requests/" + requestID},
		{http.MethodGet, "/api/v1/plans/01941f29-7c00-7000-8000-000000000001"},
	}
	for _, test := range allowed {
		t.Run(test.method+" "+test.path, func(t *testing.T) {
			request := httptest.NewRequest(test.method, test.path, nil)
			route, matched, _ := matchAPIRoute(request)
			if !matched {
				t.Fatal("allowlisted route did not match")
			}
			if !validRouteQuery(request.URL, route) {
				t.Fatal("allowlisted query was rejected")
			}
		})
	}

	rejected := []struct {
		method string
		path   string
	}{
		{http.MethodDelete, "/api/v1/grids"},
		{http.MethodGet, "/api/v1/admin"},
		{http.MethodGet, "/api/v1/grids/not-a-uuid/items"},
		{http.MethodGet, "/api/v1/grids/" + gridID + "/items/extra"},
		{http.MethodGet, "/api/v1/grids/" + gridID + "/items?admin=true"},
		{http.MethodGet, "/api/v1/grids/" + gridID + "/items?limit=1&limit=2"},
		{http.MethodGet, "/api/v1/grids/" + gridID + "/items?limit=5001"},
		{http.MethodGet, "/api/v1/grids/" + gridID + "/items?craftable=yes"},
		{http.MethodGet, "/api/v1/grids/" + gridID + "/items?limit=1;admin=true"},
		{http.MethodGet, "/api/v1/grids/" + gridID + "/items/../metrics"},
		{http.MethodGet, "/api/v1/grids/" + gridID + "%2fitems"},
	}
	for _, test := range rejected {
		t.Run(test.method+" "+test.path, func(t *testing.T) {
			request := httptest.NewRequest(test.method, test.path, nil)
			route, matched, _ := matchAPIRoute(request)
			if matched && validRouteQuery(request.URL, route) {
				t.Fatal("non-allowlisted request matched")
			}
		})
	}
}

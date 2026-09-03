package main

import (
	"net"
	"sync"
	"time"
)

const maxRateLimitClients = 10000

type tokenBucket struct {
	tokens   float64
	updated  time.Time
	lastSeen time.Time
}

type RateLimiter struct {
	mu       sync.Mutex
	rate     float64
	burst    float64
	clients  map[string]*tokenBucket
	now      func() time.Time
	lastReap time.Time
}

func NewRateLimiter(perMinute, burst int) *RateLimiter {
	return &RateLimiter{
		rate:    float64(perMinute) / 60,
		burst:   float64(burst),
		clients: make(map[string]*tokenBucket),
		now:     time.Now,
	}
}

func (l *RateLimiter) Allow(key string) bool {
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()

	if l.lastReap.IsZero() || now.Sub(l.lastReap) >= 10*time.Minute {
		for client, bucket := range l.clients {
			if now.Sub(bucket.lastSeen) > 15*time.Minute {
				delete(l.clients, client)
			}
		}
		l.lastReap = now
	}

	bucket := l.clients[key]
	if bucket == nil {
		if len(l.clients) >= maxRateLimitClients {
			return false
		}
		bucket = &tokenBucket{tokens: l.burst, updated: now}
		l.clients[key] = bucket
	}
	elapsed := now.Sub(bucket.updated).Seconds()
	if elapsed > 0 {
		bucket.tokens = min(l.burst, bucket.tokens+elapsed*l.rate)
		bucket.updated = now
	}
	bucket.lastSeen = now
	if bucket.tokens < 1 {
		return false
	}
	bucket.tokens--
	return true
}

func remoteIP(remoteAddress string) (string, bool) {
	host, _, err := net.SplitHostPort(remoteAddress)
	if err != nil {
		return "", false
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return "", false
	}
	return ip.String(), true
}

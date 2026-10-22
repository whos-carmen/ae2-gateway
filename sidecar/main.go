package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	config, err := LoadConfig()
	if err != nil {
		logger.Error("invalid configuration", "error", err)
		os.Exit(1)
	}

	var authenticator Authenticator
	mode := "cloudflare"
	switch {
	case config.TrustTailscale:
		authenticator = FixedAuthenticator{
			Subject: "tailscale:" + config.TailscaleIdentity,
			Email:   config.TailscaleIdentity,
		}
		mode = "tailscale-trusted"
	case config.DevIdentity != "":
		authenticator = DevAuthenticator{Email: config.DevIdentity}
		mode = "loopback-development"
	default:
		authenticator = NewCloudflareAuthenticator(
			config.CFIssuer,
			config.CFAudience,
			config.JWKSURL,
			nil,
		)
	}
	app, err := NewApp(config, authenticator, logger)
	if err != nil {
		logger.Error("initialize application", "error", err)
		os.Exit(1)
	}

	server := &http.Server{
		Addr:              config.ListenAddr,
		Handler:           app,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
	shutdownSignal, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	go func() {
		<-shutdownSignal.Done()
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(ctx); err != nil {
			logger.Error("graceful shutdown", "error", err)
		}
	}()

	logger.Info("starting sidecar", "address", config.ListenAddr, "mode", mode)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Error("server stopped", "error", err)
		os.Exit(1)
	}
}

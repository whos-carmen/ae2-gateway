package dev.ae2gateway;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

final class GatewayConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    String serviceSecret;
    int port = 8765;
    int maxQueuedTasks = 128;
    int maxTasksPerTick = 8;
    long tickBudgetMicros = 15_000;
    int snapshotIntervalTicks = 20;
    int maxSnapshotKeys = 50_000;
    long maxCraftAmount = 1_000_000_000L;
    long maxCraftBytes = 64L * 1024 * 1024;
    int planTtlSeconds = 120;
    int requestTimeoutSeconds = 10;
    int maxAssertionJtis = 4_096;
    int assertionClockSkewSeconds = 5;
    int maxActivePlans = 1_024;
    int planRetentionSeconds = 300;
    int maxRetainedRequests = 2_048;
    int requestRetentionSeconds = 600;

    static GatewayConfig load(Path configDirectory) throws IOException {
        Files.createDirectories(configDirectory);
        Path path = configDirectory.resolve("ae2-gateway.json");
        GatewayConfig config;
        if (Files.exists(path)) {
            config = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), GatewayConfig.class);
            if (config == null) {
                throw new IOException("Invalid gateway configuration");
            }
        } else {
            config = new GatewayConfig();
        }
        if (config.serviceSecret == null || config.serviceSecret.length() < 32) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            config.serviceSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        }
        config.validate();
        Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
        restrict(path);
        return config;
    }

    private void validate() throws IOException {
        if (port < 1 || port > 65535 || maxQueuedTasks < 1 || maxQueuedTasks > 4096
                || maxTasksPerTick < 1 || tickBudgetMicros < 100 || snapshotIntervalTicks < 1
                || maxSnapshotKeys < 1 || maxCraftAmount < 1 || maxCraftBytes < 1
                || planTtlSeconds < 10 || requestTimeoutSeconds < 1
                || maxAssertionJtis < 128 || maxAssertionJtis > 65_536
                || assertionClockSkewSeconds < 0 || assertionClockSkewSeconds > 15
                || maxActivePlans < 1 || maxActivePlans > 10_000
                || planRetentionSeconds < 10 || planRetentionSeconds > 86_400
                || maxRetainedRequests < 1 || maxRetainedRequests > 20_000
                || requestRetentionSeconds < planRetentionSeconds
                || requestRetentionSeconds > 86_400) {
            throw new IOException("Unsafe value in ae2-gateway.json");
        }
    }

    private static void restrict(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and non-POSIX filesystems do not expose POSIX permissions.
        }
    }
}

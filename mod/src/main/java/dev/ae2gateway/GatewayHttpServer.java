package dev.ae2gateway;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class GatewayHttpServer implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final int MAX_BODY = 8 * 1024;
    private final GatewayConfig config;
    private final GatewayCoordinator coordinator;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final IdentityAssertionVerifier assertionVerifier;

    GatewayHttpServer(GatewayConfig config, GatewayCoordinator coordinator) throws IOException {
        this.config = config;
        this.coordinator = coordinator;
        this.assertionVerifier = new IdentityAssertionVerifier(config.serviceSecret, config.maxAssertionJtis,
                config.assertionClockSkewSeconds);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.port), 32);
        this.executor = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
                runnable -> {
                    Thread thread = new Thread(runnable, "ae2-gateway-http");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/api/v1/", this::handle);
    }

    void start() {
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRemoteAddress().getAddress() == null
                    || !exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                throw new GatewayCoordinator.ClientException(403, "loopback_required", "Loopback connection required");
            }
            String suppliedSecret = singleHeader(exchange, "X-AE2-Mod-Secret");
            if (!CoreLogic.constantTimeEquals(config.serviceSecret, suppliedSecret)) {
                throw new GatewayCoordinator.ClientException(401, "unauthorized", "Invalid service credential");
            }
            route(exchange);
        } catch (IdentityAssertionVerifier.VerificationException exception) {
            error(exchange, exception.status, exception.code, "Identity assertion rejected");
        } catch (GatewayCoordinator.ClientException exception) {
            error(exchange, exception.status, exception.code, exception.getMessage());
        } catch (GatewayCoordinator.QueueFullException exception) {
            error(exchange, 503, "server_busy", "Minecraft server queue is full");
        } catch (java.util.concurrent.TimeoutException exception) {
            error(exchange, 503, "server_timeout", "Minecraft server did not respond in time");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof GatewayCoordinator.ClientException client) {
                error(exchange, client.status, client.code, client.getMessage());
            } else {
                error(exchange, 500, "internal_error", "Request failed safely");
            }
        } catch (JsonParseException | IllegalArgumentException exception) {
            error(exchange, 400, "invalid_request", "Request is invalid");
        } catch (Exception exception) {
            error(exchange, 500, "internal_error", "Request failed safely");
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && "/api/v1/health".equals(path)) {
            respond(exchange, 200, Map.of("status", "ok", "version", "0.1.0", "minecraftConnected", true));
            return;
        }
        GatewayCoordinator.Identity identity = identity(exchange, method, path);
        if ("GET".equals(method) && "/api/v1/identity".equals(path)) {
            respond(exchange, 200, coordinator.onServerThread(() -> coordinator.identityView(identity)));
            return;
        }
        if ("POST".equals(method) && "/api/v1/identity/link-code".equals(path)) {
            requireEmptyOrJsonBody(exchange);
            respond(exchange, 201, coordinator.onServerThread(() -> coordinator.createLinkCode(identity)));
            return;
        }
        if ("GET".equals(method) && "/api/v1/grids".equals(path)) {
            List<GatewayCoordinator.GridView> grids =
                    coordinator.onServerThread(() -> coordinator.listGrids(identity));
            respond(exchange, 200, Map.of("revision", gridsRevision(grids), "grids", grids));
            return;
        }

        String[] segments = path.substring("/api/v1/".length()).split("/");
        if (segments.length == 4 && "grids".equals(segments[0]) && "cpus".equals(segments[2])) {
            requireMethod(method, "GET");
            UUID gridId = parseUuid(segments[1]);
            String cpuId = URLDecoder.decode(segments[3], StandardCharsets.UTF_8);
            respond(exchange, 200, coordinator.onServerThread(
                    () -> coordinator.cpuDetail(identity, gridId, cpuId)));
            return;
        }
        if (segments.length == 3 && "grids".equals(segments[0])) {
            UUID gridId = parseUuid(segments[1]);
            switch (segments[2]) {
                case "items" -> {
                    requireMethod(method, "GET");
                    itemPage(exchange, identity, gridId);
                    return;
                }
                case "cpus" -> {
                    requireMethod(method, "GET");
                    GatewayCoordinator.GridSnapshot snapshot = coordinator.onServerThread(
                            () -> coordinator.requireSnapshot(identity, gridId,
                                    GatewaySavedData.Enrollment.READ));
                    respondRevision(exchange, 200, snapshot.revision(),
                            Map.of("revision", snapshot.revision(), "cpus", snapshot.cpus()));
                    return;
                }
                case "metrics" -> {
                    requireMethod(method, "GET");
                    GatewayCoordinator.GridSnapshot snapshot = coordinator.onServerThread(
                            () -> coordinator.requireSnapshot(identity, gridId,
                                    GatewaySavedData.Enrollment.READ));
                    respondRevision(exchange, 200, snapshot.revision(), snapshot.metrics());
                    return;
                }
                case "plans" -> {
                    requireMethod(method, "POST");
                    PlanBody body = readJson(exchange, PlanBody.class);
                    GatewayCoordinator.PlanView plan = coordinator.onServerThread(
                            () -> coordinator.createPlan(identity, gridId, body.keyToken, body.amount));
                    respond(exchange, 202, plan);
                    return;
                }
                default -> {
                }
            }
        }
        if (segments.length == 2 && "plans".equals(segments[0])) {
            requireMethod(method, "GET");
            UUID planId = parseUuid(segments[1]);
            respond(exchange, 200, coordinator.onServerThread(() -> coordinator.getPlan(identity, planId)));
            return;
        }
        if (segments.length == 3 && "plans".equals(segments[0]) && "submit".equals(segments[2])) {
            requireMethod(method, "POST");
            requireEmptyOrJsonBody(exchange);
            UUID planId = parseUuid(segments[1]);
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            respond(exchange, 202,
                    coordinator.onServerThread(() -> coordinator.submitPlan(identity, planId, key)));
            return;
        }
        if (segments.length == 2 && "requests".equals(segments[0])) {
            requireMethod(method, "GET");
            UUID requestId = parseUuid(segments[1]);
            respond(exchange, 200,
                    coordinator.onServerThread(() -> coordinator.getRequest(identity, requestId)));
            return;
        }
        throw new GatewayCoordinator.ClientException(404, "not_found", "Endpoint was not found");
    }

    private void itemPage(HttpExchange exchange, GatewayCoordinator.Identity identity, UUID gridId)
            throws Exception {
        GatewayCoordinator.GridSnapshot snapshot = coordinator.onServerThread(
                () -> coordinator.requireSnapshot(identity, gridId, GatewaySavedData.Enrollment.READ));
        Map<String, String> query = query(exchange.getRequestURI());
        int limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 200;
        if (limit < 1 || limit > 5_000) {
            throw new GatewayCoordinator.ClientException(400, "invalid_limit", "Limit must be 1-5000");
        }
        CoreLogic.Cursor cursor = CoreLogic.decodeCursor(config.serviceSecret, query.get("cursor"));
        if (cursor.revision() != -1 && cursor.revision() != snapshot.revision()) {
            throw new GatewayCoordinator.ClientException(409, "stale_cursor", "Inventory snapshot changed");
        }
        String text = query.getOrDefault("query", "").toLowerCase(java.util.Locale.ROOT);
        if (text.length() > 128) {
            throw new GatewayCoordinator.ClientException(400, "invalid_query", "Query is too long");
        }
        Boolean craftable = query.containsKey("craftable") ? parseBoolean(query.get("craftable")) : null;
        List<GatewayCoordinator.ItemView> filtered = new ArrayList<>();
        for (GatewayCoordinator.ItemView item : snapshot.items()) {
            if (!text.isEmpty() && !item.displayName().toLowerCase(java.util.Locale.ROOT).contains(text)
                    && !item.resourceId().toLowerCase(java.util.Locale.ROOT).contains(text)
                    && (item.namespace() == null
                            || !item.namespace().toLowerCase(java.util.Locale.ROOT).contains(text))) {
                continue;
            }
            if (craftable != null && craftable != item.craftable()) {
                continue;
            }
            filtered.add(item);
        }
        if (cursor.offset() > filtered.size()) {
            throw new GatewayCoordinator.ClientException(400, "invalid_cursor", "Cursor is out of range");
        }
        int end = Math.min(filtered.size(), cursor.offset() + limit);
        String next = end < filtered.size()
                ? CoreLogic.encodeCursor(config.serviceSecret, snapshot.revision(), end) : null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revision", snapshot.revision());
        body.put("nextCursor", next);
        body.put("items", List.copyOf(filtered.subList(cursor.offset(), end)));
        respondRevision(exchange, 200, snapshot.revision(), body);
    }

    private GatewayCoordinator.Identity identity(HttpExchange exchange, String method, String path)
            throws IdentityAssertionVerifier.VerificationException {
        return assertionVerifier.verify(
                singleHeader(exchange, "X-AE2-Identity-Assertion"),
                singleHeader(exchange, "X-Request-ID"),
                method,
                path);
    }

    private static String singleHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values != null && values.size() == 1 ? values.get(0) : null;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String part : raw.split("&")) {
            int equals = part.indexOf('=');
            String key = decode(equals < 0 ? part : part.substring(0, equals));
            String value = decode(equals < 0 ? "" : part.substring(equals + 1));
            if (result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate parameter");
            }
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("invalid boolean");
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new GatewayCoordinator.ClientException(400, "invalid_uuid", "UUID is invalid");
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new GatewayCoordinator.ClientException(405, "method_not_allowed", "Method is not allowed");
        }
    }

    private static void requireEmptyOrJsonBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) {
            throw new GatewayCoordinator.ClientException(413, "body_too_large", "Request body is too large");
        }
        if (bytes.length > 0) {
            GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
        }
    }

    private static <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) {
            throw new GatewayCoordinator.ClientException(413, "body_too_large", "Request body is too large");
        }
        if (bytes.length == 0) {
            throw new GatewayCoordinator.ClientException(400, "body_required", "JSON body is required");
        }
        T result = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
        if (result == null) {
            throw new GatewayCoordinator.ClientException(400, "invalid_json", "JSON body is invalid");
        }
        return result;
    }

    private static long gridsRevision(List<GatewayCoordinator.GridView> grids) {
        long result = 1;
        for (GatewayCoordinator.GridView grid : grids) {
            result = 31 * result + grid.hashCode();
        }
        return result & Long.MAX_VALUE;
    }

    private static void respondRevision(HttpExchange exchange, int status, long revision, Object value)
            throws IOException {
        exchange.getResponseHeaders().set("ETag", "\"" + revision + "\"");
        respond(exchange, status, value);
    }

    private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        respond(exchange, status, Map.of("error", error));
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdownNow();
    }

    private static final class PlanBody {
        String keyToken;
        long amount;
    }
}

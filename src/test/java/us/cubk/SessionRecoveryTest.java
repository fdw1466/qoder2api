package us.cubk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SessionRecoveryTest {
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final AtomicInteger logins = new AtomicInteger();
    private final PatPool pool = new PatPool(List.of("test-pat"), (pat, mid, token, type) -> {
        var jt = BearerApiClient.objectMapper.createObjectNode();
        jt.put("id", "test-user");
        jt.put("securityOauthToken", "test-token-" + logins.incrementAndGet());
        return jt;
    }, now::get);

    private static final String SUCCESS = "data: {\"body\":\"ok\"}\n\n";

    @ParameterizedTest
    @ValueSource(strings = {"http401", "http403", "sse401", "sse401Object", "sseStatus", "sse403", "partialLine401"})
    void rejectedSessionIsRenewedAndRequestIsRetried(String mode) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> keys = new ArrayList<>();
        List<String> machines = new ArrayList<>();
        try (Upstream upstream = new Upstream(ex -> {
            keys.add(ex.getRequestHeaders().getFirst("cosy-key"));
            machines.add(ex.getRequestHeaders().getFirst("cosy-machineid"));
            if (requests.incrementAndGet() == 1) reject(ex, mode);
            else respond(ex, 200, SUCCESS);
        })) {
            List<String> lines = new ArrayList<>();
            pool.openStreamLines(pool.acquire(), upstream.url(), Map.of("prompt", "hello"), Map.of(), lines::add);
            assertEquals(List.of(SUCCESS.trim()), lines);
            assertEquals(2, requests.get());
            assertEquals(2, logins.get());
            assertNotEquals(keys.get(0), keys.get(1));
            assertEquals(machines.get(0), machines.get(1));
        }
    }

    @Test
    void retriesOnlyOnceAndRejectedNewSessionCanRecoverLater() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Upstream upstream = new Upstream(ex -> {
            requests.incrementAndGet();
            respond(ex, 401, "unauthorized");
        })) {
            assertThrows(BearerApiClient.UpstreamAuthenticationException.class,
                    () -> pool.openStreamLines(pool.acquire(), upstream.url(), Map.of(), Map.of(), line -> {}));
            assertEquals(2, requests.get());
            assertEquals(2, logins.get());
            assertNull(pool.acquire());
            now.addAndGet(30_000);
            assertNotNull(pool.acquire());
            assertEquals(3, logins.get());
        }
    }

    @Test
    void staleConcurrentRejectionReusesAlreadyRenewedSession() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        PatPool.Acquired original = pool.acquire();
        try (Upstream upstream = new Upstream(ex -> {
            requests.incrementAndGet();
            if (original.session().cosyKey().equals(ex.getRequestHeaders().getFirst("cosy-key"))) {
                respond(ex, 401, "unauthorized");
            } else respond(ex, 200, SUCCESS);
        })) {
            pool.openStreamLines(original, upstream.url(), Map.of(), Map.of(), line -> {});
            pool.openStreamLines(original, upstream.url(), Map.of(), Map.of(), line -> {});
            assertEquals(4, requests.get());
            assertEquals(2, logins.get());
        }
    }

    @Test
    void authFailureAfterDataDoesNotReplayPartialOutput() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Upstream upstream = new Upstream(ex -> {
            requests.incrementAndGet();
            respond(ex, 200, SUCCESS + "data: {\"statusCodeValue\":401,\"body\":\"expired\"}\n\n");
        })) {
            List<String> lines = new ArrayList<>();
            assertThrows(BearerApiClient.UpstreamAuthenticationException.class,
                    () -> pool.openStreamLines(pool.acquire(), upstream.url(), Map.of(), Map.of(), lines::add));
            assertEquals(List.of(SUCCESS.trim()), lines);
            assertEquals(1, requests.get());
            assertEquals(1, logins.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http403", "sse403", "sse429", "sseObject"})
    void quotaFailureRemainsRateLimitWithoutReauthentication(String mode) throws Exception {
        String body = "{\"code\":115,\"message\":\"{\\\"agentLimitResetTime\\\":1800000060000}\"}";
        AtomicInteger requests = new AtomicInteger();
        try (Upstream upstream = new Upstream(ex -> {
            requests.incrementAndGet();
            if (mode.equals("http403")) respond(ex, 403, body);
            else {
                var wrapper = BearerApiClient.objectMapper.createObjectNode();
                wrapper.put("statusCodeValue", mode.equals("sse429") ? 429 : 403);
                if (mode.equals("sseObject")) wrapper.set("body", BearerApiClient.objectMapper.readTree(body));
                else wrapper.put("body", body);
                respond(ex, 200, "data: " + wrapper + "\n\n");
            }
        })) {
            var error = assertThrows(BearerApiClient.UpstreamRateLimitException.class,
                    () -> pool.openStreamLines(pool.acquire(), upstream.url(), Map.of(), Map.of(), line -> {}));
            assertEquals(1_800_000_060_000L, error.resetTimeMs);
            assertEquals(1, requests.get());
            assertEquals(1, logins.get());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 500})
    void permissionAndServerErrorsDoNotTriggerLogin(int status) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Upstream upstream = new Upstream(ex -> {
            requests.incrementAndGet();
            respond(ex, status, "{\"message\":\"model access denied\"}");
        })) {
            Exception error = assertThrows(Exception.class,
                    () -> pool.openStreamLines(pool.acquire(), upstream.url(), Map.of(), Map.of(), line -> {}));
            assertFalse(error instanceof BearerApiClient.UpstreamAuthenticationException);
            assertEquals(1, requests.get());
            assertEquals(1, logins.get());
        }
    }

    private static void reject(HttpExchange ex, String mode) throws IOException {
        if (mode.equals("http401")) respond(ex, 401, "unauthorized");
        else if (mode.equals("http403")) respond(ex, 403, "{\"message\":\"token expired\"}");
        else {
            var wrapper = BearerApiClient.objectMapper.createObjectNode();
            if (mode.equals("sseStatus")) wrapper.put("statusCode", "UNAUTHORIZED");
            else wrapper.put("statusCodeValue", mode.equals("sse403") ? 403 : 401);
            var body = BearerApiClient.objectMapper.createObjectNode().put("message", "token expired");
            if (mode.equals("sse401Object")) wrapper.set("body", body);
            else wrapper.put("body", body.toString());
            respond(ex, 200, "data: " + wrapper + (mode.equals("partialLine401") ? "" : "\n\n"));
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class Upstream implements AutoCloseable {
        private final HttpServer server;

        Upstream(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat", handler);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

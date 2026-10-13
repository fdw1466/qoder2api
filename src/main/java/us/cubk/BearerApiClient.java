package us.cubk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class BearerApiClient {
    public static final ObjectMapper objectMapper = new ObjectMapper();
    private static final long DOWNSTREAM_HEARTBEAT_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    private final BearerBuilder.SessionContext sess;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).build();

    public BearerApiClient(BearerBuilder.SessionContext sess) { this.sess = sess; }

    public JsonNode callPost(String fullUrl, Object jsonBody) throws Exception {
        return call("POST", fullUrl, jsonBody, null);
    }

    public JsonNode callGet(String fullUrl) throws Exception {
        return call("GET", fullUrl, null, null);
    }
    public HttpResponse<java.io.InputStream> openStream(String fullUrl, Object jsonBody, Map<String, String> extraHeaders) throws Exception {
        return openCallStream(fullUrl, jsonBody, extraHeaders);
    }

    public void openStreamLines(String fullUrl, Object jsonBody, Map<String, String> extraHeaders, java.util.function.Consumer<String> onLine) throws Exception {
        openStreamLines(fullUrl, jsonBody, extraHeaders, onLine, null);
    }

    public void openStreamLines(String fullUrl, Object jsonBody, Map<String, String> extraHeaders,
                                java.util.function.Consumer<String> onLine, Runnable onHeartbeat) throws Exception {
        URI u = URI.create(fullUrl);
        String pathQuery = u.getRawPath();
        String pathSig = pathQuery.startsWith("/algo") ? pathQuery.substring("/algo".length()) : pathQuery;
        String body = jsonBody == null ? "" : QoderEncoding.encode(objectMapper.writeValueAsBytes(jsonBody));
        String payloadB64 = BearerBuilder.buildPayloadB64(sess.info());
        String date = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = BearerBuilder.signRequest(payloadB64, sess.cosyKey(), date, body, pathSig);
        String bearer = BearerBuilder.composeBearer(payloadB64, sig);
        org.apache.hc.client5.http.classic.methods.HttpPost post = new org.apache.hc.client5.http.classic.methods.HttpPost(fullUrl);
        post.setHeader("cosy-data-policy", "AGREE");
        post.setHeader("content-type", "application/json");
        post.setHeader("cosy-machinetype", sess.machineType());
        post.setHeader("cosy-clienttype", "5");
        post.setHeader("cosy-date", date);
        post.setHeader("cosy-user", sess.identity().uid());
        post.setHeader("cosy-key", sess.cosyKey());
        post.setHeader("cache-control", "no-cache");
        post.setHeader("accept", "text/event-stream");
        post.setHeader("cosy-clientip", "169.254.198.161");
        post.setHeader("authorization", bearer);
        post.setHeader("accept-encoding", "identity");
        post.setHeader("cosy-version", "0.1.43");
        post.setHeader("cosy-machineid", sess.machineId());
        post.setHeader("cosy-machinetoken", sess.machineToken());
        post.setHeader("login-version", "v2");
        post.setHeader("user-agent", "Go-http-client/2.0");
        if (extraHeaders != null) extraHeaders.forEach(post::setHeader);
        post.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(body));
        try (org.apache.hc.client5.http.impl.classic.CloseableHttpClient client = org.apache.hc.client5.http.impl.classic.HttpClients.custom().setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom().setConnectTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(15)).setResponseTimeout(org.apache.hc.core5.util.Timeout.ofMinutes(5)).build()).useSystemProperties().build()) {
            client.execute(post, (org.apache.hc.core5.http.io.HttpClientResponseHandler<Void>) response -> {
                try {
                    return execHandler(response, onLine, onHeartbeat);
                } catch (org.apache.hc.core5.http.ConnectionClosedException cce) {
                    // Upstream closed a chunked response early; log headers for debugging.
                    System.err.println("[stream] upstream closed connection early: " + cce.getMessage());
                    try {
                        System.err.println("[stream] upstream status=" + response.getCode());
                        for (org.apache.hc.core5.http.Header h : response.getHeaders()) {
                            String name = h.getName();
                            if (name == null) continue;
                            String lname = name.toLowerCase();
                            if (lname.contains("authorization") || lname.contains("cookie") || lname.contains("token")) continue;
                            System.err.println("[stream] hdr " + name + ": " + h.getValue());
                        }
                    } catch (Exception ignore) {
                    }
                    throw cce;
                } catch (UpstreamRateLimitException | UpstreamAuthenticationException upstream) {
                    throw upstream;
                } catch (java.io.IOException ioe) {
                    throw ioe;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(ie);
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
            });
        }
    }

    public static final class UpstreamRateLimitException extends RuntimeException {
        public final long resetTimeMs;
        public final String upstreamCode;
        public final int upstreamHttpStatus;

        public UpstreamRateLimitException(String message, long resetTimeMs, String upstreamCode, int upstreamHttpStatus) {
            super(message);
            this.resetTimeMs = resetTimeMs;
            this.upstreamCode = upstreamCode;
            this.upstreamHttpStatus = upstreamHttpStatus;
        }
    }

    public static final class UpstreamAuthenticationException extends RuntimeException {
        public UpstreamAuthenticationException(int status) {
            super("Upstream session authentication failed (HTTP " + status + ")");
        }
    }

    private Void execHandler(org.apache.hc.core5.http.ClassicHttpResponse response,
                             java.util.function.Consumer<String> onLine, Runnable onHeartbeat) throws Exception {
                System.out.println("[stream] upstream status=" + response.getCode() + " reason=" + response.getReasonPhrase());
                // headers and body are debug-level to avoid noisy logs
                for (org.apache.hc.core5.http.Header h : response.getHeaders()) {
                    String name = h.getName();
                    if (name == null) continue;
                    String lname = name.toLowerCase();
                    if (lname.contains("authorization") || lname.contains("cookie") || lname.contains("token")) continue;
                    if (Config.debug()) {
                        System.out.println("[stream][debug] upstream hdr " + name + ": " + h.getValue());
                    }
                }

                if (response.getCode() != 200) {
                    String errBody = org.apache.hc.core5.http.io.entity.EntityUtils.toString(response.getEntity());
                    if (Config.debug()) {
                        System.out.println("[stream][debug] upstream error body=" + errBody);
                    }
                    throwUpstreamError(response.getCode(), errBody);
                    throw new RuntimeException("HTTP " + response.getCode() + " " + errBody);
                }

                java.io.InputStream is = response.getEntity().getContent();
                java.util.concurrent.BlockingQueue<Integer> queue = new java.util.concurrent.LinkedBlockingQueue<>();
                final int SENTINEL_EOF = -1;
                Thread reader = new Thread(() -> {
                    try {
                        int b;
                        while ((b = is.read()) != -1) queue.put(b);
                        queue.put(SENTINEL_EOF);
                    } catch (Exception e) {
                        System.err.println("[stream] reader error: " + e);
                        try { queue.put(SENTINEL_EOF); } catch (InterruptedException ignore) {}
                    }
                }, "sse-reader");
                reader.setDaemon(true);
                reader.start();
                java.io.ByteArrayOutputStream lineBuf = new java.io.ByteArrayOutputStream();
                long nextHeartbeatAt = System.nanoTime() + DOWNSTREAM_HEARTBEAT_INTERVAL_NANOS;
                try {
                    while (true) {
                        Integer b = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (onHeartbeat != null && System.nanoTime() >= nextHeartbeatAt) {
                            onHeartbeat.run();
                            nextHeartbeatAt = System.nanoTime() + DOWNSTREAM_HEARTBEAT_INTERVAL_NANOS;
                        }
                        if (b == null) {
                            // The upstream SSE can be quiet for long stretches; do not treat silence as completion.
                            // We only stop when we see EOF or the downstream heartbeat detects a disconnect.
                            continue;
                        }
                        if (b == SENTINEL_EOF) break;
                        if (b == '\n') {
                            String line = lineBuf.toString(java.nio.charset.StandardCharsets.UTF_8);
                            lineBuf.reset();
                            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                            if (!line.isEmpty()) {
                                if (Config.debug()) {
                                    System.out.println("[stream][debug] upstream line=" + line);
                                }
                                maybeThrowUpstreamError(line);
                                onLine.accept(line);
                            }
                        } else {
                            lineBuf.write(b);
                        }
                    }
                    // flush any last partial line
                    String last = lineBuf.toString(java.nio.charset.StandardCharsets.UTF_8);
                    if (!last.isEmpty()) {
                        if (last.endsWith("\r")) last = last.substring(0, last.length() - 1);
                        if (!last.isEmpty()) {
                            if (Config.debug()) {
                                System.out.println("[stream][debug] upstream line=" + last);
                            }
                            maybeThrowUpstreamError(last);
                            onLine.accept(last);
                        }
                    }
                } finally {
                    try { is.close(); } catch (Exception ignore) {}
                    reader.interrupt();
                }
                if (Config.debug()) {
                    System.out.println("[stream][debug] read complete");
                }
                return null;
    }

    private void maybeThrowUpstreamError(String line) {
        if (line == null) return;
        String trimmed = line.trim();
        if (!trimmed.startsWith("data:")) return;
        String payload = trimmed.substring(5).trim();

        try {
            JsonNode wrapper = objectMapper.readTree(payload);
            int statusCode = wrapper.path("statusCodeValue").asInt(0);
            String status = wrapper.path("statusCode").asText("");
            if (statusCode == 0) {
                if ("UNAUTHORIZED".equals(status)) statusCode = 401;
                else if ("FORBIDDEN".equals(status)) statusCode = 403;
                else if ("TOO_MANY_REQUESTS".equals(status)) statusCode = 429;
            }
            if (statusCode == 0 && status.isBlank()) {
                return;
            }

            // Some upstreams tunnel an inner HTTP response inside SSE data.
            JsonNode body = wrapper.path("body");
            throwUpstreamError(statusCode, body.isTextual() ? body.asText() : body.toString());
            if (statusCode >= 400) {
                throw new IllegalStateException("Upstream SSE error (HTTP " + statusCode + ")");
            }
        } catch (UpstreamRateLimitException | UpstreamAuthenticationException | IllegalStateException e) {
            throw e;
        } catch (Exception ignore) {
            // ignore parse errors; it's fine for normal SSE deltas
        }
    }

    private static void throwUpstreamError(int statusCode, String body) {
        JsonNode json = null;
        try {
            json = objectMapper.readTree(body);
        } catch (Exception ignore) {
        }
        String code = json == null ? "" : json.path("code").asText("");
        String message = json == null ? body : json.path("message").asText(body);
        if ((statusCode == 403 || statusCode == 429) && "115".equals(code)) {
            long resetMs = 0;
            try {
                resetMs = objectMapper.readTree(message).path("agentLimitResetTime").asLong(0);
            } catch (Exception ignore) {
            }
            throw new UpstreamRateLimitException("Upstream rate-limited (code=115)", resetMs, code, statusCode);
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        boolean authMessage = lower.contains("unauthorized") || lower.contains("authentication")
                || lower.contains("login") || lower.contains("登录") || lower.contains("令牌")
                || (lower.contains("token") && (lower.contains("expir") || lower.contains("invalid")));
        if (statusCode == 401 || (statusCode == 403 && authMessage)) {
            throw new UpstreamAuthenticationException(statusCode);
        }
    }

    private JsonNode call(String method, String fullUrl, Object jsonBody, Map<String,String> extraHeaders) throws Exception {
        URI u = URI.create(fullUrl);
        String pathQuery = u.getRawPath();
        String pathSig = pathQuery.startsWith("/algo") ? pathQuery.substring("/algo".length()) : pathQuery;

        String body = "";
        if (jsonBody != null) {
            byte[] plain = objectMapper.writeValueAsBytes(jsonBody);
            body = QoderEncoding.encode(plain);
        }

        String payloadB64 = BearerBuilder.buildPayloadB64(sess.info());
        String date = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = BearerBuilder.signRequest(payloadB64, sess.cosyKey(), date, body, pathSig);
        String bearer = BearerBuilder.composeBearer(payloadB64, sig);

        HttpRequest.Builder b = HttpRequest.newBuilder(u)
                .timeout(Duration.ofSeconds(30))
                .header("cosy-data-policy", "AGREE")
                .header("content-type", "application/json")
                .header("cosy-machinetype", sess.machineType())
                .header("cosy-clienttype", "5")
                .header("cosy-date", date)
                .header("cosy-user", sess.identity().uid())
                .header("cosy-key", sess.cosyKey())
                .header("accept", "application/json")
                .header("cosy-clientip", "169.254.198.161")
                .header("authorization", bearer)
                .header("accept-encoding", "identity")
                .header("cosy-version", "0.1.43")
                .header("cosy-machineid", sess.machineId())
                .header("cosy-machinetoken", sess.machineToken())
                .header("login-version", "v2")
                .header("user-agent", "Go-http-client/2.0");
        if (extraHeaders != null) extraHeaders.forEach(b::header);
        if ("POST".equals(method)) {
            b.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.GET();
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " body=" + resp.body());
        }
        return objectMapper.readTree(resp.body());
    }

    private HttpResponse<java.io.InputStream> openCallStream(String fullUrl, Object jsonBody,
                                                             Map<String,String> extraHeaders) throws Exception {
        URI u = URI.create(fullUrl);
        String pathQuery = u.getRawPath();
        String pathSig = pathQuery.startsWith("/algo") ? pathQuery.substring("/algo".length()) : pathQuery;
        String body = jsonBody == null ? "" : QoderEncoding.encode(objectMapper.writeValueAsBytes(jsonBody));
        String payloadB64 = BearerBuilder.buildPayloadB64(sess.info());
        String date = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = BearerBuilder.signRequest(payloadB64, sess.cosyKey(), date, body, pathSig);
        String bearer = BearerBuilder.composeBearer(payloadB64, sig);

        HttpRequest.Builder b = HttpRequest.newBuilder(u)
                .timeout(Duration.ofMinutes(5))
                .header("cosy-data-policy", "AGREE")
                .header("content-type", "application/json")
                .header("cosy-machinetype", sess.machineType())
                .header("cosy-clienttype", "5")
                .header("cosy-date", date)
                .header("cosy-user", sess.identity().uid())
                .header("cosy-key", sess.cosyKey())
                .header("cache-control", "no-cache")
                .header("accept", "text/event-stream")
                .header("cosy-clientip", "169.254.198.161")
                .header("authorization", bearer)
                .header("accept-encoding", "identity")
                .header("cosy-version", "0.1.43")
                .header("cosy-machineid", sess.machineId())
                .header("cosy-machinetoken", sess.machineToken())
                .header("login-version", "v2")
                .header("user-agent", "Go-http-client/2.0");
        if (extraHeaders != null) extraHeaders.forEach(b::header);
        b.POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<java.io.InputStream> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            byte[] err = resp.body().readAllBytes();
            throw new RuntimeException("HTTP " + resp.statusCode() + " body=" + new String(err));
        }
        return resp;
    }
}

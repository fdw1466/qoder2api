package us.cubk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OpenAiBridge {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String MODELS_FILE = "models.json";

    private final PatPool pool;
    private final JsonNode templateBase;

    public OpenAiBridge(List<String> pats, String basePromptFile) throws Exception {
        this.pool = new PatPool(pats);
        System.out.println("[bridge] PAT pool size=" + pool.size());
        String basePromptFilePath = (basePromptFile != null && !basePromptFile.isBlank()) ? basePromptFile : "baseprompt.json";
        String basePrompt = new String(java.nio.file.Files.readAllBytes(new File(basePromptFilePath).toPath()));
        basePrompt = basePrompt.replace("{UUID1}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID2}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID3}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID4}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID5}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{TIME1}", String.valueOf(System.currentTimeMillis()));
        this.templateBase = objectMapper.readTree(basePrompt);
    }

    public void start(String host, int port) throws Exception {
        String bindHost = (host == null || host.isBlank()) ? "127.0.0.1" : host;
        HttpServer server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        server.createContext("/v1/chat/completions", this::handleChat);
        server.createContext("/v1/responses", this::handleResponses);
        server.createContext("/v1/models", this::handleModels);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("[bridge] listening http://" + bindHost + ":" + port + "/v1/chat/completions");
    }

    /**
     * Validate the request's Bearer token against {@code app.api_keys}. Returns true if access is allowed
     * (either no keys are configured → open access for local use, or the token matches). On failure writes
     * a 401 response and returns false. Per OpenAI convention the token is read from
     * {@code Authorization: Bearer <key>}; {@code x-api-key} is also accepted as a convenience.
     */
    private boolean checkAuth(HttpExchange ex) throws IOException {
        List<String> keys = Config.app().apiKeys();
        if (keys == null || keys.isEmpty()) {
            return true; // no keys configured → open access (backwards compatible)
        }
        String presented = null;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            presented = auth.substring("Bearer ".length()).trim();
        }
        if (presented == null || presented.isEmpty()) {
            presented = ex.getRequestHeaders().getFirst("x-api-key");
        }
        if (presented != null && keys.contains(presented)) {
            return true;
        }
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode err = objectMapper.createObjectNode();
        err.put("message", "Invalid API key. Set Authorization: Bearer <key> to match an entry in app.api_keys.");
        err.put("type", "invalid_request_error");
        root.set("error", err);
        byte[] out = objectMapper.writeValueAsBytes(root);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("WWW-Authenticate", "Bearer");
        ex.sendResponseHeaders(401, out.length);
        ex.getResponseBody().write(out);
        return false;
    }

    private void handleChat(HttpExchange ex) throws IOException {
        PatPool.Acquired acquired = null;
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (!checkAuth(ex)) return;
            JsonNode req = objectMapper.readTree(ex.getRequestBody());
            boolean stream = req.path("stream").asBoolean(false);
            String model = req.path("model").asText(defaultModelKey());
            JsonNode messages = req.path("messages");

            // basic request info
            System.out.println("[bridge] /v1/chat/completions model=" + model + " stream=" + stream + " messages=" + (messages != null && messages.isArray() ? messages.size() : 0));
            debugIncomingMessages(messages);

            // Accept any model key provided by client.

            // Try messages first; if empty fall back to input[] (Responses API style)
            String prompt = extractLatestUserPrompt(messages);
            if (prompt.isBlank()) {
                prompt = extractLatestInputText(req.path("input"));
            }

            if (prompt.isBlank()) {
                writeJsonError(ex, 400, "No user prompt found in request messages. Ensure you send a role=user message with content.");
                return;
            }

            acquired = pool.acquire();
            if (acquired == null) {
                // Every PAT is cooling down or failed.
                long reset = pool.earliestResetMs();
                ObjectNode root = objectMapper.createObjectNode();
                ObjectNode err = objectMapper.createObjectNode();
                err.put("message", "All PATs in the pool are currently rate-limited. Please retry shortly.");
                err.put("type", "rate_limit_error");
                if (reset > 0) {
                    err.put("reset_time_ms", reset);
                    err.put("reset_time_epoch_seconds", reset / 1000);
                }
                root.set("error", err);
                byte[] out = objectMapper.writeValueAsBytes(root);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(429, out.length);
                ex.getResponseBody().write(out);
                return;
            }
            BearerBuilder.SessionContext sess = acquired.session();
            BearerApiClient bearerClient = acquired.client();

            ObjectNode body = templateBase.deepCopy();
            String nid = UUID.randomUUID().toString();
            body.put("request_id", nid);
            body.put("chat_record_id", nid);
            body.put("request_set_id", UUID.randomUUID().toString());
            body.put("session_id", UUID.randomUUID().toString());
            body.put("stream", stream);
            body.put("aliyun_user_type", sess.identity().userType());
            ObjectNode mc = (ObjectNode) body.path("model_config");
            mc.put("key", model);
            ObjectNode biz = (ObjectNode) body.path("business");
            biz.put("id", UUID.randomUUID().toString());
            biz.put("begin_at", System.currentTimeMillis());
            ObjectNode ctx = (ObjectNode) body.path("chat_context");
            ((ObjectNode) ctx.path("text")).put("text", prompt);
            ((ObjectNode) ctx.path("extra").path("originalContent")).put("text", prompt);
            biz.put("name", prompt.length() > 30 ? prompt.substring(0, 30) : prompt);

            JsonNode incomingTools = req.path("tools");
            boolean toolsEnabled = incomingTools.isArray() && incomingTools.size() > 0;
            body.set("messages", buildQoderMessages((ArrayNode) body.path("messages"), messages, prompt, toolsEnabled));
            if (toolsEnabled) {
                body.set("tools", incomingTools.deepCopy());
            }

            if (Config.debug()) {
                System.out.println("[bridge] prompt=" + (prompt.length() > 80 ? prompt.substring(0, 80) + "..." : prompt));
                for (JsonNode msg : body.path("messages")) {
                    String content = msg.path("content").asText();
                    String contentsStr = msg.path("contents").toString();
                    System.out.println("[bridge][debug] msg role=" + msg.path("role").asText() + " content=" + (content.length() > 40 ? content.substring(0, 40) + "..." : content) + " contents=" + (contentsStr.length() > 120 ? contentsStr.substring(0, 120) + "..." : contentsStr));
                }
            }

            String url = "https://api3.qoder.sh/algo/api/v2/service/pro/sse/agent_chat_generation" + "?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1";
            Map<String, String> extraHeaders = Map.of("x-model-key", model, "x-model-source", mc.path("source").asText("system"));
            System.out.println("[bridge] >>> upstream url=" + url);

            String reqId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            long created = System.currentTimeMillis() / 1000;

            if (stream) {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.getResponseHeaders().add("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();
                StreamAccumulator accumulator = new StreamAccumulator(out, reqId, created, model, toolsEnabled);
                int[] dataLineCount = {0};
                bearerClient.openStreamLines(url, body, extraHeaders, line -> {
                    dataLineCount[0]++;
                    if (!line.startsWith("data:")) return;
                    BridgeDelta delta = extractDelta(line.substring(5).trim());
                    if (delta.isEmpty()) return;
                    try {
                        accumulator.accept(delta);
                    } catch (IOException ie) {
                        throw new java.io.UncheckedIOException(ie);
                    }
                }, () -> {
                    try {
                        out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (IOException ie) {
                        throw new java.io.UncheckedIOException(ie);
                    }
                });
                if (Config.debug()) {
                    System.out.println("[bridge][debug] <<< upstream data lines=" + dataLineCount[0] + " (stream=true)");
                }
                accumulator.flush();
                ObjectNode done = makeChunk(reqId, created, model);
                ((ObjectNode) done.path("choices").get(0)).put("finish_reason", accumulator.finishReason());
                ((ObjectNode) done.path("choices").get(0)).set("delta", objectMapper.createObjectNode());
                out.write(("data: " + objectMapper.writeValueAsString(done) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.close();
            } else {
                StringBuilder full = new StringBuilder();
                StringBuilder reasoning = new StringBuilder();
                ToolCallAccumulator toolCalls = new ToolCallAccumulator();
                int[] dataLineCount = {0};
                bearerClient.openStreamLines(url, body, extraHeaders, line -> {
                    dataLineCount[0]++;
                    if (!line.startsWith("data:")) return;
                    BridgeDelta delta = extractDelta(line.substring(5).trim());
                    if (delta.content() != null) {
                        full.append(delta.content());
                    }
                    if (delta.reasoningContent() != null) {
                        reasoning.append(delta.reasoningContent());
                    }
                    if (delta.toolCalls() != null && delta.toolCalls().size() > 0) {
                        toolCalls.append(delta.toolCalls());
                    }
                });
                if (Config.debug()) {
                    System.out.println("[bridge][debug] <<< upstream data lines=" + dataLineCount[0] + ", full.len=" + full.length());
                }
                ArrayNode fallbackToolCalls = null;
                if (toolCalls.isEmpty() && toolsEnabled) {
                    fallbackToolCalls = parseToolCallsText(full.toString());
                }
                ObjectNode out = objectMapper.createObjectNode();
                out.put("id", reqId);
                out.put("object", "chat.completion");
                out.put("created", created);
                out.put("model", model);
                ArrayNode choices = objectMapper.createArrayNode();
                ObjectNode ch = objectMapper.createObjectNode();
                ch.put("index", 0);
                ObjectNode msg = objectMapper.createObjectNode();
                msg.put("role", "assistant");
                if (fallbackToolCalls != null) {
                    msg.putNull("content");
                    msg.set("tool_calls", fallbackToolCalls);
                } else if (full.isEmpty() && !toolCalls.isEmpty()) {
                    msg.putNull("content");
                } else {
                    msg.put("content", full.toString());
                }
                if (reasoning.length() > 0) {
                    msg.put("reasoning_content", reasoning.toString());
                }
                if (!toolCalls.isEmpty()) {
                    msg.set("tool_calls", toolCalls.snapshot());
                }
                ch.set("message", msg);
                ch.put("finish_reason", (!toolCalls.isEmpty() || fallbackToolCalls != null) ? "tool_calls" : "stop");
                choices.add(ch);
                out.set("choices", choices);
                ObjectNode usage = objectMapper.createObjectNode();
                usage.put("prompt_tokens", 0);
                usage.put("completion_tokens", 0);
                usage.put("total_tokens", 0);
                out.set("usage", usage);
                byte[] outBytes = objectMapper.writeValueAsBytes(out);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, outBytes.length);
                ex.getResponseBody().write(outBytes);
            }
        } catch (BearerApiClient.UpstreamRateLimitException rle) {
            if (acquired != null) {
                pool.markRateLimited(acquired.pat(), rle.resetTimeMs);
            }
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode err = objectMapper.createObjectNode();
            err.put("message", rle.getMessage());
            err.put("type", "rate_limit_error");
            if (rle.resetTimeMs > 0) {
                err.put("reset_time_ms", rle.resetTimeMs);
                err.put("reset_time_epoch_seconds", rle.resetTimeMs / 1000);
            }
            root.set("error", err);
            byte[] out = objectMapper.writeValueAsBytes(root);
            try {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(429, out.length);
                ex.getResponseBody().write(out);
            } catch (IOException ignore) {
            }
        } catch (Exception e) {
            String err = "{\"error\":{\"message\":\"" + e.getMessage().replace("\"", "\\\"") + "\",\"type\":\"qoder_error\"}}";
            byte[] errBytes = err.getBytes(StandardCharsets.UTF_8);
            try {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(500, errBytes.length);
                ex.getResponseBody().write(errBytes);
            } catch (IOException ignore) {
            }
        } finally {
            ex.close();
        }
    }

    private void handleResponses(HttpExchange ex) throws IOException {
        PatPool.Acquired acquired = null;
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (!checkAuth(ex)) return;
            JsonNode req = objectMapper.readTree(ex.getRequestBody());

            String model = req.path("model").asText("lite");
            boolean stream = req.path("stream").asBoolean(false);

            // basic request info
            System.out.println("[bridge] /v1/responses model=" + model + " stream=" + stream);
            if (Config.debug()) {
                System.out.println("[bridge][debug] responses incoming=" + abbreviateJson(req, 4000));
            }
            if (stream) {
                writeJsonError(ex, 400, "stream=true is not supported on /v1/responses yet. Please disable streaming.");
                return;
            }

            String prompt = extractLatestInputText(req.path("input"));
            if (prompt.isBlank()) {
                writeJsonError(ex, 400, "No input text found. Expected input[].content[].text");
                return;
            }

            acquired = pool.acquire();
            if (acquired == null) {
                long reset = pool.earliestResetMs();
                ObjectNode root = objectMapper.createObjectNode();
                ObjectNode err = objectMapper.createObjectNode();
                err.put("message", "All PATs in the pool are currently rate-limited. Please retry shortly.");
                err.put("type", "rate_limit_error");
                if (reset > 0) {
                    err.put("reset_time_ms", reset);
                    err.put("reset_time_epoch_seconds", reset / 1000);
                }
                root.set("error", err);
                writeJson(ex, 429, root);
                return;
            }

            String reply = callUpstreamFullText(prompt, model, acquired.session(), acquired.client());

            ObjectNode out = objectMapper.createObjectNode();
            out.put("id", "resp_" + UUID.randomUUID().toString().replace("-", ""));
            out.put("object", "response");
            out.put("created_at", System.currentTimeMillis() / 1000);
            out.put("model", model);

            ArrayNode output = objectMapper.createArrayNode();
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("id", "msg_" + UUID.randomUUID().toString().replace("-", ""));
            msg.put("type", "message");
            msg.put("role", "assistant");
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "output_text");
            item.put("text", reply);
            content.add(item);
            msg.set("content", content);
            output.add(msg);
            out.set("output", output);

            writeJson(ex, 200, out);
        } catch (BearerApiClient.UpstreamRateLimitException rle) {
            if (acquired != null) {
                pool.markRateLimited(acquired.pat(), rle.resetTimeMs);
            }
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode err = objectMapper.createObjectNode();
            err.put("message", rle.getMessage());
            err.put("type", "rate_limit_error");
            if (rle.resetTimeMs > 0) {
                err.put("reset_time_ms", rle.resetTimeMs);
                err.put("reset_time_epoch_seconds", rle.resetTimeMs / 1000);
            }
            root.set("error", err);
            writeJson(ex, 429, root);
        } catch (Exception e) {
            writeJsonError(ex, 500, e.getMessage());
        } finally {
            ex.close();
        }
    }

    private String callUpstreamFullText(String prompt, String model, BearerBuilder.SessionContext sess, BearerApiClient bearerClient) throws Exception {
        ObjectNode body = templateBase.deepCopy();
        String nid = UUID.randomUUID().toString();
        body.put("request_id", nid);
        body.put("chat_record_id", nid);
        body.put("request_set_id", UUID.randomUUID().toString());
        body.put("session_id", UUID.randomUUID().toString());
        body.put("stream", false);
        body.put("aliyun_user_type", sess.identity().userType());
        ((ObjectNode) body.path("model_config")).put("key", model);
        ObjectNode biz = (ObjectNode) body.path("business");
        biz.put("id", UUID.randomUUID().toString());
        biz.put("begin_at", System.currentTimeMillis());
        biz.put("name", prompt.length() > 30 ? prompt.substring(0, 30) : prompt);

        ObjectNode ctx = (ObjectNode) body.path("chat_context");
        ((ObjectNode) ctx.path("text")).put("text", prompt);
        ((ObjectNode) ctx.path("extra").path("originalContent")).put("text", prompt);

        body.set("messages", buildQoderMessages((ArrayNode) body.path("messages"), null, prompt, false));

        String url = "https://api3.qoder.sh/algo/api/v2/service/pro/sse/agent_chat_generation" + "?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1";
        Map<String, String> extraHeaders = Map.of("x-model-key", model, "x-model-source", ((ObjectNode) body.path("model_config")).path("source").asText("system"));

        StringBuilder full = new StringBuilder();
        bearerClient.openStreamLines(url, body, extraHeaders, line -> {
            if (!line.startsWith("data:")) return;
            BridgeDelta delta = extractDelta(line.substring(5).trim());
            if (delta.isEmpty()) return;
            if (delta.content() != null && !delta.content().isEmpty()) {
                full.append(delta.content());
            }
        });

        return full.toString();
    }

    private String extractLatestInputText(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) return "";

        // input can be a string
        if (input.isTextual()) {
            return input.asText("");
        }

        // input can be an array of items
        if (input.isArray()) {
            for (int i = input.size() - 1; i >= 0; i--) {
                String txt = extractInputItemText(input.get(i));
                if (!txt.isBlank()) return txt;
            }
            return "";
        }

        // input can be an object
        return extractInputItemText(input);
    }

    private String extractInputItemText(JsonNode item) {
        if (item == null || item.isMissingNode() || item.isNull()) return "";

        // responses-style item: {content:[{text:"..."}]}
        JsonNode content = item.path("content");
        if (content.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode c : content) {
                if (c.path("text").isTextual()) {
                    String t = c.path("text").asText("");
                    if (!t.isBlank()) parts.add(t);
                } else if (c.path("type").isTextual() && c.path("content").isContainerNode()) {
                    String t = normalizeContent(c.path("content"));
                    if (!t.isBlank()) parts.add(t);
                }
            }
            return String.join("\n\n", parts);
        }

        // fallback
        if (item.path("text").isTextual()) {
            return item.path("text").asText("");
        }
        return "";
    }

    private void handleModels(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (!checkAuth(ex)) return;
            JsonNode cfg = loadModelsConfig();
            JsonNode list = cfg.path("data");

            ObjectNode root = objectMapper.createObjectNode();
            root.put("object", "list");
            ArrayNode data = objectMapper.createArrayNode();

            long created = 1767225600L; // 2026-01-01T00:00:00Z
            String createdAt = "2026-01-01T00:00:00Z";

            if (list.isArray()) {
                for (JsonNode src : list) {
                    if (!src.path("enable").asBoolean(true)) continue;
                    String id = src.path("id").asText("");
                    if (id.isBlank()) continue;

                    ObjectNode m = objectMapper.createObjectNode();
                    m.put("type", "model");
                    m.put("id", id);
                    m.put("display_name", src.path("display_name").asText(id));
                    m.put("created_at", createdAt);
                    m.put("object", "model");
                    m.put("created", src.path("created").asLong(created));
                    m.put("owned_by", src.path("owned_by").asText("qoder-bridge"));
                    data.add(m);
                }
            } else {
                for (String id : exposedModelKeys()) {
                    ObjectNode m = objectMapper.createObjectNode();
                    m.put("type", "model");
                    m.put("id", id);
                    m.put("display_name", id);
                    m.put("created_at", createdAt);
                    m.put("object", "model");
                    m.put("created", created);
                    m.put("owned_by", "qoder-bridge");
                    data.add(m);
                }
            }

            root.set("data", data);
            writeJson(ex, 200, root);
        } catch (Exception e) {
            writeJsonError(ex, 500, e.getMessage());
        } finally {
            ex.close();
        }
    }

    private String abbreviateJson(JsonNode node, int maxChars) {
        String raw = (node == null) ? "" : node.toString();
        if (raw.length() <= maxChars) return raw;
        return raw.substring(0, Math.max(0, maxChars)) + "...";
    }

    private void debugIncomingMessages(JsonNode messages) {
        try {
            if (messages == null || !messages.isArray()) {
                if (Config.debug()) {
                    System.out.println("[bridge][debug] incoming.messages: (missing or not array)");
                }
                return;
            }
            if (Config.debug()) {
                System.out.println("[bridge][debug] incoming.messages.len=" + messages.size());
            }
            int max = Math.min(messages.size(), 8);
            for (int i = 0; i < max; i++) {
                JsonNode m = messages.get(i);
                String role = m.path("role").asText("");
                boolean hasRole = m.has("role");
                JsonNode c = m.path("content");
                String cType = c.isMissingNode() ? "(missing)" : (c.isTextual() ? "string" : (c.isArray() ? "array" : (c.isObject() ? "object" : c.getNodeType().toString())));
                String txt = extractMessageText(m);
                String preview = txt.length() > 120 ? txt.substring(0, 120) + "..." : txt;
                if (Config.debug()) {
                    System.out.println("[bridge][debug] incoming.messages[" + i + "] role=" + (hasRole ? (role.isBlank() ? "(empty)" : role) : "(missing)")
                            + " contentType=" + cType
                            + " textPreview=" + preview.replace("\n", "\\n"));
                }
            }
        } catch (Exception e) {
            if (Config.debug()) {
                System.out.println("[bridge][debug] incoming.messages.debug error: " + e.getMessage());
            }
        }
    }

    private boolean isExposedModel(String model) {
        if (model == null || model.isBlank()) return false;
        for (String m : exposedModelKeys()) {
            if (model.equals(m)) return true;
        }
        return false;
    }

    private List<String> exposedModelKeys() {
        JsonNode list = loadModelsConfig().path("data");
        if (!list.isArray()) return List.of("lite");
        List<String> keys = new ArrayList<>();
        for (JsonNode m : list) {
            if (!m.path("enable").asBoolean(true)) continue;
            String k = m.path("id").asText("");
            if (!k.isBlank()) keys.add(k);
        }
        return keys.isEmpty() ? List.of("lite") : keys;
    }

    private String defaultModelKey() {
        JsonNode list = loadModelsConfig().path("data");
        if (!list.isArray()) return "lite";
        for (JsonNode m : list) {
            if (!m.path("enable").asBoolean(true)) continue;
            if (m.path("is_default").asBoolean(false)) {
                String k = m.path("id").asText("");
                if (!k.isBlank()) return k;
            }
        }
        return "lite";
    }

    private volatile JsonNode modelsCache;
    private volatile long modelsCacheMtime;

    private JsonNode loadModelsConfig() {
        try {
            File f = new File(MODELS_FILE);
            if (!f.exists()) {
                return objectMapper.createObjectNode();
            }
            long mt = f.lastModified();
            JsonNode cached = modelsCache;
            if (cached != null && modelsCacheMtime == mt) {
                return cached;
            }
            JsonNode parsed = objectMapper.readTree(f);
            modelsCache = parsed;
            modelsCacheMtime = mt;
            return parsed;
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private void writeJson(HttpExchange ex, int status, JsonNode payload) throws IOException {
        byte[] outBytes = objectMapper.writeValueAsBytes(payload);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, outBytes.length);
        ex.getResponseBody().write(outBytes);
    }

    private void writeJsonError(HttpExchange ex, int status, String message) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode err = objectMapper.createObjectNode();
        err.put("message", message == null ? "" : message);
        err.put("type", "invalid_request_error");
        root.set("error", err);
        writeJson(ex, status, root);
    }

    private BridgeDelta extractDelta(String dataLine) {
        try {
            JsonNode wrapper = objectMapper.readTree(dataLine);
            String inner = wrapper.path("body").asText("");
            if (inner.isEmpty()) return BridgeDelta.empty();
            JsonNode innerJson = objectMapper.readTree(inner);
            for (JsonNode ch : innerJson.path("choices")) {
                JsonNode delta = ch.path("delta");
                String role = delta.has("role") ? delta.path("role").asText("") : "";
                String content = delta.has("content") ? delta.path("content").asText("") : "";
                String reasoningContent = delta.has("reasoning_content") ? delta.path("reasoning_content").asText("") : "";
                ArrayNode toolCalls = null;
                if (delta.path("tool_calls").isArray() && delta.path("tool_calls").size() > 0) {
                    toolCalls = (ArrayNode) delta.path("tool_calls").deepCopy();
                }
                if (!role.isEmpty() || !content.isEmpty() || !reasoningContent.isEmpty() || toolCalls != null) {
                    return new BridgeDelta(role, content, reasoningContent, toolCalls);
                }
            }
        } catch (Exception ignore) {
        }
        return BridgeDelta.empty();
    }

    private String extractLatestUserPrompt(JsonNode messages) {
        if (messages == null || !messages.isArray() || messages.isEmpty()) return "";

        // 1) Preferred: find the latest explicit role=user message.
        //    Skip injected blocks like <system-reminder>.
        String explicitUser = extractLatestPromptByRoles(messages, List.of("user"));
        if (!explicitUser.isBlank()) return explicitUser;

        // 1.5) If we only see injected "user" blocks, try to use the latest system message as prompt.
        //      This is not perfect, but works for some clients that send a single system blob.
        String latestSystem = extractLatestPromptByRoles(messages, List.of("system"));
        if (!latestSystem.isBlank()) return latestSystem;

        // 2) Fallback: if the client omitted roles, use the latest non-empty message that doesn't look like
        //    a system prompt / tool boilerplate.
        String any = extractLatestPromptByRoles(messages, List.of("", "assistant", "system"));
        if (!any.isBlank()) return any;

        // 3) Last resort: just return the latest extractable text.
        for (int i = messages.size() - 1; i >= 0; i--) {
            String t = extractMessageText(messages.get(i));
            if (!t.isBlank()) return t;
        }
        return "";
    }

    private String extractLatestPromptByRoles(JsonNode messages, List<String> roles) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            String role = message.path("role").asText("");
            boolean roleMatch = roles.contains(role);
            if (!roleMatch) continue;

            String text = extractMessageText(message);
            if (text.isBlank()) continue;

            if (looksLikeSystemInjectedBlock(text)) {
                continue;
            }

            // Filter out known boilerplate that some clients send as the first/only message.
            if (looksLikeClientBoilerplate(text)) {
                continue;
            }

            System.out.println("[bridge] extractLatestUserPrompt found msg[" + i + "] role=" + (role.isBlank() ? "(missing)" : role)
                    + " text=" + (text.length() > 60 ? text.substring(0, 60) + "..." : text));
            return text;
        }
        return "";
    }

    private String extractMessageText(JsonNode message) {
        if (message == null || message.isMissingNode() || message.isNull()) return "";
        String text = normalizeMessageText(message);
        if (text.isBlank()) {
            text = extractTextFromNode(message);
        }
        return text == null ? "" : text.trim();
    }

    private boolean looksLikeSystemInjectedBlock(String text) {
        if (text == null) return false;
        String t = text.stripLeading();

        // Cursor/Codex/agent wrappers may put metadata blocks inside role=user.
        // These are not actual user prompts.
        if (t.startsWith("<system-reminder>")) return true;
        if (t.startsWith("<system-reminder ")) return true;
        if (t.startsWith("<system-communication>")) return true;
        if (t.startsWith("<attached_files>")) return true;
        if (t.startsWith("<open_and_recently_viewed_files>")) return true;
        if (t.startsWith("<git_status>")) return true;
        if (t.startsWith("<user_info>")) return true;
        if (t.startsWith("<timestamp>")) return true;
        if (t.startsWith("<agent_transcripts>")) return true;
        if (t.startsWith("<agent_skills>")) return true;

        // Generic XML-like tag at the beginning is suspicious; treat as injected.
        if (t.startsWith("<") && t.length() >= 2 && (Character.isLetter(t.charAt(1)) || t.charAt(1) == '/')) {
            return true;
        }

        return false;
    }

    private boolean looksLikeClientBoilerplate(String text) {
        if (text == null) return false;
        String t = text.stripLeading();
        // Heuristics: Claude Code often begins with this self-description; it's not a user prompt.
        if (t.startsWith("You are Claude Code")) return true;
        if (t.startsWith("You are an interactive agent")) return true;
        // Some wrappers prepend headings like "# System" and long policy blocks.
        if (t.startsWith("# System") && t.length() > 200) return true;
        return false;
    }

    private String extractTextFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        // If root is a simple text value, return it
        if (node.isTextual()) return node.asText("");
        // If root is an array, try to extract text from elements
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : node) {
                String part = extractTextFromNode(item);
                if (!part.isBlank()) parts.add(part);
            }
            return String.join("\n", parts);
        }
        // For objects, try common text-containing fields
        if (node.isObject()) {
            // Try 'text' field at root level
            if (node.path("text").isTextual()) {
                String t = node.path("text").asText("");
                if (!t.isBlank()) return t;
            }
            // If content is an object/array (not handled by normalizeMessageText), try it
            JsonNode content = node.path("content");
            if (!content.isMissingNode() && !content.isNull() && !content.isTextual()) {
                String t = extractTextFromNode(content);
                if (!t.isBlank()) return t;
            }
        }
        return "";
    }

    private ArrayNode buildQoderMessages(ArrayNode templateMessages, JsonNode incomingMessages, String prompt, boolean toolsEnabled) {
        ArrayNode rebuilt = objectMapper.createArrayNode();
        boolean keepTemplateSystem = !hasRole(incomingMessages, "system");
        if (keepTemplateSystem) {
            for (JsonNode msg : templateMessages) {
                if ("system".equals(msg.path("role").asText())) {
                    rebuilt.add(msg.deepCopy());
                }
            }
        }
        if (incomingMessages != null && incomingMessages.isArray()) {
            for (int i = 0; i < incomingMessages.size(); i++) {
                JsonNode message = incomingMessages.get(i);
                boolean allowStructuredToolCalls = toolsEnabled && hasResolvedToolResponse(incomingMessages, i);
                ObjectNode converted = convertIncomingMessage(message, toolsEnabled, allowStructuredToolCalls);
                if (converted != null) {
                    rebuilt.add(converted);
                }
            }
        }
        if (rebuilt.isEmpty() && !prompt.isBlank()) {
            rebuilt.add(buildUserMessage(prompt));
        }
        return rebuilt;
    }

    private boolean hasRole(JsonNode messages, String role) {
        if (messages == null || !messages.isArray()) return false;
        for (JsonNode message : messages) {
            if (role.equals(message.path("role").asText())) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode convertIncomingMessage(JsonNode message, boolean toolsEnabled, boolean allowStructuredToolCalls) {
        String role = message.path("role").asText("user");
        String text = normalizeMessageText(message);
        ArrayNode anyToolCalls = extractAnyToolCalls(message, text, toolsEnabled);
        ArrayNode structuredToolCalls = resolveStructuredToolCalls(message, text, toolsEnabled, allowStructuredToolCalls);

        if ("assistant".equals(role) && structuredToolCalls != null) {
            return buildAssistantToolCallMessage(text, structuredToolCalls);
        }

        if ("assistant".equals(role) && anyToolCalls != null && allowStructuredToolCalls == false) {
            return buildStructuredMessage("assistant", summarizeUnresolvedToolCalls(anyToolCalls));
        }

        if (!toolsEnabled && message.path("tool_calls").isArray() && message.path("tool_calls").size() > 0) {
            text = joinSections(text, renderToolCalls(message.path("tool_calls")));
        }

        if ("tool".equals(role)) {
            if (toolsEnabled) {
                return buildToolMessage(message, text);
            }
            role = "user";
            text = renderToolResult(message, text);
        }

        if (text.isBlank()) {
            return null;
        }

        if ("user".equals(role)) {
            return buildUserMessage(text);
        }

        return buildStructuredMessage(role, text);
    }

    private boolean hasResolvedToolResponse(JsonNode messages, int assistantIndex) {
        JsonNode message = messages.get(assistantIndex);
        if (!"assistant".equals(message.path("role").asText())) {
            return false;
        }
        boolean hasToolCalls = (message.path("tool_calls").isArray() && message.path("tool_calls").size() > 0)
                || parseToolCallsText(normalizeMessageText(message)) != null;
        if (!hasToolCalls) {
            return false;
        }
        for (int i = assistantIndex + 1; i < messages.size(); i++) {
            String nextRole = messages.get(i).path("role").asText();
            if ("tool".equals(nextRole)) {
                return true;
            }
            if ("assistant".equals(nextRole) || "user".equals(nextRole) || "system".equals(nextRole)) {
                return false;
            }
        }
        return false;
    }

    private ObjectNode buildUserMessage(String text) {
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", "");
        ArrayNode contents = objectMapper.createArrayNode();
        ObjectNode cn = objectMapper.createObjectNode();
        cn.put("type", "text");
        cn.put("text", text);
        contents.add(cn);
        userMsg.set("contents", contents);
        userMsg.set("response_meta", blankResponseMeta());
        userMsg.put("reasoning_content_signature", "");
        return userMsg;
    }

    private ObjectNode buildStructuredMessage(String role, String text) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("role", role);
        out.put("content", text == null ? "" : text);
        out.set("response_meta", blankResponseMeta());
        out.put("reasoning_content_signature", "");
        return out;
    }

    private ObjectNode buildAssistantToolCallMessage(String text, ArrayNode toolCalls) {
        String content = text == null ? "" : text;
        if (parseToolCallsText(content) != null) {
            content = "";
        }
        ObjectNode out = buildStructuredMessage("assistant", content);
        out.set("tool_calls", toolCalls.deepCopy());
        return out;
    }

    private ObjectNode buildToolMessage(JsonNode message, String text) {
        ObjectNode out = buildStructuredMessage("tool", text);
        if (message.path("name").isTextual()) {
            out.put("name", message.path("name").asText());
        }
        if (message.path("tool_call_id").isTextual()) {
            out.put("tool_call_id", message.path("tool_call_id").asText());
        }
        return out;
    }

    private ObjectNode blankResponseMeta() {
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", 0);
        usage.put("total_tokens", 0);
        ObjectNode completionDetails = objectMapper.createObjectNode();
        completionDetails.put("reasoning_tokens", 0);
        usage.set("completion_tokens_details", completionDetails);
        ObjectNode promptDetails = objectMapper.createObjectNode();
        promptDetails.put("cached_tokens", 0);
        usage.set("prompt_tokens_details", promptDetails);
        ObjectNode responseMeta = objectMapper.createObjectNode();
        responseMeta.put("id", "");
        responseMeta.set("usage", usage);
        return responseMeta;
    }

    private String normalizeMessageText(JsonNode message) {
        String text = normalizeContent(message.path("content"));
        if (text.isBlank()) {
            text = normalizeContent(message.path("contents"));
        }
        return text;
    }

    private ArrayNode resolveStructuredToolCalls(JsonNode message, String text, boolean toolsEnabled, boolean allowStructuredToolCalls) {
        if (!toolsEnabled || !allowStructuredToolCalls) {
            return null;
        }
        return extractAnyToolCalls(message, text, true);
    }

    private ArrayNode extractAnyToolCalls(JsonNode message, String text, boolean toolsEnabled) {
        if (!toolsEnabled) {
            return null;
        }
        if (message.path("tool_calls").isArray() && message.path("tool_calls").size() > 0) {
            return normalizeToolCalls(message.path("tool_calls"));
        }
        return parseToolCallsText(text);
    }

    private String normalizeContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : content) {
                String part = normalizeContentPart(item);
                if (!part.isBlank()) {
                    parts.add(part);
                }
            }
            return String.join("\n\n", parts);
        }
        return normalizeContentPart(content);
    }

    private String normalizeContentPart(JsonNode item) {
        if (item == null || item.isMissingNode() || item.isNull()) return "";
        if (item.isTextual()) return item.asText();
        if (item.isObject()) {
            String type = item.path("type").asText("");
            if (item.path("text").isTextual()) {
                return item.path("text").asText();
            }
            if (("image_url".equals(type) || "input_image".equals(type)) && item.path("image_url").path("url").isTextual()) {
                return "[image] " + item.path("image_url").path("url").asText();
            }
            if (item.path("content").isContainerNode()) {
                return normalizeContent(item.path("content"));
            }
            return item.toString();
        }
        return item.toString();
    }

    private String renderToolCalls(JsonNode toolCalls) {
        return "Tool calls:\n" + toolCalls.toString();
    }

    private ArrayNode parseToolCallsText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("Tool calls:")) {
            return null;
        }
        String payload = trimmed.substring("Tool calls:".length()).trim();
        if (payload.startsWith("```") && payload.endsWith("```")) {
            int newline = payload.indexOf('\n');
            if (newline >= 0) {
                payload = payload.substring(newline + 1, payload.length() - 3).trim();
            }
        }
        if (!payload.startsWith("[")) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(payload);
            return normalizeToolCalls(parsed);
        } catch (Exception ignore) {
            return null;
        }
    }

    private ArrayNode normalizeToolCalls(JsonNode rawToolCalls) {
        if (rawToolCalls == null || !rawToolCalls.isArray()) {
            return null;
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode rawToolCall : rawToolCalls) {
            JsonNode function = rawToolCall.path("function");
            String name = function.path("name").asText("");
            String arguments = normalizeToolArguments(function.path("arguments"));
            if (name.isBlank() && arguments.isBlank()) {
                continue;
            }
            ObjectNode call = objectMapper.createObjectNode();
            call.put("id", rawToolCall.path("id").asText(""));
            call.put("type", rawToolCall.path("type").asText("function"));
            ObjectNode normalizedFunction = objectMapper.createObjectNode();
            normalizedFunction.put("name", name);
            normalizedFunction.put("arguments", arguments);
            call.set("function", normalizedFunction);
            normalized.add(call);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private String summarizeUnresolvedToolCalls(ArrayNode toolCalls) {
        StringBuilder sb = new StringBuilder("Previously planned but unexecuted tool calls");
        int limit = Math.min(toolCalls.size(), 6);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String name = toolCalls.get(i).path("function").path("name").asText("");
            if (name.isBlank()) {
                name = "unknown";
            }
            names.add(name);
        }
        if (!names.isEmpty()) {
            sb.append(": ").append(String.join(", ", names));
        }
        if (toolCalls.size() > limit) {
            sb.append(" and ").append(toolCalls.size() - limit).append(" more");
        }
        sb.append('.');
        return sb.toString();
    }

    private String normalizeToolArguments(JsonNode arguments) {
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) {
            return "";
        }
        if (arguments.isTextual()) {
            return arguments.asText();
        }
        return arguments.toString();
    }

    private String renderToolResult(JsonNode message, String text) {
        String name = message.path("name").asText("");
        String toolCallId = message.path("tool_call_id").asText("");
        StringBuilder sb = new StringBuilder("Tool result");
        if (!name.isBlank()) {
            sb.append(" (").append(name).append(')');
        }
        if (!toolCallId.isBlank()) {
            sb.append(" [").append(toolCallId).append(']');
        }
        if (!text.isBlank()) {
            sb.append(":\n").append(text);
        }
        return sb.toString();
    }

    private String joinSections(String first, String second) {
        if (first == null || first.isBlank()) return second == null ? "" : second;
        if (second == null || second.isBlank()) return first;
        return first + "\n\n" + second;
    }

    private ObjectNode makeChunk(String id, long created, String model) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", id);
        root.put("object", "chat.completion.chunk");
        root.put("created", created);
        root.put("model", model);
        ArrayNode choices = objectMapper.createArrayNode();
        ObjectNode c = objectMapper.createObjectNode();
        c.put("index", 0);
        c.set("delta", objectMapper.createObjectNode());
        c.putNull("finish_reason");
        choices.add(c);
        root.set("choices", choices);
        return root;
    }

    private void writeSseChunk(OutputStream out, String id, long created, String model, String role,
                               String content, String reasoningContent, ArrayNode toolCalls) throws IOException {
        ObjectNode chunk = makeChunk(id, created, model);
        ObjectNode delta = (ObjectNode) chunk.path("choices").get(0).path("delta");
        if (role != null && !role.isEmpty()) {
            delta.put("role", role);
        }
        if (content != null && !content.isEmpty()) {
            delta.put("content", content);
        }
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            delta.put("reasoning_content", reasoningContent);
        }
        if (toolCalls != null && toolCalls.size() > 0) {
            delta.set("tool_calls", toolCalls);
        }
        out.write(("data: " + objectMapper.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private record BridgeDelta(String role, String content, String reasoningContent, ArrayNode toolCalls) {
        private static BridgeDelta empty() {
            return new BridgeDelta("", "", "", null);
        }

        private boolean isEmpty() {
            return role.isEmpty() && content.isEmpty() && reasoningContent.isEmpty()
                    && (toolCalls == null || toolCalls.isEmpty());
        }
    }

    private final class StreamAccumulator {
        private final OutputStream out;
        private final String reqId;
        private final long created;
        private final String model;
        private final boolean toolCallFallbackEnabled;
        private final ToolCallAccumulator toolCalls = new ToolCallAccumulator();
        private final StringBuilder pendingContent = new StringBuilder();
        private String pendingRole = "assistant";
        private boolean emittedChunk;
        private boolean streamingText;

        private StreamAccumulator(OutputStream out, String reqId, long created, String model, boolean toolCallFallbackEnabled) {
            this.out = out;
            this.reqId = reqId;
            this.created = created;
            this.model = model;
            this.toolCallFallbackEnabled = toolCallFallbackEnabled;
        }

        private void accept(BridgeDelta delta) throws IOException {
            if (delta.role() != null && !delta.role().isEmpty()) {
                pendingRole = delta.role();
            }
            if (delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
                emit(null, delta.reasoningContent(), null);
            }
            if (delta.toolCalls() != null && delta.toolCalls().size() > 0) {
                discardBufferedToolCallText();
                toolCalls.append(delta.toolCalls());
                emit(null, null, withToolCallIndices(delta.toolCalls()));
                return;
            }
            if (delta.content() == null || delta.content().isEmpty()) {
                return;
            }
            if (!toolCallFallbackEnabled || streamingText) {
                streamingText = true;
                emit(delta.content(), null, null);
                return;
            }

            pendingContent.append(delta.content());
            if (isPotentialToolCallText(pendingContent.toString())) {
                return;
            }

            streamingText = true;
            emitBufferedText();
        }

        private void flush() throws IOException {
            if (pendingContent.length() == 0) {
                return;
            }
            String buffered = pendingContent.toString();
            pendingContent.setLength(0);
            ArrayNode parsedToolCalls = toolCallFallbackEnabled ? parseToolCallsText(buffered) : null;
            if (parsedToolCalls != null) {
                toolCalls.append(parsedToolCalls);
                emit(null, null, withToolCallIndices(parsedToolCalls));
                return;
            }
            streamingText = true;
            emit(buffered, null, null);
        }

        private String finishReason() {
            return toolCalls.isEmpty() ? "stop" : "tool_calls";
        }

        private void emitBufferedText() throws IOException {
            if (pendingContent.length() == 0) {
                return;
            }
            String buffered = pendingContent.toString();
            pendingContent.setLength(0);
            emit(buffered, null, null);
        }

        private void discardBufferedToolCallText() throws IOException {
            if (pendingContent.length() == 0) {
                return;
            }
            String buffered = pendingContent.toString();
            pendingContent.setLength(0);
            if (toolCallFallbackEnabled && isPotentialToolCallText(buffered)) {
                return;
            }
            streamingText = true;
            emit(buffered, null, null);
        }

        private void emit(String content, String reasoningContent, ArrayNode toolCalls) throws IOException {
            String role = "";
            if (!emittedChunk) {
                role = pendingRole == null || pendingRole.isBlank() ? "assistant" : pendingRole;
            }
            writeSseChunk(out, reqId, created, model, role, content, reasoningContent, toolCalls);
            emittedChunk = true;
        }

        private boolean isPotentialToolCallText(String text) {
            String candidate = text == null ? "" : text.stripLeading();
            if (candidate.isEmpty()) {
                return true;
            }
            return "Tool calls:".startsWith(candidate) || candidate.startsWith("Tool calls:");
        }

        private ArrayNode withToolCallIndices(ArrayNode rawToolCalls) {
            ArrayNode indexed = objectMapper.createArrayNode();
            for (int i = 0; i < rawToolCalls.size(); i++) {
                ObjectNode call = (ObjectNode) rawToolCalls.get(i).deepCopy();
                if (!call.path("index").isInt()) {
                    call.put("index", i);
                }
                indexed.add(call);
            }
            return indexed;
        }
    }

    private final class ToolCallAccumulator {
        private final ArrayNode calls = objectMapper.createArrayNode();

        private void append(ArrayNode deltaCalls) {
            for (JsonNode deltaCall : deltaCalls) {
                int index = deltaCall.path("index").isInt() ? deltaCall.path("index").asInt() : calls.size();
                while (calls.size() <= index) {
                    ObjectNode placeholder = objectMapper.createObjectNode();
                    placeholder.put("id", "");
                    placeholder.put("type", "function");
                    ObjectNode function = objectMapper.createObjectNode();
                    function.put("name", "");
                    function.put("arguments", "");
                    placeholder.set("function", function);
                    calls.add(placeholder);
                }

                ObjectNode existing = (ObjectNode) calls.get(index);
                if (deltaCall.path("id").isTextual()) {
                    existing.put("id", deltaCall.path("id").asText());
                }
                if (deltaCall.path("type").isTextual()) {
                    existing.put("type", deltaCall.path("type").asText());
                }

                JsonNode deltaFunction = deltaCall.path("function");
                ObjectNode existingFunction = (ObjectNode) existing.path("function");
                if (deltaFunction.path("name").isTextual()) {
                    existingFunction.put("name", deltaFunction.path("name").asText());
                }
                if (deltaFunction.path("arguments").isTextual()) {
                    existingFunction.put("arguments", existingFunction.path("arguments").asText("") + deltaFunction.path("arguments").asText());
                }
            }
        }

        private boolean isEmpty() {
            return calls.isEmpty();
        }

        private ArrayNode snapshot() {
            return calls.deepCopy();
        }
    }

    public static void run(String pat, int port) throws Exception {
        Config.Qoder qoderCfg = Config.qoder();
        Config.App    appCfg    = Config.app();
        List<String> pats = new ArrayList<>(qoderCfg.pat());
        if (pat != null && !pat.isBlank()) {
            pats.add(pat);
        }
        if (pats.isEmpty()) {
            String envPat = System.getProperty("QODER_PAT");
            if (envPat == null) envPat = System.getenv("QODER_PAT");
            if (envPat != null && !envPat.isBlank()) {
                for (String part : envPat.split(",")) {
                    if (!part.isBlank()) pats.add(part.trim());
                }
            }
        }
        if (pats.isEmpty()) {
            throw new RuntimeException("Qoder PAT is required. Set it in config.yaml (qoder.pat, string or list) or via QODER_PAT env variable (comma-separated).");
        }
        String configuredHost = System.getProperty("QODER_HOST", System.getenv("QODER_HOST"));
        String configuredPort = System.getProperty("QODER_PORT", System.getenv("QODER_PORT"));
        int effectivePort = port > 0 ? port : (configuredPort != null && !configuredPort.isBlank()
                ? Integer.parseInt(configuredPort) : (appCfg.port() > 0 ? appCfg.port() : 8080));
        String effectiveHost = configuredHost != null && !configuredHost.isBlank() ? configuredHost : appCfg.host();
        String basePromptFile = qoderCfg.basePrompt();
        new OpenAiBridge(pats, basePromptFile).start(effectiveHost, effectivePort);
        Thread.currentThread().join();
    }

    public static void main(String[] args) throws Exception {
        run(null, 0);
    }
}

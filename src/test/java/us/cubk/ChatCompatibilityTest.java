package us.cubk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatCompatibilityTest {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String PROVIDER_ERROR = mapper.createObjectNode()
            .put("code", "provider_error").put("message", "Error in upstream response").put("type", "provider_error")
            .put("details", "{\"error\":{\"code\":\"1214\",\"message\":\"角色信息不正确\"}}").toString();

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void developerMessagesWorkForStreamingAndNonStreamingRequests(boolean stream) throws Exception {
        ObjectNode request = request(stream);
        request.withArray("messages").addObject().put("role", "developer").put("content", "Follow IDE instructions.");
        request.withArray("messages").addObject().put("role", "user")
                .putArray("content").addObject().put("type", "text").put("text", "hello");
        try (Bridge fixture = new Bridge((ex, body) -> {
            JsonNode messages = body.path("messages");
            boolean compatible = messages.size() == 2 && messages.get(0).path("role").asText().equals("system")
                    && messages.get(1).path("role").asText().equals("user");
            respond(ex, 200, compatible ? success() : providerErrorSse());
        })) {
            HttpResponse<String> response = fixture.send(request);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("hello back"), response.body());
            if (stream) assertTrue(response.body().contains("data: [DONE]"));
            else assertEquals("hello back", mapper.readTree(response.body()).path("choices").get(0).path("message").path("content").asText());
            JsonNode sent = fixture.lastRequest.get().path("messages");
            assertEquals("Follow IDE instructions.", sent.get(0).path("content").asText());
            assertEquals("developer", request.path("messages").get(0).path("role").asText());
        }
    }

    @Test
    void mergesSystemAndDeveloperInstructionsWhilePreservingConversationAndTools() throws Exception {
        ObjectNode request = request(false);
        ArrayNode messages = request.withArray("messages");
        messages.addObject().put("role", "system").put("content", "System rules.");
        messages.addObject().put("role", "developer").putArray("content")
                .addObject().put("type", "text").put("text", "Developer rules.");
        messages.addObject().put("role", "user").put("content", "look up a file");
        ObjectNode assistant = messages.addObject().put("role", "assistant");
        assistant.putArray("tool_calls").addObject().put("id", "call_1").put("type", "function")
                .putObject("function").put("name", "read_file").put("arguments", "{}");
        messages.addObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "file contents");
        messages.addObject().put("role", "user").put("content", "summarize it");
        request.putArray("tools").addObject().put("type", "function").putObject("function").put("name", "read_file");
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, success()))) {
            assertEquals(200, fixture.send(request).statusCode());
            JsonNode sent = fixture.lastRequest.get().path("messages");
            assertEquals(List.of("system", "user", "assistant", "tool", "user"),
                    mapper.convertValue(sent.findValues("role"), mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            assertTrue(sent.get(0).path("content").asText().contains("System rules."));
            assertTrue(sent.get(0).path("content").asText().contains("Developer rules."));
            assertEquals("call_1", sent.get(2).path("tool_calls").get(0).path("id").asText());
            assertEquals("call_1", sent.get(3).path("tool_call_id").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsNestedProviderErrorsToTheClient(boolean stream) throws Exception {
        ObjectNode request = request(stream);
        request.withArray("messages").addObject().put("role", "user").put("content", "hello");
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, providerErrorSse()))) {
            HttpResponse<String> response = fixture.send(request);
            assertEquals(stream ? 200 : 400, response.statusCode());
            JsonNode error;
            if (stream) {
                String data = response.body().lines().filter(line -> line.startsWith("data: {")).findFirst().orElseThrow();
                error = mapper.readTree(data.substring(5).trim()).path("error");
                assertTrue(response.body().contains("data: [DONE]"));
                assertFalse(response.body().contains("\"finish_reason\":\"stop\""));
            } else error = mapper.readTree(response.body()).path("error");
            assertEquals("provider_error", error.path("type").asText());
            assertEquals("provider_error", error.path("code").asText());
            assertTrue(error.path("details").asText().contains("1214"), response.body());
            assertTrue(error.path("details").asText().contains("角色信息不正确"), response.body());
        }
    }

    @Test
    void streamingRateLimitIsReportedAsAnSseError() throws Exception {
        ObjectNode request = request(true);
        request.withArray("messages").addObject().put("role", "user").put("content", "hello");
        String limit = mapper.createObjectNode().put("code", 115)
                .put("message", "{\"agentLimitResetTime\":1800000060000}").toString();
        String sse = "data: " + mapper.createObjectNode().put("statusCodeValue", 403).put("body", limit) + "\n\n";
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, sse))) {
            HttpResponse<String> response = fixture.send(request);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"type\":\"rate_limit_error\""), response.body());
            assertTrue(response.body().contains("1800000060000"), response.body());
            assertTrue(response.body().contains("data: [DONE]"));
        }
    }

    @Test
    void nonStreamingResponsesRetainsDefaultInstructionsAndIncludesUserInput() throws Exception {
        ObjectNode request = request(false).put("input", "hello");
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, success()))) {
            HttpResponse<String> response = fixture.send(request, "/v1/responses");
            assertEquals(200, response.statusCode());
            assertEquals("hello back", mapper.readTree(response.body()).path("output").get(0)
                    .path("content").get(0).path("text").asText());
            JsonNode sent = fixture.lastRequest.get().path("messages");
            assertEquals("Default Qoder instructions.", sent.get(0).path("content").asText());
            assertEquals("user", sent.get(1).path("role").asText());
            assertEquals("hello", sent.get(1).path("contents").get(0).path("text").asText());
        }
    }

    @Test
    void responsesStreamAndChatBothAcceptDeveloperInstructions() throws Exception {
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, success()))) {
            HttpResponse<String> responses = fixture.send(request(true).put("input", "hello")
                    .put("instructions", "IDE instructions."), "/v1/responses");
            assertEquals(200, responses.statusCode());
            assertTrue(responses.body().contains("event: response.completed"));
            assertEquals("IDE instructions.", fixture.lastRequest.get().path("messages").get(0).path("content").asText());
            ObjectNode chat = request(true);
            chat.withArray("messages").addObject().put("role", "developer").put("content", "IDE instructions.");
            chat.withArray("messages").addObject().put("role", "user").put("content", "hello");
            assertTrue(fixture.send(chat).body().contains("hello back"));
            assertEquals("system", fixture.lastRequest.get().path("messages").get(0).path("role").asText());
        }
    }

    @Test
    void responsesTextStreamHasOrderedLifecycleAndConsistentFinalOutput() throws Exception {
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200,
                delta(mapper.createObjectNode().put("content", "hello "))
                        + delta(mapper.createObjectNode().put("content", "世界"))))) {
            HttpResponse<String> response = fixture.send(request(true).put("input", "hello"), "/v1/responses");
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            List<JsonNode> events = events(response.body());
            assertEquals("response.created", events.get(0).path("type").asText());
            assertEquals("response.in_progress", events.get(1).path("type").asText());
            for (int i = 0; i < events.size(); i++) assertEquals(i, events.get(i).path("sequence_number").asInt());
            String text = events.stream().filter(e -> e.path("type").asText().equals("response.output_text.delta"))
                    .map(e -> e.path("delta").asText()).collect(java.util.stream.Collectors.joining());
            assertEquals("hello 世界", text);
            JsonNode completed = events.get(events.size() - 1);
            assertEquals("response.completed", completed.path("type").asText());
            assertEquals("completed", completed.path("response").path("status").asText());
            JsonNode message = completed.path("response").path("output").get(0);
            assertEquals(text, message.path("content").get(0).path("text").asText());
            assertEquals("completed", message.path("status").asText());
            for (JsonNode event : events) {
                if (event.has("item_id")) assertEquals(message.path("id").asText(), event.path("item_id").asText());
            }
            assertFalse(response.body().contains("[DONE]"));
            assertTrue(fixture.lastRequest.get().path("stream").asBoolean());
        }
    }

    @Test
    void textDeltaIsDeliveredBeforeUpstreamFinishes() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var readerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Bridge fixture = new Bridge((ex, body) -> {
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                out.write(delta(mapper.createObjectNode().put("content", "first token")).getBytes(StandardCharsets.UTF_8));
                out.flush();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        })) {
            HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient().send(
                    fixture.httpRequest(request(true).put("input", "hello"), "/v1/responses"), HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                var first = readerExecutor.submit(() -> {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ") && line.contains("\"type\":\"response.output_text.delta\"")) return line;
                    }
                    return "";
                });
                assertTrue(first.get(3, java.util.concurrent.TimeUnit.SECONDS).contains("first token"));
                assertEquals(1, release.getCount());
                release.countDown();
                assertTrue(reader.lines().anyMatch(line -> line.contains("response.completed")));
            }
        } finally {
            release.countDown();
            readerExecutor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parallelFunctionCallsAccumulateArgumentsAndPreserveCallIds(boolean stream) throws Exception {
        ObjectNode request = responseToolRequest(stream);
        ArrayNode first = mapper.createArrayNode();
        first.add(toolDelta(0, "call_read", "read_file", "{\"path\":"));
        first.add(toolDelta(1, "call_search", "search", "{\"query\":"));
        ArrayNode second = mapper.createArrayNode();
        second.add(toolDelta(1, null, null, "\"needle\"}"));
        second.add(toolDelta(0, null, null, "\"a.kt\"}"));
        String data = delta(mapper.createObjectNode().set("tool_calls", first))
                + delta(mapper.createObjectNode().set("tool_calls", second));
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, data))) {
            HttpResponse<String> result = fixture.send(request, "/v1/responses");
            assertEquals(200, result.statusCode());
            JsonNode response = stream ? lastResponse(result.body()) : mapper.readTree(result.body());
            assertEquals(2, response.path("output").size());
            assertEquals("function_call", response.path("output").get(0).path("type").asText());
            assertEquals("call_read", response.path("output").get(0).path("call_id").asText());
            assertEquals("{\"path\":\"a.kt\"}", response.path("output").get(0).path("arguments").asText());
            assertEquals("call_search", response.path("output").get(1).path("call_id").asText());
            assertEquals("{\"query\":\"needle\"}", response.path("output").get(1).path("arguments").asText());
            if (stream) {
                assertEquals(4, events(result.body()).stream().filter(e -> e.path("type").asText().equals("response.function_call_arguments.delta")).count());
                assertEquals(2, events(result.body()).stream().filter(e -> e.path("type").asText().equals("response.function_call_arguments.done")).count());
            }
            assertEquals("read_file", fixture.lastRequest.get().path("tools").get(0).path("function").path("name").asText());
        }
    }

    @Test
    void responsesHistoryInstructionsAndFunctionResultsReachUpstream() throws Exception {
        ObjectNode request = responseToolRequest(true).put("instructions", "Follow project rules.").put("max_output_tokens", 256);
        request.putObject("tool_choice").put("type", "function").put("name", "read_file");
        ArrayNode input = request.putArray("input");
        input.addObject().put("role", "developer").put("content", "Follow IDE rules.");
        input.addObject().put("role", "user").putArray("content").addObject().put("type", "input_text").put("text", "read it");
        input.addObject().put("type", "message").put("role", "assistant").putArray("content")
                .addObject().put("type", "output_text").put("text", "I will read it.");
        input.addObject().put("type", "function_call").put("call_id", "call_read").put("name", "read_file").put("arguments", "{}");
        input.addObject().put("type", "function_call_output").put("call_id", "call_read").put("output", "file contents");
        input.addObject().put("role", "user").put("content", "summarize");
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, success()))) {
            assertEquals(200, fixture.send(request, "/v1/responses").statusCode());
            JsonNode body = fixture.lastRequest.get();
            JsonNode messages = body.path("messages");
            assertEquals(List.of("system", "user", "assistant", "tool", "user"), mapper.convertValue(messages.findValues("role"),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            assertTrue(messages.get(0).path("content").asText().contains("Follow project rules."));
            assertTrue(messages.get(0).path("content").asText().contains("Follow IDE rules."));
            assertEquals("call_read", messages.get(2).path("tool_calls").get(0).path("id").asText());
            assertEquals("call_read", messages.get(3).path("tool_call_id").asText());
            assertEquals(256, body.path("parameters").path("max_tokens").asInt());
            assertEquals("read_file", body.path("tool_choice").path("function").path("name").asText());
        }
    }

    @Test
    void textualToolFallbackProducesFunctionEventsInsteadOfLiteralToolText() throws Exception {
        String text = "Tool calls:\n[{\"id\":\"call_read\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]";
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200,
                delta(mapper.createObjectNode().put("content", text.substring(0, 5)))
                        + delta(mapper.createObjectNode().put("content", text.substring(5)))))) {
            String result = fixture.send(responseToolRequest(true), "/v1/responses").body();
            assertTrue(result.contains("event: response.function_call_arguments.delta"));
            assertFalse(result.contains("event: response.output_text.delta"));
            assertEquals("read_file", lastResponse(result).path("output").get(0).path("name").asText());
        }
    }

    @Test
    void reasoningEventsHaveDistinctOutputIndexesFromAnswerText() throws Exception {
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200,
                delta(mapper.createObjectNode().put("reasoning_content", "Thinking.")) + success()))) {
            String result = fixture.send(request(true).put("input", "hello"), "/v1/responses").body();
            assertTrue(result.contains("event: response.reasoning_summary_text.delta"));
            JsonNode output = lastResponse(result).path("output");
            assertEquals("reasoning", output.get(0).path("type").asText());
            assertEquals("Thinking.", output.get(0).path("summary").get(0).path("text").asText());
            assertEquals("message", output.get(1).path("type").asText());
            JsonNode textDelta = events(result).stream().filter(e -> e.path("type").asText().equals("response.output_text.delta")).findFirst().orElseThrow();
            assertEquals(1, textDelta.path("output_index").asInt());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upstreamFailureEmitsErrorAndFailedWithoutCompleted(boolean partialText) throws Exception {
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, (partialText ? success() : "") + providerErrorSse()))) {
            String result = fixture.send(request(true).put("input", "hello"), "/v1/responses").body();
            List<JsonNode> events = events(result);
            assertEquals("error", events.get(events.size() - 2).path("type").asText());
            assertEquals("response.failed", events.get(events.size() - 1).path("type").asText());
            assertEquals("failed", lastResponse(result).path("status").asText());
            assertTrue(lastResponse(result).path("error").path("details").asText().contains("1214"));
            assertFalse(result.contains("event: response.completed"));
            assertFalse(result.contains("[DONE]"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tokenLimitTerminatesAsIncomplete(boolean stream) throws Exception {
        ObjectNode finish = mapper.createObjectNode();
        finish.putArray("choices").addObject().put("finish_reason", "length").putObject("delta");
        String data = success() + "data: " + mapper.createObjectNode().put("body", finish.toString()) + "\n\n";
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, data))) {
            String result = fixture.send(request(stream).put("input", "hello"), "/v1/responses").body();
            JsonNode response = stream ? lastResponse(result) : mapper.readTree(result);
            assertEquals("incomplete", response.path("status").asText());
            assertEquals("max_output_tokens", response.path("incomplete_details").path("reason").asText());
            if (stream) assertTrue(result.contains("event: response.incomplete"));
        }
    }

    @Test
    void responsesStreamRecoversAuthenticationWithoutDuplicateLifecycle() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        try (Bridge fixture = new Bridge((ex, body) -> {
            if (requests.incrementAndGet() == 1) respond(ex, 401, "unauthorized");
            else respond(ex, 200, success());
        })) {
            String result = fixture.send(request(true).put("input", "hello"), "/v1/responses").body();
            assertEquals(2, requests.get());
            assertEquals(1, events(result).stream().filter(e -> e.path("type").asText().equals("response.created")).count());
            assertEquals("completed", lastResponse(result).path("status").asText());
        }
    }

    @Test
    void responsesRateLimitFailsTheStreamAndCoolsDownThePat() throws Exception {
        String limit = mapper.createObjectNode().put("code", 115)
                .put("message", "{\"agentLimitResetTime\":1800000060000}").toString();
        String sse = "data: " + mapper.createObjectNode().put("statusCodeValue", 403).put("body", limit) + "\n\n";
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, sse))) {
            ObjectNode request = request(true).put("input", "hello");
            String first = fixture.send(request, "/v1/responses").body();
            assertEquals("failed", lastResponse(first).path("status").asText());
            assertEquals("rate_limit_error", lastResponse(first).path("error").path("code").asText());
            assertEquals(1_800_000_060_000L, lastResponse(first).path("error").path("reset_time_ms").asLong());
            HttpResponse<String> retry = fixture.send(request, "/v1/responses");
            assertEquals(429, retry.statusCode());
            assertFalse(retry.body().contains("event: response.created"));
        }
    }

    @Test
    void delayedFunctionMetadataRetainsBufferedArguments() throws Exception {
        String data = delta(mapper.createObjectNode().set("tool_calls", mapper.createArrayNode().add(toolDelta(0, null, null, "{\"path\":\"a.kt\"}"))))
                + delta(mapper.createObjectNode().set("tool_calls", mapper.createArrayNode().add(toolDelta(0, "call_read", "read_file", ""))));
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, data))) {
            String result = fixture.send(responseToolRequest(true), "/v1/responses").body();
            JsonNode item = lastResponse(result).path("output").get(0);
            assertEquals("read_file", item.path("name").asText());
            assertEquals("{\"path\":\"a.kt\"}", item.path("arguments").asText());
            assertEquals(1, events(result).stream().filter(e -> e.path("type").asText().equals("response.function_call_arguments.delta")).count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing_input", "previous_response_id", "builtin_tool", "bad_function"})
    void invalidResponsesRequestsFailBeforeOpeningStream(String problem) throws Exception {
        ObjectNode request = request(true).put("input", "hello");
        switch (problem) {
            case "missing_input" -> request.remove("input");
            case "previous_response_id" -> request.put("previous_response_id", "resp_unknown");
            case "builtin_tool" -> request.putArray("tools").addObject().put("type", "web_search");
            case "bad_function" -> request.putArray("tools").addObject().put("type", "function").put("function", "invalid");
        }
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, success()))) {
            HttpResponse<String> result = fixture.send(request, "/v1/responses");
            assertEquals(400, result.statusCode());
            assertTrue(result.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
            assertNull(fixture.lastRequest.get());
        }
    }

    @Test
    void modelListUsesNamesAndFallsBackToIdsWhenNameIsMissingOrBlank() throws Exception {
        ObjectNode catalog = mapper.createObjectNode();
        ArrayNode data = catalog.putArray("data");
        data.addObject().put("id", "gfmodel").put("name", "GLM-5.3-Flash").put("owned_by", "test-owner");
        data.addObject().put("id", "legacy");
        data.addObject().put("id", "blank-name").put("name", " ");
        data.addObject().put("id", "disabled").put("name", "Hidden").put("enable", false);
        data.addObject().put("id", "").put("name", "Missing ID");
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, success()), catalog)) {
            JsonNode listed = mapper.readTree(fixture.getModels().body()).path("data");
            assertEquals(List.of("GLM-5.3-Flash", "legacy", "blank-name"), mapper.convertValue(listed.findValues("id"),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            assertEquals("GLM-5.3-Flash", listed.get(0).path("display_name").asText());
            assertEquals("test-owner", listed.get(0).path("owned_by").asText());
            assertEquals("gfmodel", catalog.path("data").get(0).path("id").asText());
        }
    }

    @ParameterizedTest
    @CsvSource({"/v1/chat/completions,false", "/v1/chat/completions,true", "/v1/responses,false", "/v1/responses,true"})
    void modelNamesResolveToRealIdsForBothApisAndStreamingModes(String path, boolean stream) throws Exception {
        ObjectNode catalog = mapper.createObjectNode();
        catalog.putArray("data").addObject().put("id", "gfmodel").put("name", "GLM-5.3-Flash");
        ObjectNode request = request(stream).put("model", "GLM-5.3-Flash").put("input", "hello");
        request.withArray("messages").addObject().put("role", "user").put("content", "hello");
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, success()), catalog)) {
            HttpResponse<String> response = fixture.send(request, path);
            assertEquals(200, response.statusCode());
            assertEquals("gfmodel", fixture.lastRequest.get().path("model_config").path("key").asText());
            assertEquals("gfmodel", fixture.lastModelHeader.get());
            assertTrue(response.body().contains("\"model\":\"GLM-5.3-Flash\""), response.body());
            assertEquals("GLM-5.3-Flash", request.path("model").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gfmodel", "custom-upstream-model", "Hidden"})
    void rawIdsAndUnknownModelsKeepTheirExistingBehavior(String model) throws Exception {
        ObjectNode catalog = mapper.createObjectNode();
        ArrayNode data = catalog.putArray("data");
        data.addObject().put("id", "gfmodel").put("name", "Friendly name");
        data.addObject().put("id", "another-id").put("name", "gfmodel");
        data.addObject().put("id", "disabled-id").put("name", "Hidden").put("enable", false);
        ObjectNode request = request(false).put("model", model);
        request.withArray("messages").addObject().put("role", "user").put("content", "hello");
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, success()), catalog)) {
            assertEquals(200, fixture.send(request).statusCode());
            assertEquals(model, fixture.lastRequest.get().path("model_config").path("key").asText());
            assertEquals(model, fixture.lastModelHeader.get());
        }
    }

    @Test
    void modelNamesAndIdMappingsReloadTogetherAfterCatalogChanges() throws Exception {
        ObjectNode catalog = mapper.createObjectNode();
        catalog.putArray("data").addObject().put("id", "gfmodel").put("name", "Old name");
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, success()), catalog)) {
            assertEquals("Old name", mapper.readTree(fixture.getModels().body()).path("data").get(0).path("id").asText());
            ObjectNode initial = request(false).put("model", "Old name").put("input", "hello");
            assertEquals(200, fixture.send(initial, "/v1/responses").statusCode());
            assertEquals("gfmodel", fixture.lastModelHeader.get());
            long previousTime = Files.getLastModifiedTime(fixture.modelsFile).toMillis();
            catalog.withArray("data").removeAll().addObject().put("id", "new-real-id").put("name", "New name");
            Files.writeString(fixture.modelsFile, catalog.toString());
            Files.setLastModifiedTime(fixture.modelsFile, java.nio.file.attribute.FileTime.fromMillis(previousTime + 2000));
            assertEquals("New name", mapper.readTree(fixture.getModels().body()).path("data").get(0).path("id").asText());
            assertEquals(200, fixture.send(initial.put("model", "New name"), "/v1/responses").statusCode());
            assertEquals("new-real-id", fixture.lastModelHeader.get());
        }
    }

    @Test
    void omittedModelStillUsesConfiguredDefaultRealId() throws Exception {
        ObjectNode catalog = mapper.createObjectNode();
        catalog.putArray("data").addObject().put("id", "gfmodel").put("name", "Friendly").put("is_default", true);
        ObjectNode request = request(false).put("input", "hello");
        request.remove("model");
        try (Bridge fixture = new Bridge((ex, body) -> respond(ex, 200, success()), catalog)) {
            assertEquals(200, fixture.send(request, "/v1/responses").statusCode());
            assertEquals("gfmodel", fixture.lastModelHeader.get());
        }
    }

    private static ObjectNode responseToolRequest(boolean stream) {
        ObjectNode request = request(stream).put("input", "hello");
        request.putArray("tools").addObject().put("type", "function").put("name", "read_file").putObject("parameters").put("type", "object");
        return request;
    }

    private static ObjectNode toolDelta(int index, String id, String name, String arguments) {
        ObjectNode call = mapper.createObjectNode().put("index", index);
        if (id != null) call.put("id", id);
        ObjectNode function = call.putObject("function").put("arguments", arguments);
        if (name != null) function.put("name", name);
        return call;
    }

    private static String delta(ObjectNode delta) {
        ObjectNode inner = mapper.createObjectNode();
        inner.putArray("choices").addObject().set("delta", delta);
        return "data: " + mapper.createObjectNode().put("body", inner.toString()) + "\n\n";
    }

    private static List<JsonNode> events(String sse) throws IOException {
        var events = new java.util.ArrayList<JsonNode>();
        String eventName = "";
        for (String line : sse.split("\n")) {
            if (line.startsWith("event: ")) eventName = line.substring(7);
            if (!line.startsWith("data: ")) continue;
            JsonNode event = mapper.readTree(line.substring(6));
            assertEquals(eventName, event.path("type").asText());
            events.add(event);
        }
        return events;
    }

    private static JsonNode lastResponse(String sse) throws IOException {
        List<JsonNode> events = events(sse);
        return events.get(events.size() - 1).path("response");
    }

    private static ObjectNode request(boolean stream) {
        return mapper.createObjectNode().put("model", "gfmodel").put("stream", stream);
    }

    private static String success() {
        ObjectNode inner = mapper.createObjectNode();
        inner.putArray("choices").addObject().putObject("delta").put("role", "assistant").put("content", "hello back");
        return "data: " + mapper.createObjectNode().put("body", inner.toString()) + "\n\n";
    }

    private static String providerErrorSse() {
        return "data:" + mapper.createObjectNode().put("body", PROVIDER_ERROR).put("statusCodeValue", 400)
                .put("statusCode", "BAD_REQUEST") + "\n\n";
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange ex, JsonNode body) throws IOException;
    }

    private final class Bridge implements AutoCloseable {
        final HttpServer upstream;
        final HttpServer downstream;
        final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
        final AtomicReference<String> lastModelHeader = new AtomicReference<>();
        final Path modelsFile;

        Bridge(Responder responder) throws Exception {
            this(responder, mapper.createObjectNode());
        }

        Bridge(Responder responder, JsonNode catalog) throws Exception {
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.createContext("/chat", ex -> {
                String encoded = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode body = mapper.readTree(QoderEncoding.decode(encoded));
                lastRequest.set(body);
                lastModelHeader.set(ex.getRequestHeaders().getFirst("x-model-key"));
                responder.respond(ex, body);
            });
            upstream.start();
            Path template = directory.resolve("baseprompt.json");
            Files.writeString(template, """
                    {"model_config":{},"business":{},"chat_context":{"text":{},"extra":{"originalContent":{}}},
                     "messages":[{"role":"system","content":"Default Qoder instructions."}]}
                    """);
            PatPool pool = new PatPool(List.of("test-pat"), (pat, mid, token, type) -> mapper.createObjectNode()
                    .put("id", "test-user").put("securityOauthToken", "test-token"), System::currentTimeMillis);
            modelsFile = directory.resolve("models.json");
            Files.writeString(modelsFile, catalog.toString());
            OpenAiBridge bridge = new OpenAiBridge(pool, template.toString(),
                    "http://127.0.0.1:" + upstream.getAddress().getPort() + "/chat", modelsFile.toFile());
            downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            for (var route : java.util.Map.of("/v1/chat/completions", "handleChat", "/v1/responses", "handleResponses", "/v1/models", "handleModels").entrySet()) {
                var handle = OpenAiBridge.class.getDeclaredMethod(route.getValue(), HttpExchange.class);
                handle.setAccessible(true);
                downstream.createContext(route.getKey(), ex -> {
                    try { handle.invoke(bridge, ex); }
                    catch (IllegalAccessException | InvocationTargetException e) { throw new IOException(e); }
                });
            }
            downstream.start();
        }

        HttpResponse<String> send(ObjectNode body) throws Exception {
            return send(body, "/v1/chat/completions");
        }

        HttpResponse<String> getModels() throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + downstream.getAddress().getPort() + "/v1/models"))
                    .timeout(Duration.ofSeconds(10));
            if (!Config.app().apiKeys().isEmpty()) request.header("Authorization", "Bearer " + Config.app().apiKeys().get(0));
            return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpResponse<String> send(ObjectNode body, String path) throws Exception {
            return HttpClient.newHttpClient().send(httpRequest(body, path), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpRequest httpRequest(ObjectNode body, String path) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + downstream.getAddress().getPort()
                            + path)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");
            if (!Config.app().apiKeys().isEmpty()) request.header("Authorization", "Bearer " + Config.app().apiKeys().get(0));
            return request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        }

        @Override
        public void close() {
            downstream.stop(0);
            upstream.stop(0);
        }
    }
}

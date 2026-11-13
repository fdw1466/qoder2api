package us.cubk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Builds one Responses result and, when an output stream is supplied, emits its lifecycle events. */
final class ResponsesStream {
    private static final ObjectMapper mapper = new ObjectMapper();
    private final OutputStream out;
    private final ObjectNode response;
    private final ArrayNode output;
    private final Function<String, ArrayNode> toolTextParser;
    private final Map<Integer, Tool> tools = new LinkedHashMap<>();
    private final StringBuilder pendingText = new StringBuilder();
    private ObjectNode message;
    private ObjectNode reasoning;
    private int messageIndex;
    private int reasoningIndex;
    private int sequence;
    private boolean streamingText;
    private String incompleteReason;

    ResponsesStream(OutputStream out, String model, JsonNode request, Function<String, ArrayNode> toolTextParser) {
        this.out = out;
        this.toolTextParser = toolTextParser;
        response = mapper.createObjectNode().put("id", id("resp_")).put("object", "response")
                .put("created_at", System.currentTimeMillis() / 1000).put("status", "in_progress")
                .put("model", model).put("store", false);
        response.putNull("error");
        response.putNull("incomplete_details");
        response.putNull("usage");
        for (String field : java.util.List.of("instructions", "max_output_tokens")) {
            if (request.has(field)) response.set(field, request.get(field).deepCopy());
            else response.putNull(field);
        }
        response.putNull("previous_response_id");
        response.set("tools", request.has("tools") ? request.get("tools").deepCopy() : mapper.createArrayNode());
        response.set("tool_choice", request.has("tool_choice") ? request.get("tool_choice").deepCopy() : mapper.getNodeFactory().textNode("auto"));
        response.put("parallel_tool_calls", request.path("parallel_tool_calls").asBoolean(true));
        response.set("metadata", request.has("metadata") ? request.get("metadata").deepCopy() : mapper.createObjectNode());
        output = response.putArray("output");
    }

    void start() throws IOException {
        emit("response.created", mapper.createObjectNode().set("response", response.deepCopy()));
        emit("response.in_progress", mapper.createObjectNode().set("response", response.deepCopy()));
    }

    void accept(String text, String reasoningText, ArrayNode toolCalls) throws IOException {
        if (reasoningText != null && !reasoningText.isEmpty()) appendReasoning(reasoningText);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // A structured call supersedes the textual "Tool calls:" fallback prefix.
            if (potentialToolText(pendingText.toString())) pendingText.setLength(0);
            else flushText();
            appendTools(toolCalls);
        }
        if (text == null || text.isEmpty()) return;
        if (toolTextParser == null || streamingText) {
            appendText(text);
        } else {
            pendingText.append(text);
            if (!potentialToolText(pendingText.toString())) {
                streamingText = true;
                flushText();
            }
        }
    }

    void heartbeat() throws IOException {
        if (out != null) {
            out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    void finishReason(String reason) {
        if ("length".equals(reason)) incompleteReason = "max_output_tokens";
        else if ("content_filter".equals(reason)) incompleteReason = "content_filter";
    }

    ObjectNode complete() throws IOException {
        if (!pendingText.isEmpty()) {
            ArrayNode parsed = toolTextParser == null ? null : toolTextParser.apply(pendingText.toString());
            if (parsed != null) {
                pendingText.setLength(0);
                appendTools(parsed);
            } else flushText();
        }
        if (output.isEmpty()) appendText("");
        for (Tool tool : tools.values()) {
            if (tool.item.path("name").asText().isBlank()) {
                throw new IllegalStateException("Upstream function call is missing its name");
            }
            if (tool.item.path("call_id").asText().isBlank()) tool.item.put("call_id", id("call_"));
            announceTool(tool);
        }
        for (int index = 0; index < output.size(); index++) {
            ObjectNode item = (ObjectNode) output.get(index);
            String type = item.path("type").asText();
            if ("message".equals(type)) {
                JsonNode part = item.path("content").get(0);
                ObjectNode done = indexed(item, index).put("content_index", 0).put("text", part.path("text").asText());
                done.putArray("logprobs");
                emit("response.output_text.done", done);
                emit("response.content_part.done", indexed(item, index).put("content_index", 0).set("part", part.deepCopy()));
            } else if ("reasoning".equals(type)) {
                JsonNode part = item.path("summary").get(0);
                emit("response.reasoning_summary_text.done", indexed(item, index).put("summary_index", 0)
                        .put("text", part.path("text").asText()));
                emit("response.reasoning_summary_part.done", indexed(item, index).put("summary_index", 0)
                        .set("part", part.deepCopy()));
            } else if ("function_call".equals(type)) {
                emit("response.function_call_arguments.done", indexed(item, index).put("arguments", item.path("arguments").asText()));
            }
            item.put("status", incompleteReason == null ? "completed" : "incomplete");
            emit("response.output_item.done", mapper.createObjectNode().put("output_index", index).set("item", item.deepCopy()));
        }
        response.put("status", incompleteReason == null ? "completed" : "incomplete");
        if (incompleteReason == null) response.put("completed_at", System.currentTimeMillis() / 1000);
        else response.putObject("incomplete_details").put("reason", incompleteReason);
        emit(incompleteReason == null ? "response.completed" : "response.incomplete",
                mapper.createObjectNode().set("response", response.deepCopy()));
        return response;
    }

    void fail(JsonNode error) throws IOException {
        response.put("status", "failed");
        ObjectNode failure = error.deepCopy();
        if (!failure.hasNonNull("code")) failure.put("code", error.path("type").asText("server_error"));
        response.set("error", failure);
        for (JsonNode item : output) ((ObjectNode) item).put("status", "incomplete");
        ObjectNode event = failure.deepCopy();
        event.putNull("param");
        emit("error", event);
        emit("response.failed", mapper.createObjectNode().set("response", response.deepCopy()));
    }

    private void appendText(String text) throws IOException {
        if (message == null) {
            messageIndex = output.size();
            message = mapper.createObjectNode().put("id", id("msg_")).put("type", "message")
                    .put("role", "assistant").put("status", "in_progress");
            message.putArray("content");
            output.add(message);
            emit("response.output_item.added", mapper.createObjectNode().put("output_index", messageIndex)
                    .set("item", message.deepCopy()));
            ObjectNode part = mapper.createObjectNode().put("type", "output_text").put("text", "");
            part.putArray("annotations");
            part.putArray("logprobs");
            message.withArray("content").add(part);
            emit("response.content_part.added", indexed(message, messageIndex).put("content_index", 0).set("part", part.deepCopy()));
        }
        ObjectNode part = (ObjectNode) message.path("content").get(0);
        part.put("text", part.path("text").asText() + text);
        if (!text.isEmpty()) {
            ObjectNode event = indexed(message, messageIndex).put("content_index", 0).put("delta", text);
            event.putArray("logprobs");
            emit("response.output_text.delta", event);
        }
    }

    private void appendReasoning(String text) throws IOException {
        if (reasoning == null) {
            reasoningIndex = output.size();
            reasoning = mapper.createObjectNode().put("id", id("rs_")).put("type", "reasoning").put("status", "in_progress");
            reasoning.putArray("summary");
            output.add(reasoning);
            emit("response.output_item.added", mapper.createObjectNode().put("output_index", reasoningIndex).set("item", reasoning.deepCopy()));
            ObjectNode part = mapper.createObjectNode().put("type", "summary_text").put("text", "");
            reasoning.withArray("summary").add(part);
            emit("response.reasoning_summary_part.added", indexed(reasoning, reasoningIndex).put("summary_index", 0)
                    .set("part", part.deepCopy()));
        }
        ObjectNode part = (ObjectNode) reasoning.path("summary").get(0);
        part.put("text", part.path("text").asText() + text);
        emit("response.reasoning_summary_text.delta", indexed(reasoning, reasoningIndex).put("summary_index", 0).put("delta", text));
    }

    private void appendTools(ArrayNode calls) throws IOException {
        for (int i = 0; i < calls.size(); i++) {
            JsonNode call = calls.get(i);
            int index = call.path("index").asInt(i);
            Tool tool = tools.get(index);
            if (tool == null) {
                ObjectNode item = mapper.createObjectNode().put("id", id("fc_")).put("type", "function_call")
                        .put("call_id", "").put("name", "").put("arguments", "").put("status", "in_progress");
                tool = new Tool(item, output.size());
                tools.put(index, tool);
                output.add(item);
            }
            if (call.hasNonNull("id")) tool.item.put("call_id", call.path("id").asText());
            JsonNode function = call.path("function");
            if (function.hasNonNull("name")) {
                tool.item.put("name", tool.item.path("name").asText() + function.path("name").asText());
            }
            String delta = function.path("arguments").asText("");
            tool.item.put("arguments", tool.item.path("arguments").asText() + delta);
            tool.pendingArguments.append(delta);
            if (!tool.item.path("name").asText().isBlank() && !tool.item.path("call_id").asText().isBlank()) announceTool(tool);
        }
    }

    private void announceTool(Tool tool) throws IOException {
        if (!tool.announced) {
            emit("response.output_item.added", mapper.createObjectNode().put("output_index", tool.outputIndex)
                    .set("item", tool.item.deepCopy().put("arguments", "")));
            tool.announced = true;
        }
        if (!tool.pendingArguments.isEmpty()) {
            emit("response.function_call_arguments.delta", indexed(tool.item, tool.outputIndex).put("delta", tool.pendingArguments.toString()));
            tool.pendingArguments.setLength(0);
        }
    }

    private void flushText() throws IOException {
        if (!pendingText.isEmpty()) {
            appendText(pendingText.toString());
            pendingText.setLength(0);
        }
    }

    private boolean potentialToolText(String text) {
        String candidate = text.stripLeading();
        return candidate.isEmpty() || "Tool calls:".startsWith(candidate) || candidate.startsWith("Tool calls:");
    }

    private ObjectNode indexed(JsonNode item, int index) {
        return mapper.createObjectNode().put("item_id", item.path("id").asText()).put("output_index", index);
    }

    private void emit(String type, ObjectNode event) throws IOException {
        if (out == null) return;
        event.put("type", type).put("sequence_number", sequence++);
        out.write(("event: " + type + "\ndata: " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static final class Tool {
        final ObjectNode item;
        final int outputIndex;
        final StringBuilder pendingArguments = new StringBuilder();
        boolean announced;

        Tool(ObjectNode item, int outputIndex) {
            this.item = item;
            this.outputIndex = outputIndex;
        }
    }
}

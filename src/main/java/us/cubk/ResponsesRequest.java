package us.cubk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Translates Responses text and function-call history into chat messages understood by Qoder. */
final class ResponsesRequest {
    private static final ObjectMapper mapper = new ObjectMapper();

    private ResponsesRequest() {}

    static ArrayNode messages(JsonNode request) {
        if (request.hasNonNull("previous_response_id")) {
            throw new IllegalArgumentException("previous_response_id is not supported; send the conversation history in input.");
        }
        ArrayNode messages = mapper.createArrayNode();
        String instructions = request.path("instructions").asText("");
        if (!instructions.isBlank()) messages.addObject().put("role", "system").put("content", instructions);
        JsonNode input = request.path("input");
        if (input.isTextual()) {
            if (input.asText().isBlank()) throw new IllegalArgumentException("input must not be empty.");
            messages.addObject().put("role", "user").put("content", input.asText());
        } else if (input.isArray() && !input.isEmpty()) {
            for (JsonNode item : input) append(messages, item);
        } else if (input.isObject()) {
            append(messages, input);
        } else {
            throw new IllegalArgumentException("input must be text or a non-empty array of messages and function results.");
        }
        return messages;
    }

    static ArrayNode tools(JsonNode request) {
        ArrayNode tools = mapper.createArrayNode();
        JsonNode raw = request.path("tools");
        if (raw.isMissingNode() || raw.isNull()) return tools;
        if (!raw.isArray()) throw new IllegalArgumentException("tools must be an array.");
        for (JsonNode tool : raw) {
            if (!"function".equals(tool.path("type").asText())) {
                throw new IllegalArgumentException("Only function tools are supported by this bridge.");
            }
            if (tool.has("function") && !tool.path("function").isObject()) {
                throw new IllegalArgumentException("Function tool definitions must be objects.");
            }
            ObjectNode function = tool.has("function") ? (ObjectNode) tool.path("function").deepCopy() : tool.deepCopy();
            function.remove("type");
            if (function.path("name").asText("").isBlank()) throw new IllegalArgumentException("Function tools require a name.");
            tools.addObject().put("type", "function").set("function", function);
        }
        return tools;
    }

    private static void append(ArrayNode messages, JsonNode item) {
        if (!item.isObject()) throw new IllegalArgumentException("Each input item must be an object.");
        String type = item.path("type").asText("message");
        switch (type) {
            case "message" -> {
                String role = item.path("role").asText("user");
                if (!java.util.List.of("system", "developer", "assistant", "user").contains(role)) {
                    throw new IllegalArgumentException("Unsupported message role: " + role);
                }
                ObjectNode message = mapper.createObjectNode().put("role", role);
                JsonNode content = item.path("content");
                if (!content.isTextual() && !content.isArray()) throw new IllegalArgumentException("Message content must be text or an array of text parts.");
                if (content.isArray()) {
                    for (JsonNode part : content) {
                        if (!part.path("text").isTextual()) throw new IllegalArgumentException("Only text input parts are supported by this bridge.");
                    }
                }
                message.set("content", content.deepCopy());
                messages.add(message);
            }
            case "function_call" -> {
                String callId = item.path("call_id").asText("");
                String name = item.path("name").asText("");
                if (callId.isBlank() || name.isBlank()) throw new IllegalArgumentException("Function calls require call_id and name.");
                ObjectNode assistant;
                if (!messages.isEmpty() && "assistant".equals(messages.get(messages.size() - 1).path("role").asText())) {
                    assistant = (ObjectNode) messages.get(messages.size() - 1);
                } else {
                    assistant = messages.addObject().put("role", "assistant").put("content", "");
                }
                ObjectNode call = assistant.withArray("tool_calls").addObject().put("id", callId).put("type", "function");
                JsonNode args = item.path("arguments");
                call.putObject("function").put("name", name).put("arguments", args.isTextual() ? args.asText() : args.toString());
            }
            case "function_call_output" -> {
                String callId = item.path("call_id").asText("");
                if (callId.isBlank()) throw new IllegalArgumentException("Function results require call_id.");
                JsonNode result = item.path("output");
                if (result.isMissingNode()) throw new IllegalArgumentException("Function results require output.");
                messages.addObject().put("role", "tool").put("tool_call_id", callId)
                        .put("content", result.isTextual() ? result.asText() : result.toString());
            }
            case "reasoning" -> { /* Reasoning output can be echoed in stateless history; it is not a chat role. */ }
            default -> throw new IllegalArgumentException("Unsupported input item type: " + type);
        }
    }
}

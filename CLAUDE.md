# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`qoder2api` is a single-process Java 17 HTTP server that exposes Qoder's internal LLM API ("COSY" agent backend at `api3.qoder.sh`) as an OpenAI-compatible API. A client sends standard OpenAI requests (`/v1/chat/completions`, `/v1/responses`, `/v1/models`); the bridge authenticates to Qoder using a Personal Access Token (PAT), wraps the request in a proprietary signed/encoded envelope, streams the upstream SSE response, and translates it back into OpenAI-shaped chunks.

The upstream protocol is reverse-engineered: custom Base64 alphabet, RSA+AES bearer-token construction, and MD5 request signing. These are not negotiable — the upstream rejects anything else.

## Build & run

```bash
# Build the shaded uber-jar (maven-shade-plugin → target/qoder-client-0.1.0.jar)
./mvnw.cmd clean package -DskipTests          # Windows (script-only wrapper, auto-downloads Maven 3.9.9)
mvn clean package -DskipTests                # any platform with mvn on PATH

# Run (reads config.yaml from CWD, server binds 127.0.0.1:8080 by default)
java -jar target/qoder-client-0.1.0.jar
# or Windows one-shot build+launch:
qoder2api.bat
```

JDK 17+ required (`JAVA_HOME` points to a JDK). The Dockerfile builds on `maven:3.9.9-temurin-17-alpine` and runs on `eclipse-temurin:17-jre-alpine`, exposing port 8963 (set via `QODER_HOST`/`QODER_PORT`).

Run `mvn test` for the JUnit session-expiry, concurrent-refresh, and HTTP/SSE authentication-recovery tests. The Maven Wrapper is script-only (no `.mvn/wrapper/maven-wrapper.jar`); `mvnw.cmd` downloads Maven into `%USERPROFILE%\.m2\wrapper\`.

## Configuration (`config.yaml`, read by `Config.java`)

Copy `config.example.yaml` to `config.yaml` for local use. Real `config.yaml` credentials and generated `dependency-reduced-pom.xml` are ignored by Git. `QODER_HOST` / `QODER_PORT` environment variables or system properties override the bind address/port for Docker compatibility.

All values can be overridden by environment variables — the env var name is the key uppercased with `.`/`-` → `_` (e.g. `qoder.pat` → `QODER_PAT`, `app.host` → `APP_HOST`). PAT resolution order in `OpenAiBridge.run()`: explicit arg → `config.yaml qoder.pat` → `QODER_PAT` system property → `QODER_PAT` env var. PAT is mandatory; the server refuses to start without it.

Key fields: `qoder.pat` (PAT), `qoder.base_prompt` (path to a base-prompt JSON template, defaults to `baseprompt.json` in CWD), `qoder.debug` (verbose SSE/parse logging), `app.host`, `app.port`. `Config` is a cached singleton — edit `config.yaml` and restart rather than expecting hot reload (only `models.json` is hot-reloaded, see below).

## Architecture

The request flow has two halves with different auth schemes. Understand both before changing auth or request-building code.

### 1. PAT pool: PAT → renewable session credentials

`OpenAiBridge` constructs a `PatPool`. Each request acquires a session, round-robin across configured PATs:
1. Each PAT has a stable random `machineId` / `machineToken` / `machineType` triple for the process lifetime.
2. `SignatureApiClient.exchangeJobToken(pat)` → POSTs to `center.qoder.sh/algo/api/v3/user/jobToken` with the **Signature auth scheme** (appcode `cosy`, MD5 signature over `appcode & SECRET & date`, body encoded via `QoderEncoding`). Returns identity fields (name, id, userType, securityOauthToken, refreshToken).
3. `BearerBuilder.newSession(identity, machineId, machineToken, machineType)` → builds a `SessionContext`:
   - Generates a 16-byte AES `tempKey`.
   - `cosyKey` = RSA-encrypt(tempKey) with a hardcoded server public key (`BearerBuilder.SERVER_PUBKEY_PEM`).
   - `info` = AES-CBC-encrypt(authPayloadJson(identity), tempKey), then base64. The tempKey is both the AES key *and* the IV (zero-IV reuse by design — it's the upstream's protocol).
4. `PatPool` caches the session and its client together, checks `expireTime` (epoch seconds or milliseconds), and refreshes shortly before expiry. Missing expiry falls back to a 30-minute cache lifetime. Refresh is on demand, with a lock per PAT so concurrent requests share one renewal and other PATs remain accessible.
5. All chat/Responses calls use `PatPool.openStreamLines`, which renews a rejected session and retries authentication once, only before any upstream SSE data has been delivered. HTTP/SSE 401 and explicit login/token failures on 403 trigger renewal; quota code 115 remains a rate limit. A failed login has a 30-second retry cooldown rather than permanently disabling the PAT. When the pool cannot log in, handlers return 503 rather than misreporting 429.

Note: `JobTokenClient.java` duplicates the same jobToken exchange logic that `SignatureApiClient.exchangeJobToken` does — `SignatureApiClient` is the one actually wired into the constructor. `LocalAuth.java` reads machine-id/user creds from `~/.qoder/.auth` (a standalone diagnostic tool with its own `main`, not used by the server).

### 2. Per-request: signed COSY bearer call (`BearerApiClient`)

Every chat request is built in `OpenAiBridge.handleChat` (streaming + non-streaming) by deep-copying `templateBase` (parsed from `baseprompt.json`), injecting fresh UUIDs/timestamps, the user's prompt, model key, and the rebuilt `messages` array. The body is then sent via `BearerApiClient.openStreamLines`:
1. Body is serialized to JSON bytes, then **encoded** with `QoderEncoding.encode` (custom-alphabet base64 with a third-rotation rearrangement — see below). The encoded string *is* the HTTP body (not JSON).
2. `payloadB64` = `BearerBuilder.buildPayloadB64(sess.info())` — a sorted map of `{cosyVersion, ideVersion, info, requestId, version}` base64-encoded.
3. `sig` = MD5 of `payloadB64 \n cosyKey \n date \n encodedBody \n pathWithoutAlgo` (the `/algo` prefix is stripped from the path before signing).
4. `Authorization: Bearer COSY.<payloadB64>.<sig>` plus a fixed set of `cosy-*` headers (machineId, machinetoken, machinetype, user=identity.uid, key=cosyKey, date, clientip hardcoded to `169.254.198.161`, user-agent `Go-http-client/2.0`, etc.).

There are **two HTTP clients** in `BearerApiClient` doing the same signing:
- `openStreamLines` uses Apache HttpClient 5 (`httpclient5`) with a manual byte-by-byte SSE reader on a background thread (needed because the upstream SSE can stall for long stretches — it deliberately does *not* treat silence as EOF, only a real `-1` byte). This is the path used by all chat requests. Rate-limit detection (`maybeThrowRateLimit`) parses every SSE line for a tunneled `statusCodeValue=403/429` + `body.code=115` and throws `UpstreamRateLimitException`, which the handler maps to HTTP 429.
- `call` / `openCallStream` use `java.net.http.HttpClient` — older code paths, retained but not on the hot path for chat.

### 3. Response translation (`OpenAiBridge`)

The upstream returns SSE `data:` lines whose payload is `{"body": "<inner-json-string>"}`. `extractDelta` unwraps the inner JSON and pulls `choices[].delta.{role,content,tool_calls}`. For streaming, `StreamAccumulator` emits OpenAI-shaped `chat.completion.chunk` SSE events; for non-streaming, content is concatenated and emitted as one `chat.completion`. Tool-call handling is the most intricate part:

- If the client sent `tools`, `toolsEnabled=true`. Tool calls may arrive either as structured `delta.tool_calls` (accumulated by `ToolCallAccumulator`, which handles incremental argument assembly and `index`-based ordering) **or** as literal text matching the `Tool calls:\n[...]` convention — `parseToolCallsText` recovers those as a fallback.
- `buildQoderMessages` rebuilds the incoming message array into Qoder's shape, with careful handling of multi-turn tool-call history: assistant messages with tool calls are only kept structured if a following `tool` response exists (`hasResolvedToolResponse`); otherwise they're collapsed into a textual summary (`summarizeUnresolvedToolCalls`) to avoid sending dangling tool_calls upstream. This is what commit `a856f53` ("normalize multi-turn tool call history") fixed.
- `extractLatestUserPrompt` strips injected agent boilerplate (`<system-reminder>`, `<attached_files>`, "You are Claude Code…", etc.) that clients like Claude Code / Cursor embed inside `role=user` messages, so only the real user prompt reaches upstream.

### 4. `models.json` (hot-reloaded)

`/v1/models` serves entries from `models.json` where `enable:true`. `loadModelsConfig` caches by file `lastModified()` mtime — editing `models.json` on disk takes effect on the next request without a restart. Model `id`s are passed straight through as the upstream `model_config.key` (and `x-model-key` header); `auto` is the default (`is_default:true`). `is_reasoning`, `is_vl`, `price_factor`, `source`, `strategies` etc. are template fields consumed by `baseprompt.json`, not by the Java code.

### `QoderEncoding` — the custom base64

`encode`/`decode` are not standard base64. Three steps: (1) standard base64 the bytes; (2) rotate the string by thirds — `lastThird + middleThird + firstThird` where each third is `n/3` long; (3) remap each char through `STD_ALPHABET → CUSTOM_ALPHABET` (a fixed 64-char alphabet containing punctuation like `@#&*()^`), with `=` padding remapped to `$`. `decode` inverts. If you touch the alphabets or the rotation, encode and decode *must* stay symmetric or every upstream request 400s. There is no test guarding this.

## Conventions

- One class per file, package `us.cubk`, all classes `final` with private constructors where they're utility classes.
- Jackson (`ObjectMapper`) for all JSON; a single `static final ObjectMapper` per class. No records for request/response DTOs beyond small auth records (`AuthIdentity`, `SessionContext`, `Session`, `BridgeDelta`) — most bodies are built imperatively with `ObjectNode`/`ArrayNode`.
- `com.sun.net.httpserver.HttpServer` with a fixed thread pool of 8 for the local OpenAI API. No web framework.
- Logging is `System.out.println("[bridge] ...")` / `System.err.println("[stream] ...")`, gated behind `Config.debug()` for verbose noise. Keep this style when adding logs.
- Hardcoded upstream constants that look like they should be config (`cosy-clientip=169.254.198.161`, `cosy-version=0.1.43`, `user-agent=Go-http-client/2.0`, the server RSA pubkey, `Signature.SECRET`) are **protocol requirements**, not tunables — changing them breaks upstream auth.

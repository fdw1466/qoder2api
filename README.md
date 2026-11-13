# Qoder2API

![q](images/img.png)

使用 Java 17+，运行前将 `config.example.yaml` 复制为 `config.yaml`，设置 `qoder.pat` 和可选的 `app.api_keys`。也可以通过 `QODER_PAT`、`APP_API_KEYS` 提供逗号分隔的凭据。`config.yaml` 仅保留在本机，不提交到 Git。

本分支保留多 PAT 轮询、API Key 验证、`/v1/responses`、`/v1/models` 和 `models.json` 热更新。Docker 使用的 `QODER_HOST`、`QODER_PORT` 仍然可用。

`models.json` 中配置了 `name` 时，`/v1/models` 会将它作为对外的 `id` 返回；请求中的模型名称会映射回真实 `id` 后发往上游，例如 `GLM-5.3-Flash` → `gfmodel`。Chat Completions 和 Responses 均支持该映射，原始 `id` 仍可直接使用；没有 `name` 或名称为空时沿用原 `id`。修改配置后列表和名称映射会一同热更新。

`POST /v1/responses` 支持 `stream: true`，例如 `{"model":"gfmodel","input":"你好","stream":true}`。流中使用 `response.output_text.delta`、`response.function_call_arguments.delta` 等命名 SSE 事件，以 `response.completed`、`response.incomplete` 或 `response.failed` 结束。文本、推理文本、函数工具及其多轮结果均可转换；`stream: false` 返回同样结构的完整结果。

Responses 会话无服务端存储，继续对话时请在 `input` 中携带消息和 `function_call` / `function_call_output` 历史；`previous_response_id`、内置搜索工具和非文本输入暂不支持，会返回明确的 400 错误。

会话会在接近 `expireTime` 时自动更新；闲置期间无需保活，下一次请求会先检查有效期。上游未返回有效期时，会话最多缓存 30 分钟。

遇到 HTTP 或 SSE 中的登录失效错误，会重新用 PAT 登录并重试一次。已经收到流式数据的请求不会重放。临时登录失败会冷却 30 秒后允许再次尝试，不会永久禁用 PAT；PAT 本身失效时仍需更换配置。

运行 `mvn test` 验证会话刷新和认证恢复，运行 `mvn package` 生成 `target/qoder-client-0.1.0.jar`。更新代码后需要重新启动服务。

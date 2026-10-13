# Qoder2API

![q](images/img.png)

使用 Java 17+，运行前将 `config.example.yaml` 复制为 `config.yaml`，设置 `qoder.pat` 和可选的 `app.api_keys`。也可以通过 `QODER_PAT`、`APP_API_KEYS` 提供逗号分隔的凭据。`config.yaml` 仅保留在本机，不提交到 Git。

本分支保留多 PAT 轮询、API Key 验证、`/v1/responses`、`/v1/models` 和 `models.json` 热更新。Docker 使用的 `QODER_HOST`、`QODER_PORT` 仍然可用。

会话会在接近 `expireTime` 时自动更新；闲置期间无需保活，下一次请求会先检查有效期。上游未返回有效期时，会话最多缓存 30 分钟。

遇到 HTTP 或 SSE 中的登录失效错误，会重新用 PAT 登录并重试一次。已经收到流式数据的请求不会重放。临时登录失败会冷却 30 秒后允许再次尝试，不会永久禁用 PAT；PAT 本身失效时仍需更换配置。

运行 `mvn test` 验证会话刷新和认证恢复，运行 `mvn package` 生成 `target/qoder-client-0.1.0.jar`。更新代码后需要重新启动服务。

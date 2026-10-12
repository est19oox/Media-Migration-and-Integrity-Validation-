# MMIV 事件契约 v2 / MMIV Event Contract v2

## 中文说明

此目录保存 Reporting Service 使用的共享、冻结版消息契约。Discovery、Transfer 和 Integrity Service 分别发布各自的结果事件；Reporting Service 使用 `migrationId` 和 `fileId` 汇总这些事件。

RabbitMQ 仅负责传递事件，不负责决定迁移流程的执行顺序。各业务服务仍应通过现有 API 或业务流程完成自己的工作。

### 文件说明

- `mmiv-event.schema.json`：统一 JSON Schema，供 CI 和 Reporting Service 自动校验消息。
- `examples/`：各服务可以直接参考的合法消息示例。
- 开发者应先复制最接近的 example，再把示例值替换为真实运行数据。
- 不要在各微服务中复制并修改独立版本的 Schema，以免契约逐渐不一致。

### 发布方职责

| 服务 | 事件 | Routing key |
|---|---|---|
| Discovery | `MIGRATION_STARTED` | `migration.started` |
| Discovery | `FILE_DISCOVERY_COMPLETED` | `discovery.file.completed` |
| Discovery | `MIGRATION_DISCOVERY_COMPLETED` | `discovery.migration.completed` |
| Transfer | `FILE_TRANSFER_COMPLETED` | `transfer.file.completed` |
| Integrity | `INTEGRITY_VALIDATION_COMPLETED` | `integrity.validation.completed` |

所有事件都必须以持久化 JSON 消息发布到 durable topic exchange `mmiv.events`，并设置：

```text
content_type = application/json
message_id = JSON 中的 eventId
```

### 每个服务应参考的示例

Discovery Service：

- `examples/01-migration-started.json`
- `examples/02-file-discovery-accepted.json`
- `examples/03-file-discovery-rejected.json`
- `examples/04-migration-discovery-completed.json`

Transfer Service：

- `examples/05-file-transfer-completed.json`

Integrity Service：

- `examples/06-integrity-validation-completed.json`

### 标识符归属

- Discovery 为每次迁移创建一个 `migrationId`，并为每个文件创建稳定的 `fileId`。
- Transfer 和 Integrity 必须继续使用 Discovery 提供的 `migrationId` 和 `fileId`，不能重新生成。
- Transfer 为传输结果创建 `transferId`，Integrity 必须继续使用该 `transferId`。
- 每个发布方为事件创建唯一的 `eventId`。同一逻辑事件重试或重新投递时必须沿用原来的 `eventId`。
- `manifestMediaId` 是可选的外部业务标识，不等同于 `fileId`。

### 发布规则

- 使用 `schemaVersion: 2`，并严格遵守 Schema 定义的事件类型与发布服务对应关系。
- 使用 UTC ISO-8601 时间，例如 `2026-10-11T10:30:00Z`。
- 没有值的可选字段应直接省略，不要发送无意义的 `null`。
- 成功状态必须省略 `failure`。
- 失败、拒绝或错误状态必须包含经过清理的 `failure.code`、`failure.message` 和 `failure.retryable`。
- 文件路径只能使用配置的逻辑 `rootId` 下、以 `/` 分隔的规范化 `relativePath`。
- 不允许擅自增加字段。不兼容的修改必须创建新的 Schema 版本。

### 安全限制

消息中不得包含：

- Windows、macOS 或 Linux 主机的绝对文件路径；
- 访问密钥、密码、Token 或其他凭据；
- 程序堆栈信息；
- 实际的源文件或目标文件 checksum 值。

允许发送 `checksumAlgorithm` 和完整性验证结论，例如 `PASSED` 或 `FAILED`。

### Schema 与 examples 的关系

`mmiv-event.schema.json` 使用 `oneOf` 定义五种合法事件。Schema 会根据 `eventType` 检查：

- `sourceService` 是否正确；
- 必填 ID 是否存在；
- `data` 中的字段是否符合该事件；
- 状态值是否合法；
- 成功和失败消息是否正确包含或省略 `failure`；
- 是否存在未知字段、危险路径或 checksum 值。

队友实现 publisher 时可以主要参考 examples；Schema 由 CI 和 Reporting Service 自动执行严格校验。

---

## English

This directory contains the shared, frozen message contract used by the Reporting Service. Discovery, Transfer, and Integrity Service publish independent outcome events; Reporting joins them using `migrationId` and `fileId`.

RabbitMQ transports events but does not orchestrate the migration workflow. Each business service must still perform its work through the existing APIs or business flow.

### Files

- `mmiv-event.schema.json`: the shared JSON Schema used by CI and Reporting Service to validate messages automatically.
- `examples/`: valid message examples that each publisher can follow directly.
- Developers should copy the closest example first and replace its sample values with real runtime data.
- Do not create and modify separate copies of the Schema in individual microservices, because those copies could drift apart.

### Publisher ownership

| Service | Event | Routing key |
|---|---|---|
| Discovery | `MIGRATION_STARTED` | `migration.started` |
| Discovery | `FILE_DISCOVERY_COMPLETED` | `discovery.file.completed` |
| Discovery | `MIGRATION_DISCOVERY_COMPLETED` | `discovery.migration.completed` |
| Transfer | `FILE_TRANSFER_COMPLETED` | `transfer.file.completed` |
| Integrity | `INTEGRITY_VALIDATION_COMPLETED` | `integrity.validation.completed` |

Publish every event as a persistent JSON message to the durable topic exchange `mmiv.events`, with:

```text
content_type = application/json
message_id = the eventId from the JSON body
```

### Examples for each service

Discovery Service:

- `examples/01-migration-started.json`
- `examples/02-file-discovery-accepted.json`
- `examples/03-file-discovery-rejected.json`
- `examples/04-migration-discovery-completed.json`

Transfer Service:

- `examples/05-file-transfer-completed.json`

Integrity Service:

- `examples/06-integrity-validation-completed.json`

### Identifier ownership

- Discovery creates one `migrationId` for a migration and one stable `fileId` for each file.
- Transfer and Integrity must reuse the `migrationId` and `fileId` supplied by Discovery; they must not generate replacements.
- Transfer creates the `transferId` for the transfer result, and Integrity must reuse that `transferId`.
- Each publisher creates a unique `eventId`. Retrying or redelivering the same logical event must reuse the original `eventId`.
- `manifestMediaId` is an optional external business reference and is not the same as `fileId`.

### Publishing rules

- Use `schemaVersion: 2` and the exact event/source pairing defined by the Schema.
- Use UTC ISO-8601 timestamps such as `2026-10-11T10:30:00Z`.
- Omit optional fields when unavailable; do not send meaningless `null` values.
- A successful status must omit `failure`.
- Failed, rejected, or error statuses must include sanitized `failure.code`, `failure.message`, and `failure.retryable` values.
- Publish only a normalized `/`-separated `relativePath` beneath the configured logical `rootId`.
- Do not add fields locally. An incompatible change requires a new Schema version.

### Security restrictions

Messages must not contain:

- absolute Windows, macOS, or Linux host paths;
- access keys, passwords, tokens, or other credentials;
- stack traces;
- actual source or destination checksum values.

`checksumAlgorithm` and the integrity result, such as `PASSED` or `FAILED`, are allowed.

### Relationship between the Schema and examples

`mmiv-event.schema.json` uses `oneOf` to define the five valid event types. Based on `eventType`, the Schema verifies:

- that `sourceService` is correct;
- that the required identifiers are present;
- that the fields inside `data` match the event;
- that status values are allowed;
- that successful and unsuccessful messages omit or include `failure` correctly;
- that the message contains no unknown fields, unsafe paths, or checksum values.

Publisher developers can work mainly from the examples. CI and Reporting Service use the Schema for strict automatic validation.

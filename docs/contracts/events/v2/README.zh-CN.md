# MMIV 事件契约 v2

[English](README.md) | [简体中文](README.zh-CN.md)

此目录保存 Reporting Service 使用的共享、冻结版消息契约。Discovery、Transfer
和 Integrity Service 分别发布各自的结果事件；Reporting Service 使用
`migrationId` 和 `fileId` 汇总这些事件。

RabbitMQ 仅负责传递事件，不负责决定迁移流程的执行顺序。各业务服务仍应通过
现有 API 或业务流程完成自己的工作。

## 文件

- `mmiv-event.schema.json`：统一 JSON Schema，供 CI 和 Reporting Service 自动
  校验消息。
- `examples/`：各服务可直接参考的合法消息示例。
- 开发者应先复制最接近的示例，再将示例值替换为实际运行数据。
- 不要在各微服务中复制并修改独立 Schema，以免契约逐渐不一致。

## 发布方职责

| 服务      | 事件                             | Routing key                      |
| --------- | -------------------------------- | -------------------------------- |
| Discovery | `MIGRATION_STARTED`              | `migration.started`              |
| Discovery | `FILE_DISCOVERY_COMPLETED`       | `discovery.file.completed`       |
| Discovery | `MIGRATION_DISCOVERY_COMPLETED`  | `discovery.migration.completed`  |
| Transfer  | `FILE_TRANSFER_COMPLETED`        | `transfer.file.completed`        |
| Integrity | `INTEGRITY_VALIDATION_COMPLETED` | `integrity.validation.completed` |

所有事件都必须以持久化 JSON 消息发布到 durable topic exchange `mmiv.events`，
并设置：

```text
content_type = application/json
message_id = JSON 中的 eventId
```

## 各服务示例

Discovery Service：

- `examples/01-migration-started.json`
- `examples/02-file-discovery-accepted.json`
- `examples/03-file-discovery-rejected.json`
- `examples/04-migration-discovery-completed.json`

Transfer Service：

- `examples/05-file-transfer-completed.json`

Integrity Service：

- `examples/06-integrity-validation-completed.json`

## 标识符归属

- Discovery 为每次迁移创建一个 `migrationId`，并为每个文件创建稳定的
  `fileId`。
- Transfer 和 Integrity 必须复用 Discovery 提供的 `migrationId` 和 `fileId`，
  不得重新生成。
- Transfer 为传输结果创建 `transferId`，Integrity 必须复用该 ID。
- 每个发布方为事件创建唯一 `eventId`。同一逻辑事件重试或重新投递时必须复用
  原 `eventId`。
- `manifestMediaId` 是可选外部业务标识，不等同于 `fileId`。

## 发布规则

- 使用 `schemaVersion: 2`，严格遵守 Schema 定义的事件类型和发布服务对应关系。
- 使用 UTC ISO-8601 时间，例如 `2026-10-11T10:30:00Z`。
- 未提供值的可选字段应省略，不发送无意义的 `null`。
- 成功状态必须省略 `failure`。
- 失败、拒绝或错误状态必须包含经过清理的 `failure.code`、
  `failure.message` 和 `failure.retryable`。
- 文件路径只能是配置逻辑 `rootId` 下、使用 `/` 分隔符的规范化
  `relativePath`。
- 不得自行增加字段。不兼容的修改必须创建新的 Schema 版本。

## 安全限制

消息中不得包含：

- Windows、macOS 或 Linux 主机的绝对文件路径；
- 访问密钥、密码、token 或其他凭据；
- 程序堆栈信息；
- 源文件或目标文件的实际 checksum 值。

允许发送 `checksumAlgorithm` 和完整性校验结果，例如 `PASSED` 或 `FAILED`。

## Schema 与示例的关系

`mmiv-event.schema.json` 使用 `oneOf` 定义五种合法事件，并根据 `eventType`
检查：

- `sourceService` 是否正确；
- 必需 ID 是否存在；
- `data` 字段是否符合该事件；
- 状态值是否合法；
- 成功和失败消息是否正确省略或包含 `failure`；
- 是否包含未知字段、危险路径或 checksum 值。

Publisher 开发者可主要参考示例；CI 和 Reporting Service 会使用 Schema 严格
自动校验。

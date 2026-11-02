# MMIV API 与事件契约

MMIV 服务通过明确的 HTTP 接口和带版本的 RabbitMQ 事件契约进行通信。契约与负责实现它们的服务一起维护。

## HTTP API

每个服务负责自己的公开端点，并对其执行的操作验证身份和权限。Gateway 提供浏览器访问路由；服务间客户端调用服务 API，不共享内部 Java 模型，也不直接访问其他服务的数据库。

当前服务通信边界包括：

| 提供方 → 消费方         | 契约用途                   |
| ----------------------- | -------------------------- |
| User Access → Discovery | 权限、用户资料和文件夹检查 |
| Discovery → Transfer    | 计划分配与解析             |
| Transfer → Discovery    | 传输执行结果               |
| Integrity → Transfer    | 验证请求与响应             |
| Discovery → Integrity   | 授权计划解析               |

适配器边界和安全行为见[服务通信文档](../architecture/service-communication.md)。各服务的端点详情由对应服务文档维护；Transfer API 测试契约位于 [`tests/api/transfer-openapi.json`](../../tests/api/transfer-openapi.json)。

## 事件契约

Discovery、Transfer 和 Integrity 发布生命周期事件，由 Reporting 消费。[事件 v2 指南](../../contracts/events/v2/README.md)介绍事件所有权、标识符、传递规则和安全限制；[JSON Schema](../../contracts/events/v2/mmiv-event.schema.json)及示例是统一的机器可读契约。

## 变更管理

明确维护请求/响应格式、状态码、标识符和错误行为。接口变化时，应更新负责服务的文档和相关契约测试。不兼容的事件变更需要创建新的 Schema 版本；不要在各服务中复制共享事件 Schema。

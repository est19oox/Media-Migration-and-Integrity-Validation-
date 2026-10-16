# Transfer 与 Integrity 校验

[English](transfer-integrity.md) | [简体中文](transfer-integrity.zh-CN.md)

两个服务都已注册到现有 `service-registry`（端口 8761）。Transfer 启用
OpenFeign 和 Spring Cloud LoadBalancer。`@FeignClient(name = "integrity-service")`
会解析注册的服务名，而不是直接将注册中心作为 API endpoint 调用。

## 契约

S3 上传完成后，Transfer 调用 `POST /api/integrity/validate`：

```json
{
  "transferId": "generated-uuid",
  "bucket": "media-migration-dev-landing",
  "objectKey": "uploads/generated-uuid/notes.txt",
  "sourceChecksum": "64-character-SHA-256-hex-string"
}
```

Integrity 返回相同的 `transferId`、`bucket`、`objectKey` 和 `sourceChecksum`，
并提供 `algorithm: SHA-256`、`destinationChecksum`、`valid` 及
`validationResult: PASSED|FAILED`。原有 S3 哈希和审计逻辑保持不变。DTO/
controller 会回显 `transferId` 并拒绝无效输入。部署时须同时部署这两项服务
变更。旧请求仍可不提供 `transferId`；更新后的 Transfer 要求响应 ID 匹配。

源路径只在 Transfer 所在主机/容器上有效，其他服务无法直接读取。Transfer
对实际上传到 S3 的 staging 文件计算 checksum 并发送该值；Integrity 独立
读取 S3 对象并计算其内容哈希。服务器路径传输会使用 staging 快照，避免源文件
在计算哈希和上传之间发生变化。

## 用户可见结果

传输/上传响应在原有文件元数据上增加 `transferId`、`validationResult`、
`success` 和 `message`：

| validationResult | success | 含义                                                      |
| ---------------- | ------- | --------------------------------------------------------- |
| `PASSED`         | `true`  | S3 上传成功且完整性校验通过                               |
| `FAILED`         | `false` | S3 上传成功，但 checksum 不一致                           |
| `UNAVAILABLE`    | `false` | 上传成功；超时、HTTP 错误、服务缺失或无效响应导致无法确认 |

HTTP 200 表示工作流完成，客户端仍须检查 `success` 和 `validationResult`。
S3 上传失败继续返回 HTTP 502，且不会调用 Integrity。文件夹上传只将
`PASSED` 文件计为成功；失败计数也包括未确认的校验。每个文件保留自己的结果、
元数据和 `transferId`。上传页面显示这些结果。

上传后最多执行三次校验（首次加两次重试），间隔一秒。`FAILED` 和
`UNAVAILABLE` 均会重试。所有尝试使用相同的 `transferId`、checksum 和对象键；
校验期间不会重新上传。`PASSED` 会立即停止重试并保留对象。

若校验仍未成功，Transfer 会自动删除此次上传的 S3 版本。它使用 PutObject
返回的 versionId 和 ETag；未启用版本控制时使用带 `If-Match` 的条件删除。
若 ETag 已变化，则中止清理，避免删除其他上传的对象。删除的是新版本而非旧版本；
旧版本可能重新变为可见。`infrastructure/terraform/main.tf` 中的 EC2 role
除既有 `s3:DeleteObject` 外还授予 `s3:DeleteObjectVersion`。部署前须应用
Terraform；其他凭据也必须拥有相应权限。

响应新增 `cleanupStatus`：校验通过为 `NOT_REQUIRED`，清理成功为 `DELETED`，
无法确认清理为 `DELETE_FAILED`。后者会保留 `success: false` 并提示需要人工
清理。浏览器会隐藏失败传输的预览/下载链接。文件夹中的每个文件独立执行此策略，
遇到失败后继续处理后续文件。

清理对网络和临时 S3 错误最多重试三次，间隔一秒；拒绝访问、对象变化冲突及
其他永久客户端错误会立即停止。`NoSuchKey` 表示对象已清理。应用级清理重试与
AWS SDK 内部请求重试分开计算。

该实现为同步调用：HTTP 请求会等待重试和清理。没有持久化任务、重试 endpoint
或传输状态数据库；进程崩溃或删除失败仍可能需要人工清理。Integrity endpoint
按对象键读取，不验证特定 S3 版本，因此校验期间应避免并发覆盖。

## 运行

使用项目现有 Java 25 和 Maven 配置：

```sh
mvn -B -f services/service-registry/pom.xml spring-boot:run
mvn -B -f services/integrity-service/pom.xml spring-boot:run
mvn -B -f services/transfer-service/pom.xml spring-boot:run
```

两个客户端使用 `EUREKA_DEFAULT_ZONE`，默认
`http://localhost:8761/eureka/`。请设置为各主机/容器可访问的 Registry 地址，
并确保 Transfer 可访问注册的实例 IP 和端口。以上命令只启动列出的三个进程；
完整传输流程还依赖已配置的 User Access、Discovery、RabbitMQ、Oracle 及 S3
访问。完整 Compose 配置见[本地开发指南](../development/local-setup.zh-CN.md)。
两服务都需要 AWS 凭据/区域；Integrity 需要 S3 GetObject 权限，Transfer 使用
其已配置的 Bucket 和 PutObject 权限。Eureka 默认启用。

Feign timeout 可用 `INTEGRITY_CONNECT_TIMEOUT_MS`（默认 3000）和
`INTEGRITY_READ_TIMEOUT_MS`（默认 30000）配置。Read timeout 覆盖 S3 读取、
哈希和审计操作，应按最大文件大小调整。Feign 使用默认不重试；应用显式重试
校验且不会重复上传。

| 环境变量                   | 默认值 | 允许范围        |
| -------------------------- | -----: | --------------- |
| `INTEGRITY_MAX_ATTEMPTS`   |      3 | 总尝试次数 1–10 |
| `INTEGRITY_RETRY_DELAY_MS` |   1000 | 0–5000 毫秒     |
| `CLEANUP_MAX_ATTEMPTS`     |      3 | 总尝试次数 1–10 |
| `CLEANUP_RETRY_DELAY_MS`   |   1000 | 0–5000 毫秒     |

代理/客户端 timeout 应覆盖最多三次 Feign read timeout、重试间隔和清理时间。
每次校验尝试都可能新增 Integrity 审计记录；`transferId` 用于关联，不代表
持久化幂等记录。

## 验证

```sh
mvn -B -f services/transfer-service/pom.xml clean verify
mvn -B -f services/integrity-service/pom.xml clean verify
```

测试覆盖 checksum、请求/响应关联、差异、无效响应、HTTP 错误、上传顺序、
文件夹统计和临时文件清理。`IntegrityFeignContractTest` 使用本地 HTTP server
及发现到的测试实例，在不访问 AWS 的情况下覆盖真实 Feign 编解码和
LoadBalancer；`IntegrityControllerContractTest` 检查 Integrity JSON 契约。

重试/清理更新的既有验证记录：Java 25 下 Transfer `clean verify` 有 107 项通过，
无失败、错误或跳过；覆盖即时/延迟成功、耗尽重试、删除重试/失败、版本及条件
删除、服务器路径、文件夹和临时文件清理。没有执行真实 AWS 传输、S3 删除或
Terraform apply。

# Reporting Service

[English](reporting.md) | [简体中文](reporting.zh-CN.md)

Reporting 从 RabbitMQ 消费 MMIV v2 业务结果事件，在 MongoDB 保存不可变审计
记录，并为每次迁移生成可查询报告。它不编排 Discovery、Transfer 或 Integrity，
也不读取这些服务的关系型数据库表。

一次文件夹上传就是一个迁移批次：Discovery 为批次内所有文件分配同一个
`migrationId`，Reporting 把所有文件结果聚合到这一份报告。例如上传 160 个文件
只生成一张汇总报告，而不是在列表中铺开 160 张报告。普通用户默认只看到失败
文件；管理员排查时可在“仅失败”和“全部文件”之间切换。两种文件视图都支持
分页，并保留完整的结果总数。

冻结的发布方契约和示例见[事件契约 v2](../contracts/events/v2/README.zh-CN.md)。
Discovery 发布迁移/发现结果，Transfer 发布存储结果，Integrity 发布校验结果。
重复投递具有幂等性；无效或耗尽重试次数的事件进入 `reporting.events.dlq`。

## 访问模型

浏览器和外部客户端通过 8080 端口的 API Gateway 访问。Reporting 会再次验证
转发的 JWT，并自行执行数据所有权检查：

| Gateway endpoint                             | 权限       | 用途                              |
| -------------------------------------------- | ---------- | --------------------------------- |
| `GET /api/reports/mine`                      | 已认证用户 | 分页列出 JWT subject 所拥有的报告 |
| `GET /api/reports/{migrationId}`             | 报告所有者 | 获取单份脱敏报告                  |
| `GET /api/reports/{migrationId}/files`       | 报告所有者 | 分页读取稳定的文件结果            |
| `GET /api/reports/{migrationId}/timeline`    | 报告所有者 | 脱敏业务时间线                    |
| `GET /api/admin/reports`                     | 管理员     | 跨用户报告搜索                    |
| `GET /api/admin/reports/{migrationId}/files` | 管理员     | 失败/全部文件排查                 |
| `GET /api/history`                           | 管理员     | 运行审计历史                      |
| `GET /api/dashboard`                         | 管理员     | 汇总运行面板                      |

用户不能通过查询参数选择其他所有者。用户时间线不会返回内部异常、基础设施
地址、凭据、绝对路径或 checksum。服务的 8084 回环端口仅用于本地诊断；生产
访问必须经过 Gateway，前端不得直接调用 Reporting。

## 存储与处理保障

- `audit_events` 保存不可变的已消费事件。
- `migration_reports` 保存当前迁移投影。
- 每个文件的事件仍会单独保留用于审计，但不会各自生成迁移报告。
- 仅在审计记录和报告投影均持久化后才确认消息。
- 同一 `eventId` 若对应不同内容会被作为冲突拒绝。
- 事件可能乱序到达；各阶段归属字段会安全地重新计算。
- 延迟到达的终结事件可恢复 `INCOMPLETE` 报告。
- MongoDB 和 RabbitMQ 与 Java 服务运行在不同容器中。

旧 Transfer AWS SQS 运行日志与 Reporting 无关，Reporting 不消费该日志。

## 构建和单元测试

使用 Java 25 和仓库 Maven Wrapper：

```sh
services/user-access-service/mvnw -B -f services/reporting-service/pom.xml clean verify
```

单元测试会关闭外部 listener 和健康贡献项。真实 Compose 运行环境仍会检查
RabbitMQ 和 MongoDB 健康状态。

## 独立 Smoke Test

Reporting 单独开发时，现有 Smoke Test 会启动 Reporting、RabbitMQ 和 MongoDB，
发布五个成功路径示例，并验证重复投递不会改变统计：

```powershell
powershell -ExecutionPolicy Bypass -File services/reporting-service/scripts/smoke.ps1
```

```sh
sh services/reporting-service/scripts/smoke.sh
```

停止该独立环境并保留卷：

```sh
docker compose -f services/reporting-service/compose.yml down
```

## Transfer 停机集成接受测试

启动完整集成环境后运行：

```sh
bash tests/api/verify-reporting-with-transfer-down.sh
```

该测试创建真实 User Access 账户，通过 Gateway 登录，发布
`initiatedBy` 与该用户 JWT subject 相同的报告，并在 Transfer 停止期间验证前端、
会话、报告列表、详情、文件结果和时间线仍可用。清理阶段会重启 Transfer 并
删除临时账户和 MongoDB 测试数据。

集成 Compose 仅将 RabbitMQ 管理端口 `127.0.0.1:15672` 暴露给本机，以便
接受测试发布 fixture，不会将管理 UI 公开到远程网络。

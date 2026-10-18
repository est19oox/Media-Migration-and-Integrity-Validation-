# MMIV 架构

> **状态：草案**
>
> 本文总结当前 MMIV 架构以及仍需团队确认的关键决策。

## 1. 待确定事项

确定跨服务 API 前，团队需要达成以下共识：

1. 端到端的媒体迁移流程是什么？
2. 哪个服务调用哪个服务？
3. Discovery 是否需要 User Access 的 `READ` 授权？
4. Transfer 是否需要 User Access 的 `READ` / `WRITE` 授权？
5. 服务之间共享哪些标识符？
6. `userId` 从哪里获得？
7. 谁负责协调 Transfer → Integrity → Reporting？
8. GitHub Actions 和 Jenkins 分别承担什么职责？

## 2. 当前服务

| 服务        | 端口 | 职责                   |
| ----------- | ---: | ---------------------- |
| Transfer    | 8081 | 在存储位置之间传输媒体 |
| Discovery   | 8085 | 发现媒体和资源         |
| User Access | 8083 | 检查用户访问权限       |
| Reporting   | 8084 | 生成迁移和验证报告     |
| Integrity   | 8082 | 验证已迁移媒体的完整性 |

每个服务都独立构建并容器化。

当前团队/服务分组如下：

```text
Discovery ────── User Access

Transfer ────── Integrity ────── Reporting
```

该分组**不代表**最终的运行时调用流程。

## 3. 草案领域上下文

| 上下文         | 职责                           |
| -------------- | ------------------------------ |
| Discovery      | 从来源发现媒体资源             |
| Access Control | 判断用户是否可以对资源执行操作 |
| Migration      | 将媒体从来源传输到目标         |
| Integrity      | 验证已迁移媒体的完整性         |
| Reporting      | 生成迁移和验证报告             |

仍需确认的潜在关系：

```text
Discovery ── READ? ─────────► Access Control

Migration ── READ/WRITE? ───► Access Control

Migration ──────────────────► Integrity

Integrity ──────────────────► Reporting
```

这些箭头表示待讨论的关系，**不是最终的服务调用方向**。

## 4. 共享语言

以下术语在各服务中应保持一致含义：

| 术语           | 含义                    |
| -------------- | ----------------------- |
| User / Subject | 执行操作的身份          |
| Resource       | 受访问控制保护的对象    |
| Source         | 读取媒体的位置          |
| Destination    | 写入媒体的位置          |
| Media Asset    | 被发现或迁移的媒体/文件 |
| READ           | 读取/访问资源           |
| WRITE          | 写入资源                |

## 5. API 原则

服务应通过明确的 API 契约通信，而不是依赖其他服务的内部 Java 类。

已达成共识的跨服务契约应定义：

- 端点和 HTTP 方法；
- 请求和响应；
- 共享标识符；
- HTTP 状态和错误行为。

已确认的契约维护在 `docs/api/` 下。

重要架构决策可记录在 `docs/decisions/` 下。

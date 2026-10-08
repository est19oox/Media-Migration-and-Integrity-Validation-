# 媒体迁移与完整性验证

[English](README.md) | [简体中文](README.zh-CN.md)

MMIV 是一个基于微服务的系统，用于在访问控制下发现、传输、验证媒体迁移，并生成相关报告。

## 服务

| 服务 | 端口 | 职责 |
| --- | ---: | --- |
| Transfer | 8081 | 在存储位置之间传输媒体 |
| Discovery | 8085 | 发现媒体和资源 |
| User Access | 8083 | 管理并检查访问权限 |
| Reporting | 8084 | 生成迁移和验证报告 |
| Integrity | 8082 | 验证已迁移媒体的完整性 |

## 文档

- **[草案]** [架构](docs/architecture/architecture.zh-CN.md) — 架构选项和需要团队讨论的决策
- **[草案]** [API 契约](docs/api/api-contracts.zh-CN.md) — 跨服务 API 契约
- **[草案]** [CI/CD](docs/ci-cd/ci-cd.zh-CN.md) — GitHub Actions 和 Jenkins 的职责
- [Jenkins 设置](docs/jenkins/local-setup.zh-CN.md) — 本地脚本自动构建、配置并运行 Jenkins

标记为**草案**的架构和 API 文档仍在团队讨论中。

## 开发

每个微服务都在 `services/` 下独立维护。

请从对应服务目录构建和测试服务：

```bash
mvn clean verify
```

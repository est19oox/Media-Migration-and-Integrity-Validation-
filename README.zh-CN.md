# 媒体迁移与完整性验证平台（MMIV）

[English](README.md) | [简体中文](README.zh-CN.md)

MMIV 是一个基于 Java 和 Spring Boot 的系统，用于发现媒体、授权迁移目标、将文件传至 Amazon S3、验证文件完整性并生成报告。API Gateway 提供浏览器前端和 API 入口。

## 系统概览

MMIV 由七个独立构建的 Spring Boot 服务组成。Eureka 负责服务发现，HTTP 和 RabbitMQ 支持服务通信，Oracle 与 MongoDB 存储应用数据，Amazon S3 存储媒体文件；API Gateway 提供浏览器应用。服务职责和端口见[服务目录](docs/services/catalog.zh-CN.md)。

## 环境要求

- 构建和测试服务：JDK 25、Maven 3.9.16。仓库提供 Maven Wrapper：`services/user-access-service/mvnw`（Windows 使用 `mvnw.cmd`）。
- 前端开发和格式检查：Node.js 22.12+ 与 npm；Vite 也支持 Node.js 20.19+。
- 本地数据库和集成运行：Docker Compose v2。
- 部分 CI 和部署校验脚本：Python 3。

集成运行还需要已配置的 Oracle、RabbitMQ、MongoDB、AWS 凭据和 S3 访问权限。切勿将凭据提交到仓库。启动前请阅读[本地开发指南](docs/development/local-setup.zh-CN.md)。

## 快速开始

在仓库根目录构建并测试 Transfer 服务：

```sh
./services/user-access-service/mvnw -B -f services/transfer-service/pom.xml clean verify
```

Windows PowerShell：

```powershell
.\services\user-access-service\mvnw.cmd -B -f services\transfer-service\pom.xml clean verify
```

启动前端开发服务器：

```sh
npm ci --prefix frontend
npm --prefix frontend run dev
```

Vite 将 API 请求代理到 `http://localhost:8080`，相关页面需要 Gateway 和依赖服务运行。格式检查命令为 `npm ci && npm run format:check`。更多测试和开发说明见文档索引。

## 文档

从[中文](docs/README.zh-CN.md)或[英文](docs/README.md)文档索引开始，查看维护中的指南及其翻译。Jenkins 指南介绍可选的本地控制器；托管 CI/CD 使用 GitHub Actions。

## 贡献

除非涉及共享契约或公共事件实现，服务变更应限制在相应 Maven 项目内。行为、配置或命令发生变化时，请更新相关文档。提交 Pull Request 前运行适用的检查；切勿提交凭据、环境文件或生成的 Terraform 状态文件。

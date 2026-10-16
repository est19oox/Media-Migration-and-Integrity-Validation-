# 媒体迁移与完整性验证平台（MMIV）

[English](README.md) | [简体中文](README.zh-CN.md)

MMIV 是一个基于 Java 和 Spring Boot 的系统，用于发现媒体文件、校验迁移目标权限、将文件传输到 Amazon S3、验证存储内容并生成迁移报告。系统由多个独立构建的服务组成；API Gateway 提供浏览器前端并转发 API 请求。

## 系统概览

MMIV 由七个独立构建的 Spring Boot 服务组成。Eureka 负责服务发现，
HTTP 和 RabbitMQ 支持服务通信，Oracle 与 MongoDB 存储应用数据，Amazon S3
存储媒体文件；API Gateway 提供浏览器应用。服务职责和端口见
[服务目录](docs/services/catalog.zh-CN.md)。

## 环境要求

- 构建和测试服务需要 JDK 25。
- Maven 3.9.16。仓库在 `services/user-access-service/mvnw` 和
  `mvnw.cmd` 中提供 Maven Wrapper。
- 前端开发服务器和仓库格式化工具需要 Node.js 22.12+ 与 npm。Vite 也支持
  Node.js 20.19+。
- 本地 Oracle、配套服务和集成运行测试需要 Docker Compose v2。
- 部分 CI 和部署校验脚本需要 Python 3。

集成运行还需要已配置的 Oracle、RabbitMQ、MongoDB、AWS 凭据和 S3 访问权限。
切勿将凭据放入已跟踪的文件。启动集成应用前请阅读
[本地开发指南](docs/development/local-setup.zh-CN.md)。

## 快速开始

在仓库根目录构建并测试一个服务：

```sh
./services/user-access-service/mvnw -B -f services/transfer-service/pom.xml clean verify
```

在 Windows PowerShell 中：

```powershell
.\services\user-access-service\mvnw.cmd -B -f services\transfer-service\pom.xml clean verify
```

启动前端开发服务器：

```sh
npm ci --prefix frontend
npm --prefix frontend run dev
```

Vite 在本地提供静态前端，并将 API 请求代理到
`http://localhost:8080`。因此，使用 API 的页面流程需要 Gateway 及其依赖服务运行。只运行仓库格式检查：

```sh
npm ci
npm run format:check
```

## 文档

请从[中文文档索引](docs/README.zh-CN.md)或[英文文档索引](docs/README.md)开始。
下表直接链接到所有指南；有对应简体中文版本时同时提供双语链接。

| 主题     | English                                                                     | 简体中文                                                           |
| -------- | --------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| 架构     | [Architecture](docs/architecture.md)                                        | [架构](docs/architecture.zh-CN.md)                                 |
| 开发     | [Local setup](docs/development/local-setup.md)                              | [本地开发](docs/development/local-setup.zh-CN.md)                  |
| 开发     | [Local Oracle database](docs/database/README.md)                            | [本地 Oracle 数据库](docs/database/README.zh-CN.md)                |
| 服务     | [Service catalog](docs/services/catalog.md)                                 | [服务目录](docs/services/catalog.zh-CN.md)                         |
| 服务     | [API Gateway](docs/services/api-gateway.md)                                 | [API Gateway](docs/services/api-gateway.zh-CN.md)                  |
| 服务     | [Reporting](docs/services/reporting.md)                                     | [Reporting](docs/services/reporting.zh-CN.md)                      |
| 服务     | [Transfer and Integrity](docs/services/transfer-integrity.md)               | [Transfer 与 Integrity](docs/services/transfer-integrity.zh-CN.md) |
| 服务     | [Integrated service flow](docs/services/integration.md)                     | [服务集成流程](docs/services/integration.zh-CN.md)                 |
| 服务     | [Circuit breakers](docs/services/circuit-breakers.md)                       | [服务熔断器](docs/services/circuit-breakers.zh-CN.md)              |
| 测试     | [Testing and formatting](docs/testing/README.md)                            | [测试与格式化](docs/testing/README.zh-CN.md)                       |
| 测试     | [API and DAST validation](docs/testing/api-validation.md)                   | [API 与 DAST 验证](docs/testing/api-validation.zh-CN.md)           |
| 部署     | [Docker release](docs/deployment/docker-release.md)                         | [Docker 发布部署](docs/deployment/docker-release.zh-CN.md)         |
| 部署     | [Gateway access](docs/deployment/gateway-access.md)                         | [Gateway 访问](docs/deployment/gateway-access.zh-CN.md)            |
| 部署     | [Login and protected access](docs/deployment/login-access.md)               | [登录与受保护访问](docs/deployment/login-access.zh-CN.md)          |
| 部署     | [Ansible](docs/deployment/ansible.md)                                       | [Ansible](docs/deployment/ansible.zh-CN.md)                        |
| 部署     | [Manual Ansible role](docs/deployment/ansible-manual-role.md)               | [手动 Ansible role](docs/deployment/ansible-manual-role.zh-CN.md)  |
| 部署     | [Terraform](docs/deployment/terraform.md)                                   | [Terraform](docs/deployment/terraform.zh-CN.md)                    |
| 部署     | [Optional local Jenkins](docs/jenkins/local-setup.md)                       | [可选本地 Jenkins](docs/jenkins/local-setup.zh-CN.md)              |
| 管理     | [Administrator access](docs/admin/administrator-access.md)（英文）          | 暂无中文版本                                                       |
| 管理     | [Folder workspace](docs/admin/folder-workspace.md)（英文）                  | 暂无中文版本                                                       |
| 历史运维 | [Bootstrap recovery notes](docs/infrastructure/bootstrap/README.md)（英文） | 使用前请核对当前 AWS 状态                                          |
| 事件契约 | [Event v2 guide](docs/contracts/events/v2/README.md)                        | [事件契约 v2](docs/contracts/events/v2/README.zh-CN.md)            |
| 事件契约 | [JSON Schema](contracts/events/v2/mmiv-event.schema.json)                   | 与语言无关                                                         |

Jenkins 指南描述本地演示控制器；仓库当前托管 CI/CD 自动化使用 GitHub Actions。

## 贡献

除非涉及共享契约或公共事件实现，服务变更应限制在相应 Maven 项目内。行为、配置或开发命令发生变化时，请同步更新文档。提交 Pull Request 前运行与改动相关的检查；切勿提交本地凭据、环境文件或生成的 Terraform 状态文件。

# 媒体迁移与完整性验证平台（MMIV）

[English](README.md) | [简体中文](README.zh-CN.md)

MMIV 是一个基于 Java 和 Spring Boot 的系统，用于发现媒体文件、校验迁移目标权限、将文件传输到 Amazon S3、验证存储内容并生成迁移报告。系统由多个独立构建的服务组成；API Gateway 提供浏览器前端并转发 API 请求。

## 架构

七个 Spring Boot 服务通过 Eureka 注册。Gateway 是浏览器访问入口；服务之间通过明确的 HTTP API 通信，RabbitMQ 传递生命周期和运行事件。Reporting 将业务事件和服务日志投影到 MongoDB。User Access 和 Discovery 使用 Oracle。Transfer 和 Integrity 访问 S3 执行文件操作。

| 服务             | 端口 | 职责                                      |
| ---------------- | ---: | ----------------------------------------- |
| Service Registry | 8761 | Eureka 服务注册与发现                     |
| API Gateway      | 8080 | 提供前端、浏览器会话和明确配置的 API 路由 |
| Transfer         | 8081 | 执行经元数据授权的上传并协调校验          |
| Integrity        | 8082 | 读取已存储对象并校验内容                  |
| User Access      | 8083 | 身份验证、账户和迁移目标权限              |
| Reporting        | 8084 | 消费事件并提供用户报告和管理员视图        |
| Discovery        | 8085 | 发现源媒体并管理迁移元数据                |

以上端口为应用默认端口。在集成 Compose 环境中，只有 Gateway 会发布到所有主机网络接口；其他应用端口仅绑定到本机回环地址。

## 技术栈

| 领域     | 技术                                                        |
| -------- | ----------------------------------------------------------- |
| 后端     | Java 25、Spring Boot 4、Spring Cloud、Maven                 |
| 服务通信 | Eureka、HTTP、RabbitMQ                                      |
| 数据存储 | Oracle、MongoDB；Integrity 本地默认使用 H2                  |
| 媒体存储 | Amazon S3                                                   |
| 前端     | 静态 HTML/CSS/JavaScript，使用 Vite 进行本地开发            |
| 部署     | Docker Compose、GitHub Actions、Terraform、Ansible、AWS SSM |

## 仓库结构

```text
common/             共享 Java 事件代码和资源
contracts/          机器可读事件 Schema 和示例
database/           本地 Oracle 设置脚本
docs/               开发、服务、测试和运维文档
frontend/           浏览器前端和 Vite 开发配置
infrastructure/     Docker Compose、Terraform 和 Ansible 部署代码
services/           七个独立 Maven 服务
scripts/            格式化、CI、部署和管理脚本
tests/              API、浏览器、集成和 CI 契约测试
```

## 环境要求

- 构建和测试服务需要 JDK 25。
- Maven 3.9.16。仓库在 `services/user-access-service/mvnw` 和
  `mvnw.cmd` 中提供 Maven Wrapper。
- 前端开发服务器和仓库格式化工具需要 Node.js 22.12+ 与 npm。Vite 也支持
  Node.js 20.19+。
- 本地 Oracle、配套服务和集成运行测试需要 Docker Compose v2。
- 部分 CI 和部署校验脚本需要 Python 3。

部分运行操作还需要已配置的 Oracle Schema、RabbitMQ、MongoDB、AWS 凭据，以及对指定 S3 Bucket 的访问权限。不要将凭据放入已跟踪的文件中。启动集成应用前请阅读[本地开发指南](docs/development/local-setup.md)。

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

## 测试与格式化

- 使用 `mvn -B -f services/<service>/pom.xml clean verify` 运行单个 Maven
  服务的单元测试和集成测试生命周期。
- 在 Linux、macOS 或 WSL 中使用 `bash scripts/ci/run-tests.sh .` 运行仓库
  测试脚本。该脚本需要 Maven 和 Python 3。
- 使用 `npm run format:check` 运行 Prettier 检查。
- 仓库级格式化脚本还会检查 Maven Spotless 和 Terraform 格式。详见
  [测试与格式化指南](docs/testing/README.md)。

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

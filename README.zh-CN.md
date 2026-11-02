# 媒体迁移与完整性验证（MMIV）

[English](README.md) | [简体中文](README.zh-CN.md)

MMIV 是一个基于 Java 和 Spring Boot 的媒体迁移系统，支持媒体发现、目标位置授权、文件传输、内容完整性验证及迁移报告。项目包含七个独立服务、浏览器前端、自动化测试和部署工具。

## 服务架构

| 服务             | 端口 | 职责                             |
| ---------------- | ---: | -------------------------------- |
| Service Registry | 8761 | Eureka 服务注册与发现            |
| API Gateway      | 8080 | 前端入口、浏览器会话和 API 路由  |
| Transfer         | 8081 | 执行授权后的文件传输并协调验证   |
| Integrity        | 8082 | 校验已存储对象的内容             |
| User Access      | 8083 | 用户身份验证、账户和目标位置权限 |
| Reporting        | 8084 | 消费事件并提供用户报告和管理视图 |
| Discovery        | 8085 | 发现来源媒体并管理迁移元数据     |

服务通过明确的 HTTP API 通信；RabbitMQ 传递生命周期和运行事件。Oracle、MongoDB 和 Amazon S3 分别用于账户/元数据、报告以及媒体存储。集成 Compose 环境仅向所有主机接口发布 Gateway，其他应用端口仅绑定本机。

## 快速开始

### 构建并测试服务

需要 JDK 25。项目包含 Maven Wrapper；从仓库根目录执行：

```sh
./services/user-access-service/mvnw -B -f services/transfer-service/pom.xml clean verify
```

PowerShell：

```powershell
.\services\user-access-service\mvnw.cmd -B -f services\transfer-service\pom.xml clean verify
```

### 启动前端开发服务器

需要 Node.js 22.12 或更高版本及 npm：

```sh
npm ci --prefix frontend
npm --prefix frontend run dev
```

API 代理指向 `http://localhost:8080`；API 页面需要 Gateway 及其依赖服务正在运行。

## 文档

- **架构：** [架构概览](docs/architecture/architecture.zh-CN.md)、[聚合设计](docs/architecture/aggregate-design.md)、[服务通信与边界](docs/architecture/service-communication.md)
- **API 契约：** [HTTP 与事件契约](docs/api/api-contracts.zh-CN.md)
- **服务：** 各服务说明见 [`services/`](services/)
- **事件契约：** [事件 v2 指南](contracts/events/v2/README.md)、[JSON Schema](contracts/events/v2/mmiv-event.schema.json)
- **开发：** [前端](frontend/README.md)、[数据库](database/README.md)、[API 测试](tests/api/README.md)
- **部署：** [Docker 部署](docs/ci-cd/docker-deployment.md)、[Gateway 访问](docs/ci-cd/gateway-access.md)、[Ansible](infrastructure/ansible/README.md)、[Terraform](infrastructure/terraform/README.md)
- **CI/CD：** [工作流说明](docs/ci-cd/ci-cd.zh-CN.md)
- **管理：** [管理员访问](docs/admin/administrator-access.md)、[文件夹工作区](docs/admin/folder-workspace.md)
- **验证记录：** [测试范围与验证证据](docs/verification/README.md)
- **可选本地 CI：** [Jenkins 设置](docs/jenkins/local-setup.zh-CN.md)

## 项目状态

**项目已完成并公开发布。** 仓库包含完整的服务实现、前端、测试、CI/CD 工作流和运维文档。实际运行或部署仍需按目标环境配置基础设施、凭据和服务参数；验证记录说明了已执行测试的范围及其限制。

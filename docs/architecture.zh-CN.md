# MMIV 架构

[English](architecture.md) | [简体中文](architecture.zh-CN.md)

MMIV 由七个独立构建的 Spring Boot 服务组成。Eureka 提供服务注册与发现。
API Gateway 提供静态前端并公开明确配置的后端路由。RabbitMQ 传递业务
生命周期事件和服务运行日志，但不负责执行迁移流程。

## 服务与数据存储

| 服务             | 默认端口 | 主要职责                          | 数据或依赖                         |
| ---------------- | -------: | --------------------------------- | ---------------------------------- |
| Service Registry |     8761 | Eureka 注册中心                   | Eureka 客户端                      |
| API Gateway      |     8080 | 浏览器会话、CSRF、前端和 API 路由 | Eureka、User Access、RabbitMQ      |
| Transfer         |     8081 | 上传及 S3 传输编排                | Discovery、Integrity、S3、RabbitMQ |
| Integrity        |     8082 | 校验目标对象                      | Discovery、S3、RabbitMQ            |
| User Access      |     8083 | 身份验证和目标权限                | Oracle、RabbitMQ                   |
| Reporting        |     8084 | 事件投影、报告和运行记录          | RabbitMQ、MongoDB                  |
| Discovery        |     8085 | 源媒体发现、迁移元数据和目标分配  | Oracle、User Access、RabbitMQ      |

前端从 `frontend/` 构建后复制到 Gateway 应用资源中，不是独立的生产服务。
Vite 仅用作本地前端开发服务器。

集成 Compose 部署中，Gateway 发布到主机的 8080 端口；Registry 和后端应用
端口仅绑定回环地址，服务通过 Compose 网络通信。Oracle、RabbitMQ 和
MongoDB 是支持服务，不属于 MMIV 应用服务。

## 请求与事件流程

1. 用户通过 User Access 身份验证。浏览器将签名令牌交给 Gateway 换取
   HttpOnly 会话 Cookie；Cookie 认证的写操作使用 Gateway 的 CSRF 防护。
2. 客户端通过 Discovery 创建元数据计划。Discovery 经 User Access 检查
   当前目标权限并记录已分配的目标。
3. Transfer 解析计划并再次检查授权，然后将准备好的文件写入 S3。客户端
   不能指定任意 Bucket 或对象键。
4. Integrity 校验已分配的目标对象并将结果返回 Transfer。Transfer 负责
   响应、重试和受保护的清理行为。
5. Discovery、Transfer 和 Integrity 发布相关联的业务结果事件。Reporting
   消费事件并在 MongoDB 中生成迁移报告。
6. 七个服务都会将运行日志事件发布到 RabbitMQ；Reporting 将其存入
   MongoDB。

当前授权与事件细节见[服务集成](services/integration.zh-CN.md)，Schema
和发布方规则见[事件契约 v2](contracts/events/v2/README.zh-CN.md)。

## 构建边界

`services/` 下的每个目录都是独立 Maven 项目，并维护自己的依赖版本。
共享 Java 事件代码位于 `common/`；服务 POM 将其源文件和资源加入各自构建。
机器可读事件契约及合法示例位于 `contracts/events/v2/`。

## 部署边界

GitHub Actions 构建并验证应用镜像，然后在配置的发布流程中使用 Terraform、
Ansible 主机准备、Docker Compose 和 AWS Systems Manager。Gateway 是公开的
应用入口。就绪检查和恢复细节见[Docker 发布部署](deployment/docker-release.zh-CN.md)。

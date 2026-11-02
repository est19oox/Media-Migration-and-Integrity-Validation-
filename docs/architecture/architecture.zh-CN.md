# MMIV 架构

MMIV 是一个媒体迁移微服务系统。API Gateway 为浏览器应用提供服务，并将请求路由到独立部署的 Spring Boot 服务。Eureka 负责服务注册；服务通过明确的 HTTP 接口处理请求/响应，通过 RabbitMQ 传递生命周期和运行事件。

## 服务

| 服务             | 默认端口 | 职责                             |
| ---------------- | -------: | -------------------------------- |
| Service Registry |     8761 | Eureka 注册与服务发现            |
| API Gateway      |     8080 | 浏览器入口、会话、路由和请求限流 |
| Transfer         |     8081 | 授权后的存储操作与传输协调       |
| Integrity        |     8082 | 已存储对象的校验和验证记录       |
| User Access      |     8083 | 账户、身份验证和目标位置权限     |
| Reporting        |     8084 | 事件投影、报告和管理视图         |
| Discovery        |     8085 | 来源发现和迁移元数据             |

以上端口为应用默认值。实际暴露端口由所选运行环境配置决定；集成 Compose 环境发布 Gateway，并将后端应用端口绑定到本机。

## 所有权与通信

各服务负责自己的业务数据和规则：User Access 管理账户和授权；Discovery 管理迁移计划和元数据；Transfer 执行存储操作；Integrity 管理验证结果；Reporting 管理事件投影。Gateway 和 Service Registry 属于基础设施组件，不是业务领域。

服务使用 HTTP 进行权限检查、计划分配、传输执行和验证请求。Discovery、Transfer 与 Integrity 发布带版本的事件，由 Reporting 投影。RabbitMQ 负责传递事件，不负责协调迁移流程。

已实现的上下文关系和安全边界见[服务通信文档](service-communication.md)；聚合规则和持久化所有权见[聚合设计](aggregate-design.md)。

## 数据与基础设施

- Oracle 存储 User Access 和 Discovery 数据。
- MongoDB 存储 Reporting 投影。
- Amazon S3 存储迁移后的媒体。
- RabbitMQ 传递生命周期和运行事件。
- Redis 为 Gateway 提供共享限流计数。

本地开发和部署需要按目标环境配置这些依赖。不要将凭据或生成的基础设施状态提交到版本控制。

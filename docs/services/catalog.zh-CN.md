# 服务目录

[English](catalog.md) | [简体中文](catalog.zh-CN.md)

七个应用服务是位于 `services/` 下的独立 Maven 项目。除非运行时配置覆盖，
它们使用下表默认端口。集成 Compose 环境中，只有 API Gateway 发布到所有
主机网络接口；其他应用端口仅绑定回环地址。

| 服务             | 端口 | 职责                                 | 主要依赖                           |
| ---------------- | ---: | ------------------------------------ | ---------------------------------- |
| Service Registry | 8761 | Eureka 注册与发现                    | RabbitMQ                           |
| API Gateway      | 8080 | 前端、浏览器会话和明确配置的后端路由 | Eureka、RabbitMQ                   |
| Transfer         | 8081 | 经元数据授权的文件上传和 S3 传输     | Discovery、Integrity、S3、RabbitMQ |
| Integrity        | 8082 | S3 对象完整性校验                    | Discovery、S3、RabbitMQ            |
| User Access      | 8083 | 身份验证、账户和目标授权             | Oracle、RabbitMQ                   |
| Reporting        | 8084 | 业务报告和服务日志投影               | RabbitMQ、MongoDB                  |
| Discovery        | 8085 | 源文件发现和迁移元数据               | Oracle、User Access、RabbitMQ      |

共享 Java 事件代码位于 `common/`；事件 Schema 和示例位于
`contracts/events/v2/`。授权和请求细节见[集成服务流程](integration.zh-CN.md)。

## 服务专题指南

- [API Gateway](api-gateway.zh-CN.md)：浏览器会话、路由、资源打包和构建验证。
- [Reporting](reporting.zh-CN.md)：事件消费、数据访问、存储保障和 Smoke Test。
- [Transfer 与 Integrity 校验](transfer-integrity.zh-CN.md)：传输结果、
  Integrity 请求、重试和清理。

其他服务职责已在上表和架构、集成指南中说明；已删除重复的独立 README。

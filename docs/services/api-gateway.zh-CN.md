# MMIV API Gateway

[English](api-gateway.md) | [简体中文](api-gateway.zh-CN.md)

API Gateway 是唯一公开的应用入口。它提供浏览器前端，验证外部
Bearer JWT 和浏览器的 `MMIV_SESSION` Cookie，对 Cookie 认证的写操作执行
CSRF 检查，并将经过验证的身份转发给后端。后端仍须独立负责自身数据授权。

Gateway 监听 8080 端口，使用 Eureka 和 Spring Cloud LoadBalancer 为明确
配置的后端路由进行服务发现。它是应用层负载均衡器，不是 AWS ALB。集成
Compose 环境会将其他服务端口绑定到 `127.0.0.1`；只有 Gateway 对所有主机
接口开放。

## 浏览器身份验证

1. 公共登录页调用 `POST /user-access/api/auth/login`。
2. 浏览器将返回的 JWT 发送到 `POST /api/v1/session`。
3. Gateway 将 JWT 存入短时效、HttpOnly、SameSite 会话 Cookie。
4. `GET /api/v1/session` 返回当前身份及 CSRF token，不暴露 JWT。
5. 前端请求使用同一 Gateway 源。Gateway 验证 Cookie，在代理前移除 Cookie，
   并向后端发送已验证的身份。

当前本地及 AWS 开发 HTTP endpoint 要求 `JWT_COOKIE_SECURE=false`。仅在
Gateway 位于 HTTPS 后方时设为 `true`。Gateway 还需要
`USER_ACCESS_JWT_ISSUER` 和 `USER_ACCESS_JWK_SET_URI`；Gateway 本身不签发令牌。

## 主要路由

| Gateway 路径                                                                 | 所属服务    | 说明                              |
| ---------------------------------------------------------------------------- | ----------- | --------------------------------- |
| `/`、`/pages/**`、`/css/**`、`/js/**`、`/assets/**`                          | API Gateway | 前端打包在 Gateway 镜像中         |
| `/api/v1/session`                                                            | API Gateway | 浏览器会话和 CSRF 生命周期        |
| `/user-access/**`、`/api/access/**`                                          | User Access | 登录、账户及权限 API              |
| `/api/v1/transfers/**`、`/api/v1/files/**`、`/api/v1/portal/**`              | Transfer    | 迁移和文件操作                    |
| `/api/metadata/**`、`/api/discovery/**`、`/discovery/**`                     | Discovery   | 元数据和发现操作                  |
| `/api/integrity/**`、`/integrity/**`                                         | Integrity   | 完整性校验                        |
| `/api/reports/**`、`/api/admin/reports/**`、`/api/history`、`/api/dashboard` | Reporting   | 用户和管理员报告查询              |
| `/reporting/**`                                                              | Reporting   | 兼容前缀；转发前移除 `/reporting` |

未知路由不会被动态公开。Eureka 检测到服务故障后，缺失的后端会返回明确的
503。Gateway 不重试写请求，以免重复上传。

## 构建和测试

使用 Java 25 和仓库 Maven Wrapper：

```sh
services/user-access-service/mvnw -B -f services/api-gateway/pom.xml clean verify
```

Maven 构建会测试前端并将生产资源复制到 `META-INF/resources`；没有独立前端
容器。运行时依赖见[本地开发](../development/local-setup.zh-CN.md)，请求流程见
[服务集成](integration.zh-CN.md)。

## 运行时与故障接受测试

当前本地集成环境为 `infrastructure/service-registry/compose.yml`。扩展版
Compose 展示多个 Transfer 和 Integrity 副本，不会改变路由归属。AWS CD 构建
并部署完整的七服务不可变应用版本；RabbitMQ 和 MongoDB 是支持容器。

集成环境健康后验证报告不依赖 Transfer：

```sh
bash tests/api/verify-reporting-with-transfer-down.sh
```

该测试创建临时用户和其拥有的报告数据，建立真实 Gateway Cookie 会话，停止
Transfer，检查报告页面及用户报告 API，并确认文件路由返回 503；随后重启
Transfer 并删除临时账户和报告数据。token 和 Cookie 仅保存在权限受限的临时
目录中。

部署 Smoke/DAST 使用 `/pages/login.html`，不使用已移除的 Transfer 所属
`/upload.html`。认证端点验证也会通过 Gateway 检查报告列表。

# 登录与受保护访问

[English](login-access.md) | [简体中文](login-access.zh-CN.md)

API Gateway 提供浏览器前端并作为浏览器应用源。User Access 负责账户身份
验证和 JWT 签名。浏览器将登录结果交给 Gateway 建立 HttpOnly
`MMIV_SESSION` Cookie，不会将访问 token 保存在浏览器存储中。

## 浏览器会话

1. 通过 Gateway 提供的前端登录或注册。
2. 前端建立 Gateway session，并通过 `GET /api/v1/session` 确认。
3. 浏览器请求使用同源 session Cookie；修改数据的请求使用 session endpoint
   返回的 CSRF token。
4. Gateway 验证配置的 issuer 和 User Access 签名密钥，再将已验证身份转发给
   路由目标。各后端仍负责其自身数据授权。
5. 登出会清除浏览器 Cookie。会话过期或无效后必须重新认证。

Gateway 使用 `USER_ACCESS_JWT_ISSUER` 和 `USER_ACCESS_JWK_SET_URI` 验证
User Access token。Gateway 使用 HTTPS 时必须启用 `JWT_COOKIE_SECURE`；当前
本地 HTTP 配置默认值为 `false`。不要将 User Access 私钥提供给 Gateway 或
Transfer。

## 授权

新账户默认没有目标授权。管理员可为已登记目标授予 `READ` 或 `WRITE`；
`WRITE` 包含读取权限。Discovery、Transfer 和 Integrity 执行操作时都会检查
当前权限和已分配的元数据。客户端不能通过 file ID 或任意对象键绕过目标授权。

账户初始化、邀请、密码重置和权限管理见[管理员访问](../admin/administrator-access.md)。
浏览器行为见[文件夹工作区](../admin/folder-workspace.md)，API 和服务间调用见
[集成服务流程](../services/integration.zh-CN.md)。

## 验证

部署验证工作流包含 Gateway 和受保护服务检查。可独立运行本地 Maven 测试，
但这些测试不能证明与在线 EC2、AWS Systems Manager 或 S3 的连接。详见
[API 与 DAST 验证](../testing/api-validation.md)。

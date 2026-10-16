# 通过 API Gateway 访问浏览器应用

[English](gateway-access.md) | [简体中文](gateway-access.zh-CN.md)

Terraform apply、主机准备和 Docker 部署完成后，可访问：

- `http://<EC2-public-IP>:8080/`（打开登录页）
- `http://<EC2-public-IP>:8080/pages/home.html`
- `http://<EC2-public-IP>:8080/pages/reports.html`

Terraform 允许公网 IPv4 TCP 8080。Docker Compose 将 api-gateway 发布为
`0.0.0.0:8080:8080`。后端服务和 Registry 的主机端口绑定到
`127.0.0.1`；容器通过 `mmiv-backend` 通信。部署、救援和回滚使用相同绑定
默认值。API 路径仍为 `/api/v1/transfers` 和 `/api/v1/files/**`。

Gateway 验证 Bearer JWT 和 HttpOnly 浏览器会话 Cookie。后端端口不公开；
所有者/管理员授权仍由各后端强制执行。若需网络层限制，在 Terraform apply
前将安全组的 `0.0.0.0/0` 入站 CIDR 改为允许的客户端 CIDR。

SSM 端口转发为可选方式：

```sh
bash scripts/cd/open-gateway-tunnel.sh i-YOUR_INSTANCE_ID
```

隧道运行时访问 `http://localhost:8080/`。登录或注册后会打开
`/pages/home.html`。

已部署版本包含七个应用服务。Discovery 和 User Access 使用暂存的 Oracle
配置；Reporting 使用 MongoDB；全部服务通过 RabbitMQ 发布审计事件，并通过
Eureka 相互发现。

文件夹 API 为 `POST /api/v1/transfers/folder`，要求 `files` 和 `relativePaths`
一一对应；最多 500 个文件、总计 100 MiB。Transfer 上传后会调用 Integrity，
必须确认完整性成功。当前部署不包含 Redis。

Gateway 页面和 Transfer 文件 API 需要登录。Gateway 将 Reporting 路由直接转发
到 Reporting，不依赖 Transfer。Cookie 会话、经过认证的 CD 检查和注册流程见
[登录设置](login-access.zh-CN.md)。

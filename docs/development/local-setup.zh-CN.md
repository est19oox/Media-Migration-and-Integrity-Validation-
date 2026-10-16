# 本地开发

[English](local-setup.md) | [简体中文](local-setup.zh-CN.md)

## 环境要求

- JDK 25 和 Maven 3.9.16。仓库 Maven Wrapper 位于
  `services/user-access-service/`。
- Vite 和 Prettier 需要 Node.js 22.12+ 与 npm；Vite 也支持 Node.js 20.19+。
- 使用本地 Oracle、RabbitMQ、MongoDB 或集成运行时测试时需要 Docker Compose v2。
- 部分 CI 和部署脚本需要 Python 3。

七个 Java 服务可分别编译和运行单元测试。完整浏览器流程还需要下述支持服务
和运行时配置；仅启动前端不会启动后端。

## 构建和测试服务

在仓库根目录选择一个服务执行：

```sh
./services/user-access-service/mvnw -B -f services/transfer-service/pom.xml clean verify
```

Windows PowerShell：

```powershell
.\services\user-access-service\mvnw.cmd -B -f services\transfer-service\pom.xml clean verify
```

将 `transfer-service` 替换为目标服务目录。`clean verify` 会运行该服务的
Maven 生命周期和测试。仓库 CI 辅助脚本会遍历所有 Maven 项目：

```sh
bash scripts/ci/run-tests.sh .
```

该脚本需要在 `PATH` 中找到 Maven 和 Python 3；Windows 请使用 WSL 或其他
POSIX Shell。

## 运行前端

前端包与根目录格式化工具包相互独立：

```sh
npm ci --prefix frontend
npm --prefix frontend run dev
```

Vite 使用默认本地地址提供前端，并按 `frontend/vite.config.js` 将 API 请求
代理到 `http://localhost:8080`。登录、上传、报告等 API 流程需要可访问的
API Gateway 及其依赖服务。

Gateway Maven 构建会把生产前端复制到 `META-INF/resources`，生产环境不会
单独运行前端服务。

## 支持服务和配置

本地 Oracle 辅助脚本只启动数据库容器，不会配置所有应用 Schema，也不会
创建 AWS 资源：

```powershell
.\database\setup-db.ps1
```

```sh
bash database/setup-db.sh
```

本地连接信息和持久卷注意事项见[Oracle 数据库设置](../database/README.zh-CN.md)。
该脚本不会创建应用数据库用户或 Schema；仓库也没有提供完整的本地 Oracle
用户/Schema 初始化流程。

集成 Compose 文件为 `infrastructure/service-registry/compose.yml`。启动前需
预建 Discovery 和 User Access 所需 Oracle 用户/Schema，并准备以下 Compose
输入：

| 变量                                   | 用途                                                  |
| -------------------------------------- | ----------------------------------------------------- |
| `RABBITMQ_PASSWORD`                    | 本地 RabbitMQ 服务凭据                                |
| `MONGODB_PASSWORD`                     | Reporting 使用的 MongoDB root 凭据                    |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | Discovery 的 Oracle JDBC 连接                         |
| `USER_ACCESS_ENV_FILE`                 | User Access 数据库和 JWT 设置的私有环境文件           |
| `USER_ACCESS_JWT_DIRECTORY`            | 含 `private.pem`、`public.pem` 的目录，以只读方式挂载 |

`DB_URL` 中的主机地址必须能从 Discovery 容器访问。Oracle 辅助脚本将
1521 绑定到主机回环地址；集成 Compose 不包含 Oracle 容器，也不配置主机
网关路由。不同 Docker Engine 和操作系统的容器到主机连接方式不同，请配置
并验证支持的路由，不要假定 `localhost` 可用。

`USER_ACCESS_ENV_FILE` 必须提供 `ORACLE_JDBC_URL`、`ORACLE_USER`、
`ORACLE_PASSWORD`、`JWT_ISSUER` 和 `JWT_KEY_ID`。密钥路径由部署配置设置，
并指向上文挂载的 PEM 文件。Compose 还可接收 `AWS_REGION`、
`MMIV_BUCKET_NAME`、`AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY`、
`AWS_SESSION_TOKEN`、`USER_ACCESS_JWT_ISSUER`、`JWT_COOKIE_SECURE` 和
`DISCOVERY_MEDIA_PATH`。请使用适用于非生产环境的值，切勿提交密码、签名密钥
或私有配置文件。数据库配置必须指向已初始化的 User Access Schema。

Integrity 默认使用内存 H2，重启后审计记录会丢失。若使用 Oracle，激活
`oracle` profile 并设置 `INTEGRITY_DB_URL` 和 `INTEGRITY_DB_PASSWORD`；
`INTEGRITY_DB_USERNAME` 默认值为 `INTEGRITY_SERVICE`。Oracle Schema 和审计表
必须预先存在，因为该 profile 使用 Hibernate 校验而不创建表。集成 Compose
默认仍使用 H2。

在 Docker 构建镜像前先构建并测试服务 JAR：

```sh
bash scripts/ci/run-tests.sh .
```

先启动 Oracle，再验证 Compose 配置；不要输出展开后的密钥：

```sh
docker compose --env-file /absolute/path/to/mmiv-compose.env -f infrastructure/service-registry/compose.yml config --quiet
```

验证成功后启动集成环境：

```sh
docker compose --env-file /absolute/path/to/mmiv-compose.env -f infrastructure/service-registry/compose.yml up --build -d
```

Compose 会在配置支持的情况下等待 RabbitMQ、MongoDB 和 Registry 健康检查；
随后后端向 Eureka 注册。Gateway 在后端之后启动，但 Compose 依赖检查不能
代替部署流程的完整就绪屏障。依赖 API 的浏览器流程开始前，请验证后端健康、
Eureka 注册和 Gateway 路由。若只需停止应用且保留持久卷：

```sh
docker compose --env-file /absolute/path/to/mmiv-compose.env -f infrastructure/service-registry/compose.yml down
```

部署侧配置和访问行为见[管理员访问](../admin/administrator-access.md)及
[登录与受保护访问](../deployment/login-access.md)。

Transfer 和 Integrity 执行实际存储操作时，需要访问已配置 S3 Bucket 的
凭据和权限。应用默认值不等于 AWS 访问权限；手动或 DAST 上传请使用非生产
Bucket。

## 格式化

根目录 npm 包安装固定版本的 Prettier：

```sh
npm ci
npm run format:check
```

仓库完整格式检查还会运行 Maven Spotless 和 Terraform 格式检查。Windows：

```powershell
.\scripts\format\check-all.ps1
```

更多 CI 检查和测试范围见[测试与格式化](../testing/README.md)。

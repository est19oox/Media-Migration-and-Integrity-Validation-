# 本地 Oracle 数据库

[English](README.md) | [简体中文](README.zh-CN.md)

需要 Docker 正在运行 Linux 容器，并安装 Docker Compose v2。

在仓库根目录的 Windows 环境运行：

```powershell
.\database\setup-db.ps1
```

Linux/macOS：

```bash
bash database/setup-db.sh
```

两个脚本均使用自身目录中的 Compose 文件，不受当前工作目录影响。除非已
设置 `ORACLE_PWD`，否则脚本会提示输入密码；验证配置时不会打印密码，并会
启动 Oracle。等待镜像健康检查报告 healthy 后再连接。

- 主机：`localhost`
- 端口：`1521`
- Service：`FREEPDB1`
- 管理用户：`SYSTEM`（使用初始化密码）
- 容器：`media-migration-oracle`
- 持久卷：`media-migration-oracle-data`

密码必需，但不会提交到源码。若数据库卷已存在，输入另一个初始化密码不会
重置数据库密码，应使用现有密码。默认镜像沿用旧设置脚本版本；需要固定版本
时设置兼容的 `ORACLE_IMAGE` tag 或 digest。已有数据时，不要在未遵循 Oracle
升级流程的情况下升级数据库镜像。

端口仅绑定本机用于开发。普通删除容器不会删除持久卷；除非确实要删除数据库
数据，否则不要运行 `docker compose down -v`。

若曾使用旧 PowerShell 脚本，可能已有同名独立容器。可继续运行
`docker start media-migration-oracle`。Compose 无法自动接管该容器。迁移前先
备份数据库并检查挂载和镜像；确认数据位于上述命名卷后，才停止并删除旧容器
（不要删除卷），再使用兼容镜像和相同数据库密码运行新脚本。脚本不会自动
删除现有容器。

这是本地数据库设置，不会被加入 EC2 CD 工作流。

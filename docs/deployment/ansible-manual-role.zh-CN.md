# 通用微服务部署 Role（手动/旧流程）

[English](ansible-manual-role.md) | [简体中文](ansible-manual-role.zh-CN.md)

此 role 保留用于手动部署和迁移测试。自动 CD 使用 Docker over SSM；当前流程
见[Docker 发布部署](docker-release.zh-CN.md)。

Ansible 负责 ECR 身份验证、远程执行、健康检查和回滚。Docker Compose 定义
容器、端口、媒体挂载和共享网络。先运行 `setup-host.yml`；此 role 不安装
主机组件。

每个服务使用独立 Compose project（`mmiv-<service>`），配置位于
`/opt/mmiv/<service>`，因此并行后端部署不会互相修改。后端端口绑定本机；
只有 Gateway 绑定所有接口。

`docker_compose_v2` 使用 `pull: missing` 并自动协调：镜像/配置不变时保留现有
容器；已停止容器会重启；镜像或 Compose 配置改变时重建对应服务。Integrity
环境文件变化会明确重建 Integrity，因为 Compose 不一定能检测 env file 内容
变化。ECR tag 必须不可变。

旧 `docker run` role 创建的容器只迁移一次。删除旧容器前先拉取替换镜像。若
容器属于其他 Compose project，会拒绝删除。迁移和后续更新可能短暂中断被选中
服务，不是零停机发布。

变更部署前，role 会保存先前镜像、Compose 文件和 Integrity 环境配置。健康
检查失败时恢复这些内容。后续 Smoke/DAST 检查失败时，`rollback.yml` 也会
使用同一 role。未变化部署会保留现有回滚备份，并向 GitHub Actions 报告
`deployment_changed=false`，避免后续检查失败回滚未变化的服务。

在仓库根目录运行回归检查，需要 Python、Ansible、所需 collections、
Ansible interpreter 中的 Python `requests` 以及运行中的 Docker daemon：

```sh
python tests/ci/test-deployment-config.py
python tests/ci/test-compose-runtime.py
```

运行时测试会创建隔离的测试镜像、容器和网络，并在结束时清理；测试重复部署、
镜像变化、显式回滚、健康失败自动回滚以及一次性旧容器迁移。

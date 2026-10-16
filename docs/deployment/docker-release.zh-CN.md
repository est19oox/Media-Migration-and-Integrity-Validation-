# Docker 发布部署

[English](docker-release.md) | [简体中文](docker-release.zh-CN.md)

当前托管发布工作流为 `.github/workflows/cicd.yml`。它构建七个应用服务镜像、
准备 AWS 主机、通过 AWS Systems Manager 部署 Docker Compose 版本，并运行已
部署服务验证。GitHub Actions 是托管 CI/CD 路径；可选 Jenkins 控制器仅用于
本地演示。

## 发布流程

1. 发布前运行 CI 验证和安全检查。可复用的 `build-service.yml` 工作流构建、
   测试、扫描服务镜像，并使用源代码 commit 标记发布。
2. Terraform 和主机准备配置部署基础设施。Ansible 用于主机准备及受保护的
   可选配置暂存；应用部署和回滚由通过 SSM 调用的 Docker stack 脚本完成。
3. 发布作业验证完整镜像清单并将选定镜像发送到主机。单条 SSM 部署命令在
   `mmiv-stack` Compose project 下协调发布。
4. Compose 先启动 Service Registry 和托管支持服务，再启动依赖服务。后端
   必须健康并注册到 Eureka，Gateway 就绪检查才能通过。
5. 验证工作流检查服务就绪状态和 Gateway 路由，然后运行配置的功能检查和
   DAST 扫描；每服务证据保存在工作流 artifacts。

发布包含全部七个应用服务：Service Registry、API Gateway、Transfer、
Integrity、User Access、Reporting 和 Discovery。Oracle、RabbitMQ、MongoDB
是支持服务。Reporting 使用 RabbitMQ 事件和 MongoDB；旧文档中的 SQS 日志
队列描述已不代表当前 Reporting 路径。

## 网络暴露

Gateway 监听主机 8080 端口，是公开应用入口。Registry 和后端应用端口仅绑定
主机回环地址；容器通过共享 Compose 网络通信。配置部署时应将 Gateway 入站
流量限制为批准的客户端网络。浏览器和 SSM 隧道访问见[Gateway 访问](gateway-access.zh-CN.md)。

## 恢复与运行限制

部署会在主机记录发布状态和恢复进度。发布失败时从上一版本快照恢复发生变化
的服务；验证失败时通过 SSM 调用恢复路径。未变化服务会保留；应用回滚不会
删除托管 Oracle 数据或持久卷。

这是原地 Compose 部署，不承诺零停机。恢复行为不能替代单独测试过的数据库
备份/恢复流程。重试失败发布前先查看工作流证据和操作员说明；不要通过删除
Terraform state、数据库卷或发布数据来绕过失败检查。

## 实现与证据

- `.github/workflows/cicd.yml`：发布编排。
- `.github/workflows/build-service.yml`：可复用的服务构建和镜像作业。
- `.github/workflows/verify-services.yml`：就绪检查、功能验证和部署恢复。
- `.github/workflows/validate.yml`：Pull Request 验证。
- `scripts/cd/docker-stack-ssm.py`：镜像清单验证和 SSM 编排。
- `scripts/cd/docker-stack-remote.py`：主机端 stack 部署与恢复。
- `scripts/cd/verify-deployed-services.py`：已部署服务就绪检查和验证。
- `infrastructure/service-registry/compose.yml`：集成服务定义。

具体部署结果和附件可在相应 GitHub Actions run 查看。本地测试范围见
[测试与格式化](../testing/README.zh-CN.md)，浏览器认证模型见[登录与受保护访问](login-access.zh-CN.md)。

# MMIV CI/CD

GitHub Actions 提供托管的代码验证、服务验证和部署工作流。工作流定义位于 [`.github/workflows/`](../../.github/workflows/)，主流水线为 [`cicd.yml`](../../.github/workflows/cicd.yml)。

## 托管工作流

- GitHub Actions 执行仓库验证和格式检查。
- 服务构建与验证工作流检查 Spring Boot 服务。
- 主 CI/CD 流水线验证 Terraform、运行 CI/安全检查、构建服务镜像、通过 AWS Systems Manager 部署，并验证已部署应用。
- 工作流按各自配置的分支/路径筛选器触发，也可手动运行。所需仓库密钥和云资源取决于所选工作流。

[Docker 部署指南](docker-deployment.md)介绍部署顺序、运行环境要求和恢复行为。运行部署工作流前，请确认 AWS 账户、Terraform 状态和目标环境。

## 可选本地 Jenkins

Jenkins 是可选的本地演示环境，不是托管 CI 或生产部署的第二个权威系统。安装和使用方法见[本地 Jenkins 设置](../jenkins/local-setup.zh-CN.md)。

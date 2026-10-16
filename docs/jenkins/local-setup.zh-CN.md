# MMIV 本地 Jenkins 配置与运行指南

[English](local-setup.md) | [简体中文](local-setup.zh-CN.md)

本指南介绍可复现的本地开发和演示控制器，不是生产环境部署，也不是仓库托管 CI/CD 的权威入口；托管验证和发布由 GitHub Actions 执行。

## 环境要求与配置

需要 Docker Desktop（或兼容的 Linux Docker Engine）、Docker Compose、首次下载镜像和扫描工具所需的网络，以及至少 6 GB 可用内存。Windows 使用 PowerShell 7 或 Windows PowerShell 5.1；macOS 和 Linux 使用兼容 POSIX 的 shell。

在仓库根目录复制本地配置文件：

```powershell
Copy-Item jenkins/.env.example jenkins/.env
```

macOS/Linux：

```sh
cp jenkins/.env.example jenkins/.env
```

在 `jenkins/.env` 中设置强密码、`GITHUB_USERNAME` 和 `GITHUB_TOKEN`。Token 应为可读取该私有仓库的 fine-grained 只读令牌，不需要 push、管理、Workflow 或 PR 审批权限。默认主机端口 `JENKINS_PORT=18080` 可避开常用的 8080 端口。该文件已忽略，切勿提交或分享。

## 启动、停止和清理

启动并运行 smoke test：

```powershell
./scripts/jenkins-up.ps1
```

macOS/Linux：

```sh
./scripts/jenkins-up.sh
```

脚本会构建镜像、启动 Jenkins，并检查 `http://localhost:18080/login` 和 `mmiv-ci` Job。使用 `jenkins/.env` 中的凭据登录；如修改了端口，请使用新端口。

普通停止会保留名为 `mmiv_jenkins_home` 的 Docker volume，其中包含 Job、凭据、配置和构建历史：

```powershell
./scripts/jenkins-down.ps1
```

macOS/Linux 使用 `./scripts/jenkins-down.sh`。需要永久清除本地数据时，使用 `./scripts/jenkins-down.ps1 -Purge` 或 `./scripts/jenkins-down.sh --purge`。

查看容器状态和日志：

```sh
docker compose --env-file jenkins/.env -f jenkins/docker-compose.yml ps
docker compose --env-file jenkins/.env -f jenkins/docker-compose.yml logs jenkins
```

## 可复现范围

Jenkins、应用 Java、Trivy、Semgrep 和 Checkov 均固定版本；ZAP、Jenkins 与 Java 基础镜像使用不可变多平台摘要，插件版本记录在 `jenkins/plugins.txt`。Terraform 和 Maven 仍由项目维护者决定版本，本 Jenkins 配置不会更改它们的版本约束。POSIX 脚本有 Windows shell 契约测试，但真实 macOS 主机验证仍需由团队成员完成并记录。

## GitHub 分支发现

Configuration as Code 会创建 `mmiv-ci` Multibranch Pipeline，并将只读 Token 保存为 `github-pat` Jenkins 凭据。Jenkins 索引配置仓库中包含根目录 `Jenkinsfile` 的远程分支，每两分钟扫描一次；也可手动运行 **Scan Multibranch Pipeline Now**。

构建通过 `checkout scm` 检出实际提交，并将完整 SHA 记录为 `TESTED_COMMIT`。分支必须包含根目录 `Jenkinsfile` 才会被发现。推送到 `main` 和 `feature/*` 的构建会执行部署、验证和 DAST；此分支发现配置不会单独发现 Pull Request。Jenkins 不会 push、合并或批准 PR，合并由人工决定。

## 流水线和部署约定

流水线依次执行仓库校验、应用测试、Semgrep SAST、Trivy SCA、Terraform 格式与验证及 Checkov（存在 Terraform 时）、容器构建和扫描、测试环境部署、提交 SHA 核对、ZAP DAST 和证据归档。缺少受支持的清单或源码类型时会标为 `SKIPPED`；已配置的门禁失败会使构建失败。

部署步骤调用：

```text
scripts/cd/trigger-deployment.sh <full-commit-sha>
```

部署实现必须生成非空 `.ci/deployment.env`，包含且仅包含一次以下必需键（允许附加其他键）：

```text
DEPLOYED_COMMIT=<full-commit-sha>
DAST_TARGET_URL=<http-or-https-url>
```

`DEPLOYED_COMMIT` 必须与 `TESTED_COMMIT` 相同，且为完整的 40 位小写 Git SHA。Jenkins 必须能访问 `DAST_TARGET_URL`。触发脚本输出保存在 `.ci/reports/deployment.log`。

## 构建证据

在 `mmiv-ci` 对应分支构建中查看 **Console Output** 和 **Artifacts**。Jenkins 会归档 `.ci/reports/**/*` 和 Maven Surefire XML，包括构建元数据、Semgrep/Trivy/Checkov 扫描结果、部署日志、ZAP 报告及测试报告。合并前应核对 `build-metadata.json` 中的 `tested_commit` 与待合并 SHA。

## 故障归属

| 失败项                                     | 负责人及处理方向                                             |
| ------------------------------------------ | ------------------------------------------------------------ |
| 应用测试或测试命令失败                     | 应用负责人检查代码、测试和项目测试接口。                     |
| Semgrep 规则或发现项                       | 安全负责人调整规则或修复问题。                               |
| Trivy 依赖发现项                           | 应用/依赖负责人升级或替换依赖。                              |
| Docker 构建或镜像扫描                      | Docker 负责人修复构建或镜像问题。                            |
| Terraform 格式、验证或 Checkov             | Terraform 负责人检查配置；Jenkins 不执行 `terraform apply`。 |
| Ansible 或 AWS 部署失败                    | 对应自动化或 AWS 负责人排查；Jenkins 不持有 AWS 部署凭据。   |
| 部署触发器、环境文件、SHA 或 DAST URL 无效 | 部署负责人修复部署接口和交接文件。                           |
| ZAP 扫描失败                               | 安全和部署负责人修复测试环境或安全问题。                     |
| 分支索引认证失败                           | Jenkins 主机负责人检查只读 `github-pat` 及仓库访问。         |

Jenkins 维护者负责 CI 编排、凭据、主机运行和证据保留，不应通过修改 Jenkins 文件来绕过应用、Docker、Terraform、Ansible 或 AWS 门禁失败。

## Docker Socket 安全限制

控制器以 root 运行，并挂载主机 Docker socket 来构建和检查镜像。访问该 socket 相当于对本地 Docker Engine 和其管理容器拥有管理员权限。Web 端口仅绑定 `127.0.0.1`，禁用匿名访问；不得将控制器暴露给不可信网络。生产方案应使用权限受限的短生命周期 Agent，而非挂载控制器的 Docker socket。

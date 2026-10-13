# MMIV 本地 Jenkins 运行指南

## 1. 使用范围

本项目通过 Docker 提供可复现的本地 Jenkins 环境，用于开发、课程演示和 CI 验证。组员不需要单独安装 Jenkins、Java、Semgrep、Trivy 或 Checkov，这些工具由 Jenkins 镜像提供。

本环境不是生产级 Jenkins。Jenkins Web 端口只绑定到 `127.0.0.1`，不要将它直接暴露到公共网络。

每位组员可以分别启动一次 Jenkins，验证 Windows、macOS 和 Linux 环境是否一致。验收完成后，建议只保留一台指定电脑运行共享 Jenkins，避免多台 Jenkins 同时轮询同一个 GitHub 仓库并重复执行同一次 push。

## 2. 前置条件

需要安装：

- Git；
- Docker Desktop，或兼容的 Linux Docker Engine；
- Docker Compose v2；
- 至少约 6 GB 可用内存；
- Windows 使用 PowerShell 7 或 Windows PowerShell 5.1；
- macOS 和 Linux 使用兼容 POSIX 的 Shell。

先启动 Docker Desktop，然后确认 Docker 和 Compose 可用：

```powershell
docker info
docker compose version
```

两个命令都必须成功。若 `docker info` 报错，应先等待 Docker Desktop 完全启动。

## 3. 获取最新代码

首次使用：

```powershell
git clone https://github.com/est19oox/Media-Migration-and-Integrity-Validation-.git
cd Media-Migration-and-Integrity-Validation-
```

已经有本地仓库时：

```powershell
git switch main
git pull --ff-only origin main
```

若要测试自己的 feature 分支，该分支必须已经推送到 GitHub，并包含仓库根目录的 `Jenkinsfile`。较早创建的分支需要先同步最新 `main`。

## 4. 创建本机配置

Windows：

```powershell
Copy-Item .\jenkins\.env.example .\jenkins\.env
```

macOS/Linux：

```sh
cp jenkins/.env.example jenkins/.env
```

编辑 `jenkins/.env`：

```dotenv
JENKINS_ADMIN_ID=admin
JENKINS_ADMIN_PASSWORD=<自己的本地强密码>
JENKINS_PORT=18080
GITHUB_USERNAME=<自己的 GitHub 用户名>
GITHUB_TOKEN=<自己的只读 GitHub Token>
GITHUB_REPOSITORY_URL=https://github.com/est19oox/Media-Migration-and-Integrity-Validation-.git
```

GitHub Token 使用 fine-grained personal access token，仅授权访问本项目私有仓库和读取代码。它不需要 push、仓库管理、Workflow 修改或 PR 审批权限。

`jenkins/.env` 已被 `.gitignore` 忽略。不要提交、共享或截图传播其中的密码与 Token。

默认主机端口为 `18080`。如果该端口已被占用，可在每台电脑的 `jenkins/.env` 中改为其他未使用端口，例如：

```dotenv
JENKINS_PORT=18081
```

容器内部仍使用 Jenkins 标准端口 `8080`，这是正常现象。

## 5. 启动 Jenkins

Windows：

```powershell
.\scripts\jenkins-up.ps1
```

macOS/Linux：

```sh
./scripts/jenkins-up.sh
```

若 macOS/Linux 报告脚本没有执行权限：

```sh
chmod +x scripts/jenkins-*.sh
./scripts/jenkins-up.sh
```

启动脚本会：

1. 检查 Docker Engine；
2. 构建固定版本的 Jenkins 镜像；
3. 启动 Jenkins 容器；
4. 通过 Configuration as Code 创建 `mmiv-ci` Multibranch Pipeline；
5. 自动执行 smoke test。

首次启动需要下载基础镜像、插件和安全扫描工具，因此耗时会明显长于后续启动，并需要稳定的网络连接。

看到类似以下输出表示 Jenkins 控制器和 `mmiv-ci` Job 已就绪：

```text
PASS: Jenkins health endpoint and mmiv-ci job are ready.
Jenkins is available at http://localhost:18080
```

使用 `jenkins/.env` 中配置的管理员账号和密码登录：

```text
http://localhost:18080
```

如果修改了 `JENKINS_PORT`，应使用修改后的端口访问。

## 6. 单独执行健康检查

Windows：

```powershell
.\scripts\jenkins-smoke-test.ps1
```

macOS/Linux：

```sh
./scripts/jenkins-smoke-test.sh
```

Smoke test 只确认：

- Jenkins 登录页面可访问；
- `mmiv-ci` Job 已创建且可访问。

Smoke test 通过不代表业务测试、安全扫描、基础设施检查、部署和 DAST 全部通过。

## 7. 停止 Jenkins

Windows：

```powershell
.\scripts\jenkins-down.ps1
```

macOS/Linux：

```sh
./scripts/jenkins-down.sh
```

普通停止会保留 `mmiv_jenkins_home` Docker volume，因此 Jenkins Job、配置和构建记录不会丢失。

只有确实需要完全重置本机 Jenkins 时才使用 purge：

```powershell
.\scripts\jenkins-down.ps1 -Purge
```

```sh
./scripts/jenkins-down.sh --purge
```

Purge 会永久删除本机 Jenkins 配置和构建历史，执行前必须确认这些数据不再需要。

## 8. GitHub 分支发现与构建

Jenkins 会从 GitHub 拉取代码，不会读取开发者当前未提交的本地文件。`mmiv-ci` 每两分钟扫描一次远程仓库，并发现包含根目录 `Jenkinsfile` 的分支。

要触发某个 feature 分支的 CI：

1. 确保该分支包含最新的 `Jenkinsfile`；
2. 将提交推送到 GitHub；
3. 等待最多约两分钟，或在 Jenkins 中点击 `mmiv-ci` → **Scan Multibranch Pipeline Now**；
4. 在对应分支 Job 中查看 Console Output 和 Artifacts。

Jenkins 会记录实际测试的完整 Git SHA。Jenkins 不会自动 push、merge 或批准 PR；最终合并仍由组员人工完成。

## 9. Pipeline 阶段

当前流水线包括：

1. Checkout；
2. Repository Validation；
3. Build and Unit Test；
4. Semgrep SAST；
5. Trivy SCA；
6. Terraform、Checkov 和容器检查；
7. 部署到测试环境；
8. 部署版本验证；
9. ZAP DAST；
10. 收集并归档证据。

流水线红色不一定表示 Jenkins 安装失败，应根据失败阶段联系对应负责人：

| 失败阶段                         | 主要负责人                           |
| -------------------------------- | ------------------------------------ |
| Unit Test                        | 业务代码负责人                       |
| Semgrep SAST                     | 安全负责人或对应代码负责人           |
| Trivy SCA                        | 依赖及业务代码负责人                 |
| Terraform/Checkov                | Terraform/IaC 负责人                 |
| Docker Build/Image Scan          | Docker 负责人                        |
| Deploy/Verify                    | CD、Ansible、Terraform 或 AWS 负责人 |
| ZAP DAST                         | 安全和部署负责人                     |
| Jenkins 启动、分支发现、日志归档 | Jenkins 负责人                       |

当前集成仓库还需要部署负责人提供以下接口：

```text
scripts/cd/trigger-deployment.sh <full-commit-sha>
```

该脚本需要生成 `.ci/deployment.env`，至少包含：

```dotenv
DEPLOYED_COMMIT=<实际部署的完整 Git SHA>
DAST_TARGET_URL=<Jenkins 可以访问的 HTTP 或 HTTPS 地址>
```

在该接口完成前，Jenkins 本身可以正常启动，但完整流水线可能在部署或 DAST 阶段失败。

## 10. 常见问题排查

### Docker Engine unavailable

启动 Docker Desktop，等待状态变为 Running，再执行：

```powershell
docker info
```

### 端口被占用

修改 `jenkins/.env` 中的 `JENKINS_PORT`，然后重新运行启动脚本。

### Jenkins 无法读取私有仓库

检查：

- `GITHUB_USERNAME` 是否正确；
- Token 是否过期；
- Token 是否获得本项目仓库访问权；
- Token 是否具有代码读取权限。

修改 `.env` 后重新运行启动脚本。

### `mmiv-ci` 一直没有就绪

查看容器状态与日志：

```powershell
docker compose --env-file jenkins/.env -f jenkins/docker-compose.yml ps
docker compose --env-file jenkins/.env -f jenkins/docker-compose.yml logs jenkins
```

### feature 分支没有出现在 Jenkins

确认：

- 分支已经推送到 GitHub；
- 分支包含根目录 `Jenkinsfile`；
- Jenkins Token 能读取该分支；
- 已等待自动扫描，或手动执行 **Scan Multibranch Pipeline Now**。

### 多台电脑重复执行同一提交

每台 Jenkins 都会独立扫描同一个 GitHub 仓库。多人同时运行时，同一次 push 可能被重复执行。

建议所有组员先分别完成一次本地可复现性验收，然后停止非指定主机上的 Jenkins，只保留一台作为小组共享 CI。若需要长期稳定运行，应把共享 Jenkins 部署到持续在线的受控环境，而不是依赖某位组员的个人电脑。

### macOS/Apple Silicon

Jenkins、Java 和 ZAP 镜像已经固定为支持多平台的镜像或 digest，设计上支持 Intel/AMD 和 Apple Silicon。但是当前仍需要一位 Mac 组员完成真实主机验收，并保存操作系统、Docker 版本、smoke-test 输出以及失败日志。

## 11. 日志与成果物

进入 Jenkins 中对应的分支构建后：

- **Console Output**：查看每个阶段的执行日志；
- **Test Result**：查看 Maven Surefire 测试结果；
- **Artifacts**：下载保留的安全扫描和部署证据。

典型成果物包括：

- `.ci/reports/build-metadata.json`；
- `.ci/reports/semgrep.json`；
- `.ci/reports/trivy-sca.json`；
- `.ci/reports/checkov.json`；
- `.ci/reports/trivy-image.json`；
- `.ci/reports/deployment.log`；
- `.ci/reports/zap.log`；
- `.ci/reports/zap-report.json`；
- `.ci/reports/zap-report.html`；
- Maven `target/surefire-reports` 测试报告。

提交或合并前，应确认报告中的 `tested_commit` 与准备合并的 Git SHA 一致。

## 12. 小组验收记录建议

每位组员完成本机验证后，记录：

- 姓名；
- 操作系统和 CPU 架构；
- Docker Desktop/Engine 版本；
- `docker compose version`；
- Jenkins 使用的端口；
- `jenkins-up` 和 smoke-test 是否通过；
- 首次启动耗时；
- 遇到的问题与解决方法；
- Jenkins 页面和成功输出截图。

不要在截图或日志中暴露 GitHub Token、Jenkins 密码、AWS 凭据或其他敏感信息。

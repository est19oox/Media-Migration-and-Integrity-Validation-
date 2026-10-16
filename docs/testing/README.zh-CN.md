# 测试与格式化

[English](README.md) | [简体中文](README.zh-CN.md)

## Maven 服务

每个服务都是独立 Maven 项目。使用 Java 25，在仓库根目录构建并测试单个服务：

```sh
./services/user-access-service/mvnw -B -f services/transfer-service/pom.xml clean verify
```

将 `transfer-service` 替换为目标服务目录。仓库测试辅助脚本会运行全部服务
Maven 项目并验证测试报告存在：

```sh
bash scripts/ci/run-tests.sh .
```

该脚本需要 Maven 和 Python 3。User Access Oracle 集成测试通过
`MMIV_USER_ACCESS_ORACLE_IT=true` opt-in，并需要单独 Oracle 测试配置和数据库；
普通单元测试不会启用该 profile。

Transfer 的 S3 单元测试使用 mock `S3Client`；Feign 契约测试使用本地 HTTP
server 测试请求/响应编解码和服务发现。这些测试不会执行真实 S3 传输。覆盖
行为和命令见[Transfer 与 Integrity 校验](../services/transfer-integrity.zh-CN.md)。

## 其他测试层

- `tests/api/endpoints.json` 是 `scripts/cd/test-endpoints.py` 使用的端点测试输入。
- `tests/ci/` 下 Python 测试验证部署辅助脚本、CI 契约和运行时配置。
- 浏览器/页面测试位于 `tests/`；已配置的测试使用 Node 内置 test runner。
- `tests/api/` 下集成接受测试脚本需要已运行的服务及其支持基础设施。
- GitHub Actions 使用独立的验证和发布工作流；发布验证路径包含已部署服务检查
  和安全扫描。

端点契约和部署证据见[API 与 DAST 验证](api-validation.zh-CN.md)。

## 格式化

安装根目录锁定的 Prettier 依赖并运行检查：

```sh
npm ci
npm run format:check
```

该命令检查仓库中的 HTML、CSS、JavaScript、JSON、Markdown、YAML 和 YML。
完整格式检查还会对每个 Maven 服务运行 Spotless，并运行
`terraform fmt -check`。Windows：

```powershell
.\scripts\format\check-all.ps1
```

Linux、macOS 或 WSL：

```sh
bash scripts/format/check-all.sh
```

这些命令不会修改文件。Windows 使用 `.\scripts\format\format-all.ps1`，
Linux/macOS/WSL 使用 `bash scripts/format/format-all.sh` 应用格式化。

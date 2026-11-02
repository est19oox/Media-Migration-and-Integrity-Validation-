# MMIV CI/CD

> **状态：草案——需要团队讨论**

## 1. 当前设置

MMIV 当前同时使用：

- **GitHub Actions**
- **Jenkins**

GitHub Actions 提供自动化验证和 AWS 部署工作流。

Jenkins 提供可复现的基于 Docker 的 CI 环境，并可运行构建、测试、安全检查和本地集成验证。

## 2. 待确定事项

团队需要确认：

1. 哪个系统是权威 CI 流水线？
2. PR 合并前必须通过哪些检查？
3. 本地 Jenkins 实例是否应作为必需的合并检查？
4. 哪个系统负责 AWS 部署？
5. GitHub Actions 和 Jenkins 有意共享哪些 CI 阶段？
6. 如何避免重复构建和检查？

## 3. 当前原则

在职责明确之前，应将 GitHub Actions 和 Jenkins 视为彼此独立的 CI/CD 机制。

不应预先假定二者最终的所有权、合并和部署职责。

详细的 Jenkins 设置说明位于：

`docs/jenkins/`

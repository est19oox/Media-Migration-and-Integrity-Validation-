# MMIV 文档

[English](README.md) | [简体中文](README.zh-CN.md)

使用本索引可直接打开维护中的专题指南。所有指南均提供中英文配对链接。

## 架构

- [系统架构](architecture.zh-CN.md) | [English](architecture.md) — 服务、
  请求流程、数据存储和部署边界。
- [事件契约 v2](contracts/events/v2/README.zh-CN.md) |
  [English](contracts/events/v2/README.md) — 发布方规则和 Schema。
- [事件 JSON Schema](../contracts/events/v2/mmiv-event.schema.json) — 机器可读契约。

## 开发

- [本地开发](development/local-setup.zh-CN.md) |
  [English](development/local-setup.md) — 环境要求、前端、服务测试和集成运行配置。
- [本地 Oracle 数据库](database/README.zh-CN.md) |
  [English](database/README.md) — 本地数据库容器、连接信息和卷安全。

## 服务

- [服务目录](services/catalog.zh-CN.md) |
  [English](services/catalog.md) — 服务职责、端口和依赖。
- [API Gateway](services/api-gateway.zh-CN.md) |
  [English](services/api-gateway.md) — 身份验证、路由、资源打包和接受测试。
- [Reporting](services/reporting.zh-CN.md) |
  [English](services/reporting.md) — 事件处理、访问模型、持久化和 Smoke Test。
- [Transfer 与 Integrity](services/transfer-integrity.zh-CN.md) |
  [English](services/transfer-integrity.md) — 传输契约、重试、清理和验证。
- [服务集成流程](services/integration.zh-CN.md) |
  [English](services/integration.md) — 授权、元数据、RabbitMQ 和报告。
- [熔断器](services/circuit-breakers.zh-CN.md) |
  [English](services/circuit-breakers.md) — 覆盖调用和故障行为。

## 测试

- [测试与格式化](testing/README.zh-CN.md) |
  [English](testing/README.md) — Maven、CI、测试层和格式工具。
- [API 与 DAST 验证](testing/api-validation.zh-CN.md) |
  [English](testing/api-validation.md) — endpoint fixture、部署扫描和证据。

## 部署与运维

- [Docker 发布部署](deployment/docker-release.zh-CN.md) |
  [English](deployment/docker-release.md) — 托管发布、就绪检查和回滚。
- [Gateway 访问](deployment/gateway-access.zh-CN.md) |
  [English](deployment/gateway-access.md) — 浏览器和 SSM 隧道访问。
- [登录与受保护访问](deployment/login-access.zh-CN.md) |
  [English](deployment/login-access.md) — 浏览器会话和授权。
- [Ansible 主机准备](deployment/ansible.zh-CN.md) |
  [English](deployment/ansible.md) — 当前主机准备和受保护配置暂存。
- [手动 Ansible role](deployment/ansible-manual-role.zh-CN.md) |
  [English](deployment/ansible-manual-role.md) — 保留的手动部署和回归测试行为。
- [Terraform](deployment/terraform.zh-CN.md) |
  [English](deployment/terraform.md) — 当前 Terraform 根目录和现有 EC2 保护。
- [可选本地 Jenkins](jenkins/local-setup.zh-CN.md) |
  [English](jenkins/local-setup.md) — 本地演示控制器；托管 CI/CD 仍由 GitHub Actions 执行。

## 管理

- [管理员访问](admin/administrator-access.zh-CN.md) |
  [English](admin/administrator-access.md) — 初始化、邀请、密码重置和目标权限。
- [文件夹工作区](admin/folder-workspace.zh-CN.md) |
  [English](admin/folder-workspace.md) — 浏览器流程、权限、文件操作和验证清理。

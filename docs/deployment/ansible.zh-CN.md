# Ansible 基础设施

[English](ansible.md) | [简体中文](ansible.zh-CN.md)

托管部署工作流使用 Ansible 准备 EC2 主机并暂存受保护配置。它通过
`stage-integrity.yml` 暂存可选 Integrity Oracle 设置，通过
`stage-user-access.yml` 暂存 User Access 数据库设置和签名密钥。应用发布和
恢复由通过 AWS Systems Manager 运行的 Docker stack 脚本完成，而非旧的逐服务
部署 role。

`setup-host.yml` 协调主机前置条件、Docker Compose plugin、共享媒体目录和网络。
SSM inventory 在工作流运行时生成；部署不需要公网 SSH 或公开后端端口。

`microservice` role、`playbook.yml` 和 `rollback.yml` 仍保留，供手动部署和
回归测试使用，但它们不是自动化七服务发布路径。当前工作流和恢复行为见
[Docker 发布部署](docker-release.zh-CN.md)。

# MMIV Terraform

[English](terraform.md) | [简体中文](terraform.zh-CN.md)

此目录是 GitHub Actions 部署工作流使用的 Terraform 根目录。

`.github/workflows/cicd.yml` 会先运行 Terraform；只有成功后才开始共享主机
准备和 stack 部署。镜像构建可与 Terraform 并行执行。

GitHub Actions 能承担 Terraform 和应用 OIDC role 之前，必须先应用一次性
bootstrap。Bootstrap 会创建 GitHub OIDC provider、GitHub Actions role 和
Terraform state bucket。

应用 S3 Bucket 固定为 `media-migration-dev-landing`，以确保 Terraform、
Ansible、EC2 IAM 权限和 Transfer Service 使用同一 Bucket。

## 保留 EC2 主机

EC2 资源忽略 AMI、user data 和公网 IP 关联漂移，并设置 `prevent_destroy`。
较新的 Amazon Linux AMI 仅在创建主机时使用；日常部署不会因此替换现有主机。
新主机配置公网 IP 关联，但不会通过替换现有主机来协调关联状态。已停止主机
释放自动分配公网 IPv4 后，可能报告 `associate_public_ip_address = false`；
这不应阻止 Terraform 在工作流启动实例前运行。该例外不修复真正缺少出站
连接的主机；此类问题需要单独修复网络配置。其他会导致替换的变更会使 plan
失败，而不是终止实例。安全补丁和有意替换主机属于单独维护操作。

初始化后，CI 会在 plan 前运行 `scripts/cd/adopt-existing-ec2.sh`：

- 若存在未跟踪且未终止的 `Name=MMIV-EC2` 实例，则导入到 `aws_instance.mmiv`
  而非创建新实例。
- 若没有匹配实例，则允许 Terraform 创建。
- 若存在多个匹配实例、state 指向不同实例或 AWS/state 访问出错，则停止并
  要求先协调 state。

Plan guard 会在 apply 前拒绝 EC2 删除和替换。部署使用
`terraform output -raw mmiv_instance_id`，不使用 tag 搜索返回的首个实例。
已停止的托管实例会启动并复用。

导入 EC2 不会导入 VPC、subnet、IAM 资源或其他依赖项。现有主机依赖与配置不
匹配时，可能产生被阻止的替换 plan；应协调这些资源，不要绕过保护或创建重复
主机。保留既有 S3 backend 和锁。上述检查保护此工作流，不阻止手动 AWS 删除
或从 Terraform 配置中移除资源。

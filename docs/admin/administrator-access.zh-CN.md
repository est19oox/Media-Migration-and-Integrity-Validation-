# 管理员访问

[English](administrator-access.md) | [简体中文](administrator-access.zh-CN.md)

托管 EC2 部署会在 User Access 就绪前尝试创建一次默认 `admin` 账户。主机将随机生成的 48 位初始密码保存在 `/etc/mmiv/managed-oracle/bootstrap-admin.json`（仅所有者可读）；Oracle 仅存储 BCrypt 哈希。账户和密码不会因应用更新、回滚或重启而重置。已有管理员或首个邀请记录会阻止再次初始化；普通用户名不会被自动提升。

## 登录默认管理员

部署完成后，通过授权的 EC2 Systems Manager shell 私下读取初始密码：

```sh
sudo python3 -c "import json; print(json.load(open('/etc/mmiv/managed-oracle/bootstrap-admin.json'))['password'])"
```

通过 Gateway 打开 `/login.html`，以 `admin` 登录。Home 页面会显示 Administration 入口。对外访问管理员页面应使用 HTTPS；也可通过加密的 SSM 端口转发访问：

```sh
aws ssm start-session --target i-YOUR_INSTANCE \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8080"],"localPortNumber":["18080"]}'
```

Administration 与 Home、Upload、Files、Logs 共用 HttpOnly cookie，无需二次登录。会话最长 30 分钟；退出会清除共享 cookie。每次管理员 API 请求都会核对 Oracle 中的当前角色和账户状态；JWT 中的角色声明本身不授予管理员权限。权限变更会在用户下一次操作时生效。

外部 Oracle 部署默认不创建管理员。首次启动前，可选择设置 `MMIV_BOOTSTRAP_ADMIN_ENABLED=true`、`MMIV_BOOTSTRAP_ADMIN_USERNAME` 和私密的 `MMIV_BOOTSTRAP_ADMIN_PASSWORD`。密码至少 16 个字符且 UTF-8 编码不超过 72 字节；不得提交到仓库。托管 EC2 只向 User Access 注入这些值。

## 邀请管理员

1. 被邀请者先在 `/signup.html` 注册普通账户。
2. 管理员登录后从 Home 打开 Administration，在 **Invite another administrator** 中输入已注册用户名并创建邀请。若当前使用本地 SSM 隧道，请在生成邀请前将网站地址设为收件人可访问的 Gateway 地址。
3. 私下发送生成的邀请链接。系统不会自动发送邮件。
4. 被邀请者打开 `/invite.html#invite=...` 并登录；随后接受邀请。服务器会核对登录身份、账户所有权、状态、令牌哈希、过期时间和使用状态，并在单个事务中提升账户。该账户的其他待处理邀请也会失效。

邀请令牌含 256 位随机数据，有效期 24 小时；Oracle 仅保存 SHA-256 哈希。页面加载后会从地址栏移除链接片段。仅打开链接不会获得权限；兑换请求要求受邀用户已登录。审计记录不包含邀请密钥、密码或 JWT。

管理员可管理目标权限并邀请其他已注册账户。普通注册不能选择管理员角色；验证账户不能通过邀请成为管理员。

## 管理目标权限

通过 Administration 创建顶层文件夹（例如 `SW001` 或 `semester2`），然后选择用户和文件夹，授予以下权限之一：

- **READ**：查看和下载。
- **WRITE**：上传、查看和下载；包含 READ。

创建文件夹会在 S3 中创建空目录标记，并在 Oracle `MMIV_FOLDERS` 中登记。不会覆盖已有标记。管理员可访问所有已登记文件夹；普通用户只能访问当前获授权限的文件夹。Bucket 级授权不会自动授权新文件夹上传。

核心 Gateway API：

- `GET /user-access/api/admin/me`
- `GET /user-access/api/admin/users?username=...`
- `PUT /user-access/api/admin/users/{id}/destinations`，请求体为 `{"destination":"...","permission":"READ"}` 或 `WRITE`
- `DELETE /user-access/api/admin/users/{id}/destinations?destination=...`
- `POST /user-access/api/admin/invitations`，请求体为 `{"username":"..."}`
- `POST /user-access/api/admin/invitations/accept`，请求体为 `{"token":"..."}`；需要受邀用户的登录会话

权限和邀请操作会在数据库提交后发送 RabbitMQ 审计事件。用户查询不返回密码哈希。浏览器通过 Transfer 固定的 `/api/v1/portal/admin/**` 路由发起请求；Transfer 校验共享 cookie 和 CSRF token 后再转发 JWT。

## 首位管理员的运维邀请

若未启用默认账户初始化，授权操作员可通过 AWS CLI 和 SSM 为已有普通账户创建首个邀请：

```sh
python3 scripts/admin/bootstrap-first-admin.py \
  --instance-id i-YOUR_INSTANCE \
  --user-id UUID_RETURNED_BY_SIGNUP \
  --base-url https://YOUR_GATEWAY \
  --region ap-southeast-1
```

只有数据库成功接受邀请后，命令才会输出链接；SSM 只接收令牌哈希。并发操作会通过数据库锁串行化。已有管理员或初始化历史会阻止重复创建首个邀请。`--replace-expired` 仅在没有管理员时替换已过期且未使用的首个邀请。普通邀请应在 Administration 页面中创建。

## 密码重置和停用账户

在 Administration 中搜索账户并创建密码重置链接，确认后私下发送给账户持有人。系统不发送邮件。链接使用 256 位随机令牌，Oracle 仅保存其 SHA-256 哈希；一小时后过期且只能使用一次。重新创建链接会使旧链接失效。新密码需为 8 至 72 个 UTF-8 字节，并以 BCrypt 哈希保存。

重置密码会递增 `AUTH_VERSION` 并使现有 JWT/浏览器会话失效。管理员可重置普通活动用户或自己的账户，不能重置其他管理员。提升为管理员会使他人创建的重置链接失效；重置密码也会使待处理管理员邀请失效。EC2 初始密码文件不会因重置而更新，后续部署不会覆盖新密码。

**Deactivate selected user** 会将 `APP_USERS.ACTIVE` 设为 0 并递增 `AUTH_VERSION`，但保留用户记录、文件、权限、元数据和审计历史。该账户的登录、会话和待处理链接会失效，用户名仍被占用。管理员不能停用其他管理员，但可以停用自己（包括最后一位管理员）；若最后一位管理员被停用，需由授权操作员恢复访问。此操作是软删除；临时验证账户清理仍单独执行硬删除。

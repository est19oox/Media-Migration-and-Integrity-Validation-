# 文件夹工作区

[English](folder-workspace.md) | [简体中文](folder-workspace.zh-CN.md)

通过 Gateway 登录后会进入 `/home.html`；已登录用户访问根 URL 也会进入 Home。Home 根据账户权限链接到 Upload、Browse & download、My activity，以及仅管理员可见的 Log dashboard 和 Administration。所有页面共用最长 30 分钟的 HttpOnly cookie。管理员身份以 Oracle 当前角色为准。

## 管理员流程

1. 在 Administration 中创建顶层文件夹，例如 `SW001` 或 `semester2`。
2. 搜索用户名或从用户列表选择账户；空搜索最多返回 100 个账户。
3. 筛选或选择文件夹。
4. 授予 **View and download**（READ）或 **Upload, view and download**（WRITE），然后保存；撤销操作会移除授权。管理员对已登记文件夹始终拥有完整访问权限。

系统在 S3 Bucket（默认 `media-migration-dev-landing`，或配置的 `MMIV_BUCKET_NAME`）创建空的 `<folder>/` 标记，并在 Oracle `MMIV_FOLDERS` 中登记名称。已有标记和文件不会被覆盖。文件夹名称最多 80 个 ASCII 字符，只能包含字母、数字、空格、点、下划线和连字符，且必须以字母或数字开头；末尾空格/点以及 `migrations`、`upload` 根目录保留。创建仅支持顶层文件夹；本地上传可在获授权文件夹中保留嵌套路径。

## 用户流程

Upload 只显示具有 WRITE 权限的文件夹。Files 显示获授权文件夹，可浏览嵌套目录、预览和下载。拥有 READ 权限的用户均可读取共享文件夹内容，与上传者无关。权限变更会在后续请求中生效。没有授权的账户会看到 **No access to folder**。

元数据将文件分配到固定 Bucket 中的 `<folder>/migrations/<metadataId>/<relativePath>`。实际存储使用 UUID，防止同名上传覆盖；页面隐藏 Bucket 和对象键。新传输要求计划所有者具有当前 WRITE 权限；下载、元数据读取和报告要求当前 READ 权限。普通用户的 My activity 仅显示自己的上传、下载和删除记录，不显示原始事件、内部 ID 或他人活动。Log dashboard 及其 API 仅管理员可访问。

## API 与部署

浏览器操作通过 Transfer 的 `/api/v1/portal/**` 路由。Cookie 写操作需要 CSRF 保护；Transfer 将验证后的 JWT 转发到固定的 User Access 和 Reporting 内部路由。直接调用 User Access 管理 API 需要已认证 JWT，并逐次验证当前管理员角色。文件 API 在访问 S3 前会检查对象键所属的已登记顶层文件夹；列表使用固定 `<folder>/` 前缀，每页读取 100 项。

变更后应一并重新部署 User Access、Discovery/Metadata、Transfer、Reporting 和 Gateway。无需新增依赖或密钥。Oracle 启动时会幂等创建文件夹目录；既有账户、密码哈希、权限和对象会保留。Bucket 级授权不会转换成每个文件夹的授权，未登记文件夹中的旧对象不会自动迁移。

验证流程为每次运行创建保留名称 `mmiv-verification-<run UUID>`，并仅向生成的测试账户授予访问权限。验证文件夹不出现在用户列表中，也不能通过 Administration 创建或授权。清理会依次删除本次运行标记的对象版本、元数据、报告和迁移记录，再移除文件夹标记、目录、权限和测试账户。若仍有意外对象或版本，清理会拒绝删除文件夹；清理失败会导致验证失败。提供的测试账户必须显式配置 `userAccessSmoke.destination`（或 `MMIV_VERIFICATION_DESTINATION`），并已拥有普通文件夹 WRITE 权限；该账户和文件夹会保留。

## 删除、重名与移动端

管理员可删除文件、嵌套文件夹或已登记的顶层文件夹。删除文件或文件夹会永久移除匹配的所有 S3 版本和删除标记；删除顶层文件夹还会移除其元数据、目录记录和全部权限。嵌套删除保留根目录和同级内容。S3 删除失败会阻止后续元数据清理，便于重试；审计记录会保留。

普通用户只能删除由 Metadata 分配给自己且仍具有 WRITE 权限的文件。后端会同时核验所有权和对象键；猜测文件 ID 不能绕过检查。无所有者记录的旧对象仅管理员可删除。

同一逻辑目录中的重复文件名会依次使用 `report.pdf`、`report (2).pdf`、`report (3).pdf`；不同子目录可使用相同文件名。Metadata 在 Oracle 文件夹锁内分配并预留名称，Transfer 仍使用独立 UUID 对象键。清理和确认的完整性失败删除会释放预留；未完成或结果不确定的上传会保留预留。旧重复对象不会自动重命名。

页面适配手机，文件夹列表提供触控行。上传显示网络进度和后续处理阶段；可隐藏进度但不中断上传，完成或失败时会显示结果。PWA 安装和 Service Worker 注册已禁用，网站仍可在手机浏览器中使用。

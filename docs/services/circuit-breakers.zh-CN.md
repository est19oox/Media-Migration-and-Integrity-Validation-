# 服务熔断器

[English](circuit-breakers.md) | [简体中文](circuit-breakers.zh-CN.md)

内部 HTTP 客户端使用 Resilience4j core 2.3.0，不使用 Spring AOP 或 Boot
starter。共享实现位于 `common/src/main/java/mmiv/resilience/DependencyCircuits.java`。
每个运行进程针对每个依赖和 endpoint 独立维护熔断器；Metadata 与 User Access
故障不会共用熔断状态。Transfer 的 Integrity Feign 客户端使用相同策略及 Eureka
服务身份。

## 覆盖的调用

- Transfer → Metadata：分配、所有权和验证清理。
- Transfer → User Access：当前账户、文件夹、权限及管理员检查。
- Transfer → Integrity：完整性校验，位于现有有界重试流程内。
- Transfer → Reporting：管理员日志和个人历史。
- Metadata → User Access、Transfer、Reporting，包括验证清理。
- Integrity → Metadata：验证调用者及精确分配对象。
- Reporting → Metadata 和 User Access：报告访问、管理员日志、个人历史及清理授权。

Gateway 路由保留既有连接/响应 timeout。RabbitMQ 继续使用持久 outbox、
publisher confirm 和消费者重试策略。S3 使用 SDK 自身处理及受保护的完整性
失败清理策略。

## 策略与恢复

熔断器观察最近 10 次完成的可用性调用。至少调用 5 次后，若失败率达到 50% 或
更高则打开；打开后阻断调用 30 秒，再允许 2 次恢复探测。两次探测均成功则关闭；
恢复探测失败会再次打开。计时从熔断器打开时开始。慢调用阈值为 10 分钟，高于
现有客户端 deadline，避免健康的大文件传输仅因耗时较长而被判定为故障。状态
只存在于进程内，进程重启后重置。固定策略值定义在共享类中。

HTTP 连接错误、读取 timeout（包括 body 读取）、5xx、408 和 429 计为可用性
故障。普通 400/401/403/404 不计入。Checksum 不匹配是已完成的完整性结果，不是
依赖故障。熔断状态日志只记录固定依赖标签和状态变化，不记录 token、用户数据
或 URL。

打开的 REST 熔断器会走现有 Service Unavailable 路径（HTTP 503）。授权仍为
强制要求：依赖不可用时不会授予权限。Integrity 熔断打开时返回
`UNAVAILABLE`，并立即停止本次操作的后续校验尝试；Integrity 无法确认时，
Transfer 仍执行既有受保护清理策略。

熔断器不会重放写操作。现有有界 Integrity 校验可重试；浏览器上传、分配请求和
传输启动请求不会由此层自动重复。熔断保护不会引入异步取消：调用方 timeout
发生时，远端操作可能已开始。上传目前没有通用幂等 key，重试前应检查上传结果。
每个客户端仍使用各自既有 timeout。

## PWA 状态

PWA 注册已禁用；保留的 manifest 和 service worker 资源不属于已部署应用。
普通网站仍支持手机响应式浏览。

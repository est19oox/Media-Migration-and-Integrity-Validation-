# API 与 DAST 验证

[English](api-validation.md) | [简体中文](api-validation.zh-CN.md)

## 端点测试

`scripts/cd/test-endpoints.py` 读取 `tests/api/endpoints.json` 中的机器可读
用例。已部署验证工作流会针对已启动的服务运行配置好的 endpoint 用例。
fixture 数据应与已实现路由及响应契约保持一致。

集成流程使用 Gateway 的明确路由。Transfer 操作受元数据授权；不要使用接受
任意源路径或目标对象键的旧示例，请参考当前[服务集成契约](../services/integration.zh-CN.md)。

## DAST

发布验证工作流在部署就绪屏障通过后运行功能检查和 OWASP ZAP 扫描。其他服务
使用 baseline scan，Transfer 使用 API scan。Transfer API scan 导入
`tests/api/transfer-openapi.json`；其 hook 会先执行已认证的 multipart fixture
上传，检查完整性和 checksum，再使用返回的 file ID 请求内容、下载和 byte-range
读取。证据保存为 `upload-seed.json`。若 seed 缺失或失败，Transfer API scan
会失败。

这些上传检查会在 S3 创建对象。应使用工作流专用验证账户、保留目标、run tag
和清理流程；未经批准的清理方案，不要对生产 Bucket 运行 active scan。扫描
报告、上传 seed 证据和部署验证日志会作为工作流 artifact 保留。

发现报告不证明覆盖完整或安全评估通过；应检查工作流结果和扫描日志。

## 本地验证边界

单元测试不能证明在线 EC2、SSM、AWS IAM、S3 或 ZAP 行为。集成接受测试需要
运行中的本地应用栈和脚本要求的外部 fixture。完整部署就绪和回滚流程见
[Docker 发布部署](../deployment/docker-release.zh-CN.md)。

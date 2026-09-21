# 安全策略

## 当前状态

Community 源码已开放，目前仍处于开发预览阶段。漏洞、密钥或用户数据问题请使用
[GitHub 私密漏洞报告](https://github.com/Munable/be-your-eye/security/advisories/new)。不要在公开 Issue
中提交密钥、验证码、账号信息、设备标识、用户事件或摄像内容。

## 安全边界

- Catalog 与 Model Manifest 采用 RFC 8785 规范化 JSON 和 Ed25519 签名；App 内置验证公钥并支持双钥轮换。
- 模型许可与任务适配在进入服务器批准目录前人工审核；运行期只验证批准状态、签名、SHA-256、大小、runtime、adapter 与设备兼容性。
- 发布私钥不得进入仓库、App、CI 日志或普通构建节点；签名仅在隔离发布环境执行。
- Community 不启动账号、同步、推送或 AI 云服务。连接版的 FCM 只携带 `event_id` 和 `cursor_hint`；完整事件经鉴权 API 获取，不传媒体。
- 跨账号访问默认拒绝；Supabase 会话刷新、Outbox 幂等和删除级联必须有自动测试。首版的 `revoked_at` 只是任务/回执/推送资格标记，不宣称或测试为设备会话撤销。

## 依赖与供应链

所有依赖使用锁文件或精确版本；发布构建生成 SBOM。安全更新不能绕过合同 fixtures、逐模型许可审核、签名验证、确定性选模和回滚测试。发现已激活模型或 Catalog 被篡改时，客户端必须拒绝运行并显示明确错误，不得静默降级到未审核资源。

## 支持范围

项目目前处于 Community 开发预览阶段，没有对外生产安全支持范围。未来只有按 [`evidence/README.md`](evidence/README.md) 约定写入 `evidence/releases/` 的最新 Beta／正式发布构建属于支持范围；旧构建、开发签名、模拟器和未签名 Catalog 不构成生产安全承诺。发布门以 [`docs/RELEASE.md`](docs/RELEASE.md) 为准。

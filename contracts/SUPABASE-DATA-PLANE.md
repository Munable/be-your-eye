# Be Your Eye Supabase 数据平面

Supabase 只承载账号、任务摘要、事件事实、Outbox/回执和可选同步。三种手动创建入口不需要账号；登录仅用于同步和在线按需请求一张端侧加密触发图。

| 数据 | 传输 | 约束 |
| --- | --- | --- |
| 任务摘要 | Supabase Authenticated API | 复用现有 `visual_target`：参考使用 `reference_images`，类别传输使用 `text`（值是有限签名类别标签），读数使用 `none`；不写开放描述。 |
| 事件事实 | Supabase Authenticated API/FCM 游标 | payload 不含图片、原始帧或读数诊断日志。 |
| 触发图预览 | 私有 Realtime Broadcast | 只中继请求元数据和端侧密文，不持久化媒体；副本不超过 720 px/120 KiB。 |
| 模型目录/制品 | R2 固定 HTTPS URL | Android 验签、校验 Manifest 与 SHA-256 后才安装。 |

当前数据平面不包含语言模型接口、路由资源、向量存储或媒体存储。最终 schema、RLS、账号删除、Event 保留与 Realtime 密文中继由 `supabase/tests/` 执行全部迁移后验证；当前 hosted 和双物理设备验收状态只看 `evidence/current/04-cloud.json`。

# Evidence

只保留两类证据：

- `current/01-foundation.json`～`05-release.json`：五个当前结果流的唯一摘要。
- `releases/<catalog-version>.json`：每个已发布 Catalog 或构建的最终身份与验收摘要。

原始日志、runner 输出、模型字节、设备 serial、相机帧和重复实验报告不进入 Git。历史记录由 Git 提交保存。`passed` 只表示记录中写明的精确层级；unit、模拟器、物理设备和 hosted cloud 互不替代。

证据只回答“当前验证到了哪一层”，不定义产品、架构、开发顺序或发布标准。四份权威文档不得复制 Catalog 哈希、APK 身份、测试计数或阶段实验结果；它们只链接到这里。更新状态时覆盖对应的 current 摘要，正式发布时再新增一份不可变的 release 摘要。

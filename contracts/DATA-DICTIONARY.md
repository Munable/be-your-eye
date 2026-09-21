# Be Your Eye v3 数据字典

| 字段 | 语义 |
| --- | --- |
| `runtime_family` | `object_detection_v1`、`similarity_match_v1` 或 `reading_pipeline_v1`。 |
| `prompt_modes` | 签名运行时目标依赖：`object_class`、`reference_images` 或 `none`。 |
| `target_definition.mode` | 本地与 Supabase TaskConfig 都只使用 `object_detection`、`reference_images` 或 `none`；有限类别目标保留结构化 ID 与中英文标签。 |
| `class_map` | 包内签名 class table：raw class ID、稳定 target ID、中英文名称和有限别名。 |
| `artifacts[]` | 每个制品的 runtime、media type、固定 HTTPS URL、SHA-256 和字节数。 |
| `inputs[]` / `outputs[]` | 模型 tensor 的角色、dtype、layout、颜色空间和 runtime shape。 |
| `bindings[]` | Manifest DAG 的内部 tensor 连接；必须无环。 |
| `local_material_refs` | 仅参考图片使用的 App 私有素材引用；类别和读数为空。 |
| `observation` | 检测、读数或 `unavailable`；异常不转换为 absence/正常值。 |
| `event` | 目标出现/离开、读数越界或状态变化事实，不含媒体。 |
| Sync Event upsert | Event 的云同步结构，额外携带来源 `monitoring_device_id`；删除仍为 `entity=null`。 |

各边界按自己的明确 schema 验证；未知字段和不支持的 mode fail closed。

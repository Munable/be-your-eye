# Google Play 前台服务声明草稿

状态：`draft`。适用于 `app.beyoureyes.monitor` 的精确 Commercial AAB；最终演示视频 URL 必须来自上传到 Play track 的同一候选。

## Console 选择

- Foreground service type：`camera`
- 使用场景：用户主动把一台持续供电的手机固定在现场，在离开 App 后继续用后置相机本地监控其已确认的目标、现象或单行数字屏。
- 用户收益：当已确认的视觉条件满足时记录事件并按用户设置通知；普通相机帧不上传。

## 提交说明

Be Your Eye starts its camera foreground service only after the user opens a setup flow, grants camera permission, previews the live rear-camera result, confirms the target or reading, and taps the explicit start action. Continuous camera access is the core user-requested function: the phone must keep watching the fixed scene while the Activity is no longer visible. The persistent notification identifies the active monitor and provides a stop action. The app never starts this service from boot, a push message, or another background callback. It runs only one monitor at a time, releases CameraX when the user stops it or when the device reaches a critical thermal state, and stops within five seconds after the account entitlement becomes invalid.

## 视频脚本

1. 从 Play track 安装精确 Commercial AAB，显示版本并登录具有有效测试订阅的审核账号。
2. 打开一条已配置任务，在相机预览中确认目标稳定可见，再点击“开始监控”。
3. 返回桌面，展开持续通知，展示任务名称、正在监控状态和“停止”操作。
4. 回到 App 展示运行详情，再从通知点击“停止”，确认相机指示与前台服务通知消失。

最终填写：`PLAY_CAMERA_FGS_VIDEO_URL`。视频不得包含真实邮箱、令牌、购买凭据、用户参考图或其他 App 通知。

# MaiStatus Android

这是一个 Kotlin Android 原型，用于在用户明确授权后采集：

- 通过 Fused Location Provider 获取的位置；
- Android Activity Recognition 推断的活动类型；
- 通过 Notification Listener 读取音乐播放器通知中的歌曲标题/文本。

采集结果先写入 Room 本地待上传队列，再由 WorkManager 以 HTTPS `POST` JSON 发送到应用中填写的服务器地址。只有收到 2xx 响应才会删除队列事件；每个事件包含 UUID、事件时间、事件类型和 `Idempotency-Key`，网络失败会保留并重试。

```json
{
  "timestamp": 1720000000000,
  "location": {"latitude": 31.2, "longitude": 121.5},
  "activity": "3:87",
  "music": "歌曲名 - 歌手"
}
```

## 运行

使用 Android Studio 打开项目，连接 Android 设备或启动模拟器后运行 `app`。首次使用时填写服务器地址，授予定位、活动识别和通知权限，再点击开始采集。

音乐通知只处理用户在应用中明确填写的播放器包名，并验证媒体通知/媒体会话；QQ、微信及其他未允许的通知不会采集。定位精度取决于用户授权，界面支持暂停和停止。

设备写入凭证保存在应用私有偏好中，不会写入普通日志；服务端应使用事件 UUID 或 `Idempotency-Key` 去重。不要把位置数据发送到未经授权的第三方。

服务器地址用于数据上报，同时首页会向该地址追加 `GET /healthz` 做低频连接检查。服务端需要实现返回 2xx 的健康检查接口；如果返回 404，应用会明确显示“服务端尚未实现 /healthz 健康检查接口”，不会伪造连接成功。首页的“最近成功上报”仅在数据上报收到 2xx 响应后更新，与健康检查状态相互独立。

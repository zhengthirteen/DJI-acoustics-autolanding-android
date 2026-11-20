# DJI Acoustics Autolanding Android

第一阶段是**人工起飞和定位 → 执行模拟方向队列 → 队列结束后悬停 → 人工决定是否降落**。现场不要求 GPS，但飞控必须能使用 GPS 或下视视觉定位稳定飞行。应用不读取相机图像；飞控自身的视觉定位仍可能必需。

从安装开发工具到手机操作，请先阅读 **[完整新手操作手册](docs/operation-manual.md)**。实验顺序与验收表见 [实验步骤](docs/experiment-guide.md)，审查结果见 [安全审查与验证范围](docs/safety-review.md)。

## VoiceSep 只读联调（v1.4）

VoiceSep 已独立部署到同级 `VoiceSep/`，原仓库源码保持干净。新增桥接集中在 `scripts/voicesep/`，只调用原 `GET /api/state`；Mac 采集/测角页保持回环监听，手机通过局域网只读网关接收角度。Android 新增“阵列实时角度”独立页面，无需连接无人机，支持有效性、新鲜度、去重与自动跟随 `configured_update_ms`。

默认128ms来自算法参数，不在手机写死。已有100ms回放和500ms旧队列保留。完整部署、接口位置、接线与无无人机/无阵列测试方法见 **[Mac与手机联调指南](docs/voicesep-mac-integration.md)**。由于尚无采集端同步时间戳，本轮网络结果只用于观察，没有直接启用实时飞控。

## 阵列联调准备（v1.3）

原版已保存为 [v1.2-pre-array-20261119](https://github.com/zhengthirteen/DJI-acoustics-autolanding-android/tree/v1.2-pre-array-20261119)，对应 `6c510d8`。本版保留已验证的 DJI 连接/遥测/飞控适配器、SDK 和原有 0.5 秒队列，新增 **0.1 秒/项、任意角度的阵列回放**。支持 1° 滚轮、文本小数角、零点朝向与顺逆时针校准。

真实阵列的数据格式与接入方式待确定；当前 UI 是本机回放，已预留 `LiveBearingInput` 与控制器接口。具体设置、明日对接清单、过期/断流策略见 **[阵列联调准备指南](docs/array-integration-guide.md)**。阵列与目标水平原点不同，需要另加位置估计器，不能仅把阵列角度当作目标偏离角。

## 当前操作流程

1. 默认仍是原有方向队列：通过选项选择实际移动方向和时长，添加、保存。原 `WAIT*20` 仍是每项 0.5 秒、共 10 秒。也可主动切换“阵列角度回放”，默认 `WAIT*100`、每项 0.1 秒、同样 10 秒。默认速度 `0.1 m/s`，实机模式默认关闭。
2. 操作员手动起飞、飞到目标上方并稳定悬停。实机模式只观察遥测，点击启动并再次确认后才申请控制权。
3. 按队列发送水平速度，竖直速度和偏航角速度均为零。没有“确认到达即自动降落”的功能。
4. 队列结束进入 `HOVERING`，持续发送零速度。只有稳定悬停至少 2 秒后，“人工申请降落…”才可用；必须另行点击并确认。
5. 顶部红色“紧急制动”立即终止本次队列、取消本次程序发起的降落并释放控制权；队列不会续播。遥控器负责最终接管。

偏离角沿用“目标 → 无人机”的地理方位约定，北 0°、顺时针；软件反向修正。因此 `180` 产生向北速度，`0` 产生向南速度，`WAIT` 为零速度。详见 [方向约定](docs/direction-input.md)。模拟方向是开环输入，不会随真实位置变化，也不能判断到达。

## 地面通信检查

点击“检查/刷新通信”，无需起飞即可分别查看 SDK 注册、设备/飞控连接、逐项遥测及只读硬件回包，并可复制诊断。修复旧版数据已到达仍显示“等待遥测”的文案，以异步硬件读取补充订阅；不会把缓存伪装成新数据或放宽失联保护。详见 [遥测与连接排查指南](docs/connection-troubleshooting.md)。

## 控制保护

- 独立控制线程，10 Hz 输出；界面心跳超过 350 ms、控制循环间隔超过 350 ms 或遥测超过 750 ms 时终止。停止锁存，迟到的心跳不能恢复会话。
- 默认速度 0.1 m/s，范围 0.05–0.3；旧队列最多 120 项、阵列回放最多 600 项，均最多 60 秒，预计水平指令路程不超过 3 m。
- 启动要求手动起飞、有效定位、允许的飞行模式、电量至少 30%、连续稳定悬停 2 秒。
- 运行时限制相对起飞点高度 2–8 m、相对启动高度变化不超过 0.5 m；实际水平速度超过 1 m/s、垂直速度绝对值超过 0.5 m/s、电量低于 20% 时停止。
- 离开前台、失联、控制权/高级模式丢失、SDK 交接失败都停止；交接未确认时禁止重新启动。
- 无距离反馈，指令路程不是实测距离。软件零速度不保证没有惯性/漂移；Mini 4 Pro 的虚拟摇杆避障不能作为本实验的保障。

代码与模拟检查不能保证实机绝对安全。首次实飞需先验证遥控器接管、零速度悬停和短距离方向响应；不要直接开展长序列或遮挡传感器的试验。

## 开发与构建

Android Studio 打开 **`android-sdk-v5-as/`**；应用模块是 **`:sample`**。环境：JDK 17、Android SDK Platform 35、Gradle Wrapper 8.12、AGP 8.5.0、Kotlin 2.1.0、DJI MSDK 5.17.0。当前 APK 仅含 `arm64-v8a`，最低 Android 7.0/API 24；具体手机/固件兼容性需实测。

```bash
cd android-sdk-v5-as
# 新电脑且文件不存在时才复制；已有本机配置不要覆盖
cp -n secrets.properties.example secrets.properties
# 按 docs/api-key-setup.md 填写匹配的 APPLICATION_ID 和 AIRCRAFT_API_KEY
./gradlew :sample:assembleDebug
```

APK：`android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk`。版本为 1.4 / versionCode 5。原 v1.2 安装包本机备份在忽略的 `artifacts/baselines/v1.2-pre-array-20261119/`。

Key 保存在 Git 忽略的 `android-sdk-v5-as/secrets.properties`。仓库默认包名 `com.zheng13.acousticslanding`；本机按用户要求复用旧 Key，实际包名为 `com.zheng13.gradproject`。同一包名不能与原视觉 App 并存；签名不同会拒绝覆盖，处理方式见 [API Key 指南](docs/api-key-setup.md)。原毕设源目录独立保留。

在仓库根目录运行纯 JVM 检查：

```bash
./scripts/test_acoustics.sh
```

当前通过原 37 组 JVM 场景、新 18 组阵列输入场景，以及 30 组适配器主机替身场景。新增覆盖 100 ms 回放、调度抖动、任意角度标定、最新帧缓冲、断流/迟到锁存与人工结束输入。Android 编译和主机测试不等于实机验证；用户已确认原版连接/移动/降落正常，本次新增阵列功能尚待设备复测。

v1.4 另通过11组只读监视场景、5组网关测试和两种周期的合成音频全链路测试；详见联调指南的验证范围。

## 目录与来源

- `android-sdk-v5-as/`：Gradle 入口、本机配置。
- `android-sdk-v5-sample/`：Android 应用；新的控制逻辑位于 `dji.sampleV5.aircraft.acoustics`。
- `acoustics/core/`：不依赖 Android/DJI 的队列、控制器、会话看门狗。
- `android-sdk-v5-uxsdk/`：继承的 DJI UXSDK。
- `docs/`：新手手册、设计、输入约定、审查与实验说明。
- `examples/directions/`：方向队列示例；`tests/`：JVM 回归场景。

基于原视觉仓库 `49578d1e3b2eb7af8b63a7565aed83610dd04e8c` 快照独立初始化 Git，移除原视觉降落/YOLO/OpenCV。声学界面仅提供只读 SDK 状态，没有进入 DJI 示例飞行控制菜单的按钮。详见 [来源说明](docs/provenance.md) 与 [DJI 许可](LICENSE-DJI.txt)。

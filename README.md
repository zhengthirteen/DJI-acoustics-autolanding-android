# DJI Acoustics Autolanding Android

第一阶段是**人工起飞和定位 → 执行模拟方向队列 → 队列结束后悬停 → 人工决定是否降落**。现场不要求 GPS，但飞控必须能使用 GPS 或下视视觉定位稳定飞行。应用不读取相机图像；飞控自身的视觉定位仍可能必需。

从安装开发工具到手机操作，请先阅读 **[完整新手操作手册](docs/operation-manual.md)**。实验顺序与验收表见 [实验步骤](docs/experiment-guide.md)，审查结果见 [安全审查与验证范围](docs/safety-review.md)。

## 当前流程（v1.1）

1. 打开 App，设置有限方向队列，点击“检查并保存队列”。默认 `WAIT*20`，每项 0.5 秒，共 10 秒；默认速度 `0.1 m/s`，实机模式默认关闭。
2. 操作员手动起飞、飞到目标上方并稳定悬停。实机模式只观察遥测，点击启动并再次确认后才申请控制权。
3. 按队列发送水平速度，竖直速度和偏航角速度均为零。没有“确认到达即自动降落”的功能。
4. 队列结束进入 `HOVERING`，持续发送零速度。只有稳定悬停至少 2 秒后，“人工申请降落…”才可用；必须另行点击并确认。
5. 顶部红色“紧急制动”立即终止本次队列、取消本次程序发起的降落并释放控制权；队列不会续播。遥控器负责最终接管。

偏离角沿用“目标 → 无人机”的地理方位约定，北 0°、顺时针；软件反向修正。因此 `180` 产生向北速度，`0` 产生向南速度，`WAIT` 为零速度。详见 [方向约定](docs/direction-input.md)。模拟方向是开环输入，不会随真实位置变化，也不能判断到达。

## 控制保护

- 独立控制线程，10 Hz 输出；界面心跳超过 350 ms、控制循环间隔超过 350 ms 或遥测超过 750 ms 时终止。停止锁存，迟到的心跳不能恢复会话。
- 默认速度 0.1 m/s，范围 0.05–0.3；最多 120 项/60 秒，预计水平指令路程不超过 3 m。
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

APK：`android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk`。版本为 1.1 / versionCode 2。

Key 保存在 Git 忽略的 `android-sdk-v5-as/secrets.properties`。仓库默认包名 `com.zheng13.acousticslanding`；本机按用户要求复用旧 Key，实际包名为 `com.zheng13.gradproject`。同一包名不能与原视觉 App 并存；签名不同会拒绝覆盖，处理方式见 [API Key 指南](docs/api-key-setup.md)。原毕设源目录独立保留。

在仓库根目录运行纯 JVM 检查：

```bash
./scripts/test_acoustics.sh
```

控制场景覆盖队列结束悬停、显式降落、紧急中止、方向反转、全部 360 个整数方位、机头变换、过期遥测、定位/飞行模式/电量/高度/速度异常及会话心跳锁存。Android 编译和 JVM 模拟不等于手机、DJI 仿真器或实机验证；本次尚未连接飞机进行实飞。

## 目录与来源

- `android-sdk-v5-as/`：Gradle 入口、本机配置。
- `android-sdk-v5-sample/`：Android 应用；新的控制逻辑位于 `dji.sampleV5.aircraft.acoustics`。
- `acoustics/core/`：不依赖 Android/DJI 的队列、控制器、会话看门狗。
- `android-sdk-v5-uxsdk/`：继承的 DJI UXSDK。
- `docs/`：新手手册、设计、输入约定、审查与实验说明。
- `examples/directions/`：方向队列示例；`tests/`：JVM 回归场景。

基于原视觉仓库 `49578d1e3b2eb7af8b63a7565aed83610dd04e8c` 快照独立初始化 Git，移除原视觉降落/YOLO/OpenCV。声学界面仅提供只读 SDK 状态，没有进入 DJI 示例飞行控制菜单的按钮。详见 [来源说明](docs/provenance.md) 与 [DJI 许可](LICENSE-DJI.txt)。

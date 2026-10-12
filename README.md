# DJI Acoustics Autolanding Android

基于 DJI Mobile SDK V5 的声学方向引导降落 Android 项目。第一阶段现场**无 GPS，由操作员先把无人机飞到预定降落点上方**，再使用可编辑的模拟偏离角队列，以 500 ms 周期测试 Android 处理和飞行控制链路。到达由操作员明确确认，之后悬停 2 秒，再交给 DJI 自动降落。

本仓库独立于原毕设目录。代码基于 [mini4pro-vision-autolanding-android](https://github.com/zhengthirteen/mini4pro-vision-autolanding-android) 的 `49578d1e3b2eb7af8b63a7565aed83610dd04e8c` 快照迁移，重新建立 Git 历史；保留 DJI Sample/UXSDK 多模块工程，移除视觉降落、YOLO 模型、OpenCV 模块及旧示例 APK。声学入口不读取相机帧、不调用视觉推理。DJI 原生诊断页面仍保留供注册和连接排查。

## 已实现的第一阶段

- 编辑方向队列：`180*40`、`180,170,160,WAIT,90`；支持负角、超过一周的角度和重复次数。
- 2 Hz 输入方向、10 Hz 发送控制指令；圆周角滤波、定速修正、保持高度和航向。
- 默认本地模拟：无人机已位于目标上方、高度 4 m，队列 `WAIT*120` 输出悬停，显示状态，不调用飞行控制接口。
- 默认采用人工到达判据，无需飞机 GPS 或目标经纬度；点击到达确认前不会自行开始降落。实机控制默认关闭。
- 自定义角度用于水平控制响应测试；另保留位置判据模拟/GPS 演示，需主动切换。
- 到达后悬停确认 2 秒，再依次释放 Virtual Stick、请求 DJI 自动降落；请求需回调成功，实际落地依据飞行状态判断。
- 输入或遥测过期、控制循环延迟、队列结束、控制权丢失、超时、页面离开前台时停止；提供取消降落和最终降落确认按钮。
- 实验 CSV 日志、纯 JVM 回归测试，以及 GitHub Actions 测试工作流。

第一阶段验证控制链路，尚未完成实飞验证，也未实现纯声学定位。方位角只能提供方向，不能单独证明到达。若阵列与降落点不重合，还需要解决不同原点之间的几何转换。当前队列约定的是“降落点 → 无人机”的偏离角；现场到达由操作员判断。见 [第一阶段设计](docs/stage1-design.md) 与 [方向输入约定](docs/direction-input.md)。

## 开发环境与构建

- Android Studio、JDK 17、Android SDK 35；最低 Android 7.0/API 24。
- Gradle Wrapper 8.12、Android Gradle Plugin 8.5.0、Kotlin 2.1.0、DJI MSDK 5.17.0。
- 当前 APK 仅打包 `arm64-v8a`，面向 DJI Mini 4 Pro + RC-N2 + Android 手机；具体功能可用性需实机联调。

Android Studio **打开 `android-sdk-v5-as/`**，不是仓库根目录。

```bash
cd android-sdk-v5-as
cp secrets.properties.example secrets.properties
# 编辑 secrets.properties；为 com.zheng13.acousticslanding 配置自己的 DJI API Key
# Android Studio 会生成本机 local.properties；命令行也可通过 ANDROID_HOME 指向 SDK
./gradlew :sample:assembleDebug
```

纯模拟不要求 DJI 注册成功。连接实机前必须配置新包名对应的 DJI API Key。新包名为 `com.zheng13.acousticslanding`，可与原视觉应用并存。`secrets.properties` 和 `local.properties` 已忽略；没有继续使用原仓库中的 API Key 和签名密码。Debug 使用自动生成的 Android debug keystore，Release 签名可在本地配置。

APK 输出：`android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk`。DJI SDK 使 APK 较大，编译结果不纳入 Git。

## 先运行模拟

1. 安装 APK，打开“声学自主降落”，保持“实机发送”未勾选。
2. 保持默认队列 `WAIT*120` 与“人工确认”，点“启动模拟实验”。这串队列可运行约 60 秒，没有水平移动指令。
3. 点击“确认已到目标正上方”：悬停 2 秒后开始模拟降落，最终进入 `COMPLETE`。
4. 不点击到达确认，队列结束后停止；不会因队列结束或角度为零而降落。
5. 测试水平响应时编辑角度队列；它会让飞机移动，实机降落前须重新人工确认位置。

若要演示位置收敛，主动选“位置判据”、队列 `180*40`、速度 `0.3 m/s`、半径 `0.8 m`。该附加模拟从目标南侧 4 m 出发，约 12.7 秒申请降落。它不是现场无 GPS 实验的默认流程。

`180°` 表示无人机位于目标南方，因此修正方向是北方。它不是“向南飞”的指令。完整实机操作见 [实验步骤](docs/experiment-guide.md)。

## 测试与当前验证范围

```bash
# 仓库根目录；只需 JDK，无需 Android SDK 或无人机
./scripts/test_acoustics.sh
```

本机已通过 15 组控制场景，包括默认无 GPS/缺少定位的 WAIT 悬停与人工确认、完整位置模拟接近、反向修正、航向变换、圆周角跨界、输入过期、失联、定位无效、到达计时重置、控制循环阻塞和指令路程限制。已通过 `:sample:assembleDebug` 构建。尚未在 Android 设备、DJI 仿真器或实机上验证；简单运动积分不代表真实降落精度。

## 项目结构

```text
android-sdk-v5-as/             Android Studio / Gradle 入口
android-sdk-v5-sample/         Android 主应用与 DJI 示例
  src/main/java/dji/sampleV5/aircraft/acoustics/
    AcousticLandingActivity.kt     参数、2 Hz 输入、10 Hz 循环、模拟、轨迹、日志
    DjiFlightBridge.kt             DJI 遥测、Virtual Stick、降落回调与取消
    core/                         不依赖 Android/DJI 的队列和控制状态机
android-sdk-v5-uxsdk/          继承的 DJI UXSDK
docs/                         设计、方向约定、实验和来源说明
examples/directions/          可粘贴到界面的示例队列
tests/                        纯 JVM 控制回归场景
scripts/test_acoustics.sh      本地测试入口
```

后续接入阵列时，需要明确阵列朝向、角度原点、通信方式、数据时间戳/序号，以及阵列不在目标点时的几何转换和到达判据。第一阶段不实现阵列信号处理、通信接收或距离估计。

## Git 与来源

`origin` 指向 [新的声学仓库](https://github.com/zhengthirteen/DJI-acoustics-autolanding-android)，`vision-upstream` 仅记录原视觉仓库地址。迁移没有复制原目录的未提交修改。详细来源和第三方许可见 [来源说明](docs/provenance.md) 与 [DJI 上游许可](LICENSE-DJI.txt)。

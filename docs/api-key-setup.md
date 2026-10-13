# DJI API Key 查找、复用与配置

DJI Mobile SDK 的 App Key 与开发者中心登记的 Android `Package Name` 对应。修改视觉/声学算法或新建 Git 仓库不要求重新申请 Key；实际 APK 的 `applicationId` 改变时，就需要使用新包名对应的 Key。这里的包名是 Android 安装身份，不是源码中的 `namespace` 或 Kotlin/Java 的 `package`。

依据：[DJI 官方运行示例指南](https://developer.dji.com/doc/mobile-sdk-tutorial/cn/quick-start/run-sample.html)。

## 本项目的两种配置

| 方式 | `APPLICATION_ID` | `AIRCRAFT_API_KEY` | 与原视觉 App 同时安装 |
| --- | --- | --- | --- |
| 复用旧 Key | `com.zheng13.gradproject` | 原视觉项目对应的 Key | 同一 Android 用户空间不能并存 |
| 独立安装身份 | `com.zheng13.acousticslanding` | 为新包名申请的 Key | 可以 |

仓库代码默认采用新包名；本机可用 `android-sdk-v5-as/secrets.properties` 中的 `APPLICATION_ID` 覆盖。该文件只在本机生效，Git 忽略它。本次根据用户要求已在本机配置原包名与原 Key，未复制原签名凭据。

## 查找原 Key

1. 用编辑器打开原项目的 `/Users/zheng13/Study/GradProj/mini4pro-vision-autolanding-android/android-sdk-v5-as/gradle.properties`。
2. 找到 `AIRCRAFT_API_KEY`；等号后面的值就是原项目填写的 DJI App Key。不要修改原文件，也不要将真实值复制到文档、截图或提交记录。
3. 登录 [DJI 开发者中心](https://developer.dji.com/user/apps/)，进入“应用 / Apps”，找到原 Android Mobile SDK 应用并查看详情。
4. 核对 `Package Name` 是否为 `com.zheng13.gradproject`，以及 `App Key` 是否与原文件相同。原代码可以证明当时填了什么；Key 目前是否仍有效，需要开发者中心与手机 SDK 注册验证。

## 复用旧 Key

本次本机已完成这一步；无需再复制示例文件覆盖配置。换电脑或重新克隆后，才需要手动重建本机配置。

1. Android Studio 打开本仓库的 `android-sdk-v5-as/`。
2. 若 `secrets.properties` 尚不存在，将同目录 `secrets.properties.example` 复制并重命名为 `secrets.properties`。
3. 设置以下两个属性，将占位文字替换为实际 Key。值不加引号：

   ```properties
   APPLICATION_ID=com.zheng13.gradproject
   AIRCRAFT_API_KEY=这里填写原项目的实际Key
   ```

4. `GMAP_API_KEY`、`MAPLIBRE_TOKEN` 可以留空；当前声学控制链路不需要地图服务的 Key。
5. 进行 Gradle Sync，然后重新构建和安装。只修改本机文件不会改变手机上已安装的旧 APK。

无需修改源码包目录、`namespace` 或手工修改 Manifest。`:sample` 的 `build.gradle` 会读取本机属性，将 Key 注入 Manifest 的 `com.dji.sdk.API_KEY`。

## 使用新包名并申请新 Key

如果需要在手机上保留原视觉 App，采用这一方式：

1. 登录 [DJI 开发者中心](https://developer.dji.com/user/apps/)。
2. 进入“应用 / Apps”，点击“创建应用 / CREATE APP”。
3. `App Type` 选择 **Mobile SDK**，`Software Platform` 选择 **Android**。
4. `App Name` 可填写 `DJI Acoustics Autolanding`；`Package Name` 必须填写 **`com.zheng13.acousticslanding`**。分类和描述按实际用途填写。
5. 创建应用后按页面提示完成激活；若收到激活邮件，打开其中的激活链接。返回应用详情，复制生成的 **App Key**。
6. 修改本仓库 `android-sdk-v5-as/secrets.properties`：

   ```properties
   APPLICATION_ID=com.zheng13.acousticslanding
   AIRCRAFT_API_KEY=这里填写为新包名申请的实际Key
   ```

7. Gradle Sync，重新构建并安装。不要在新包名下继续填旧包名的 Key。

DJI 官方示例将 Key 放在 `gradle.properties`；本项目改为从忽略的 `secrets.properties` 读取。因此请填写本项目的 `secrets.properties`，而不是直接照搬官方示例的文件位置。

## 构建与安装

在终端执行：

```bash
cd /Users/zheng13/Study/project/DJI-acoustics-autolanding-android/android-sdk-v5-as
./gradlew :sample:assembleDebug
```

APK 输出到：

```text
/Users/zheng13/Study/project/DJI-acoustics-autolanding-android/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk
```

本机现在使用旧包名。若手机已安装原视觉 App，Android 会把本次 APK 视为同一个应用的安装/更新。原项目 Debug 配置使用自定义签名，本项目 Debug 使用本机默认签名；如果手机原 App 的签名与本次 APK 不一致，会拒绝覆盖安装。这个问题来自 Android 安装校验，与 DJI Key 是否复用是两件事。依据：[Android 官方应用签名说明](https://developer.android.com/studio/publish/app-signing)。

遇到签名冲突时，可选择上面的新包名/新 Key 方式，以保留原 App。若选择替换原 App，需要先自行备份原 App 数据与实验日志，再卸载原 App 后安装本次 APK；卸载会删除其应用数据。不能把“不能并存”理解为两个代码文件夹会互相覆盖，原毕设源文件仍保留在原目录。

## 验证配置是否成功

1. 首次注册时让手机联网，打开“声学自主降落”，保持“实机发送”关闭。
2. 等待状态面板出现 **`SDK：已注册`**；必要时进入 **“DJI 注册与连接诊断”** 查看注册错误。SDK 注册过程中需要连接 DJI 服务验证 App Key，见 [SDKManager 官方 API](https://developer.dji.com/api-reference-v5/android-api/Components/SDKManager/DJISDKManager.html)。
3. 再连接遥控器与无人机，检查诊断页面的产品连接状态与实际产品名称。注册成功与飞机连接成功分别检查；这两步无需起飞。
4. 若出现 `INVALID_METADATA`，先检查开发者中心登记的 `Package Name` 与 APK 实际包名是否一致，再检查 Key 是否复制完整、手机联网是否正常。该报错可能与包名不匹配有关，不能只凭错误码认定原因。
5. 注册与连接都成功后，按 [第一阶段实验步骤](experiment-guide.md) 先进行本地模拟与遥测检查，再开展受控飞行测试。

编译通过只能证明构建配置有效。Key 已写入 APK不等于 DJI 服务已接受注册，也不等于实机飞行已经验证。

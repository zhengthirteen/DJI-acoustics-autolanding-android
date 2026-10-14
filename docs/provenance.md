# 项目来源与仓库迁移

- 原毕设仓库：[zhengthirteen/mini4pro-vision-autolanding-android](https://github.com/zhengthirteen/mini4pro-vision-autolanding-android)。
- 克隆基准：`49578d1e3b2eb7af8b63a7565aed83610dd04e8c`，分支 `main`。
- 新仓库：[zhengthirteen/DJI-acoustics-autolanding-android](https://github.com/zhengthirteen/DJI-acoustics-autolanding-android)。
- 新工作目录：`/Users/zheng13/Study/project/DJI-acoustics-autolanding-android`。
- 原本地毕设目录保持不变，未导入其未提交的视觉修改和论文。
- 新 `main` 从新的根提交开始，不携带旧提交中的 API Key 或签名配置。
- 按用户后续要求，允许在忽略的本机 `secrets.properties` 配置原 Key 与原包名。实际 Key、签名凭据和 APK 仍不进入仓库；原项目不作修改。
- `origin` 为新仓库；`vision-upstream` 记录旧仓库地址，不跟踪其分支，也不向其推送。

迁移保留 DJI Sample 和 UXSDK，移除旧 `graduation` 视觉降落包、三个旧 Activity 布局、YOLO ONNX 模型、OpenCV 工程和预打包 APK，替换为 `acoustics` 控制链路及新 launcher。DJI 通用示例中的相机/图传诊断工具仍保留。

原项目基于 [DJI Mobile-SDK-Android-V5](https://github.com/dji-sdk/Mobile-SDK-Android-V5) 的 SampleCode-V5。继承文件保留各自版权声明。`LICENSE-DJI.txt` 来自 [DJI 官方许可文件](https://github.com/dji-sdk/Mobile-SDK-Android-V5/blob/dev-sdk-main/LICENSE.txt)，区分 Sample 的 MIT 许可与二进制 SDK 的 EULA；没有重新授权 DJI SDK 或其他第三方依赖。

`scripts/data_analysis/` 是原视觉项目继承的分析工具，当前声学 CSV 格式不同，不能直接假定旧脚本兼容。v1.1 移除了该目录中随快照继承的旧视觉实验 CSV，并忽略后续生成的 `FlightData_*.csv`。声学界面仅提供只读状态查看，不再进入 DJI 示例控制菜单。

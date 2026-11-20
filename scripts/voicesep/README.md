# VoiceSep 独立只读桥接

本目录只使用同级 VoiceSep 的现有接口，不修改其源码或写入设备配置。完整连接、部署和无无人机测试步骤见 [操作指南](../../docs/voicesep-mac-integration.md)。

```bash
# 在 Android 项目根目录执行
python3 scripts/voicesep/stack.py start
python3 scripts/voicesep/stack.py status
# 原服务单独重启后，只重新加载本机凭据
python3 scripts/voicesep/stack.py load-credentials
# 实验结束并在原页面停止采集/分析后
python3 scripts/voicesep/stack.py stop
```

Mac服务为8012采集、8011测角、8014只读网关。只读网关的上游固定为本机HTTP源，手机只获得角度元数据，没有录音、启动/停止或改参数的接口。

```bash
# 完整合成音频链路，无真实阵列/无人机；手机访问 MacIP:18014
python3 -B scripts/voicesep/audio_demo.py --seconds 120 --angle 45 --hop 256 --step 8

# 本目录自身的离线检查
python3 -B scripts/voicesep/test_gateway.py
python3 -B scripts/voicesep/test_mac_compat.py

# 原SMD测试，Mac只隔离Windows测试夹具的os.name修改，不改源文件/断言
python3 -B scripts/voicesep/verify_upstream.py
```

原VoiceSep目录默认是 `../VoiceSep`，可用 `--voicesep-root` 指定其他位置。它的 `.venv` 需按原 requirements.txt 安装。Mac校准播放的 `winsound` 兼容模块只通过启动器注入SMD子进程；不会安装进系统Python。原Windows文件管理器功能在Mac请使用复制路径。

设备凭据可配置在忽略的 `android-sdk-v5-as/secrets.properties` 中，使用 `SMD_SSH_HOST`、`SMD_SSH_USERNAME`、`SMD_SSH_PASSWORD`，权限须为600。启动脚本通过原 `/api/credentials` 加载到SMD内存；密码框留空即可操作，不勾选Windows加密保存。网关不会读取或转发凭据，也不因加载凭据自动开始采样。逐步操作及通过标准见 [测试手册](../../docs/testing-runbook.md)。

数据、日志、PID和临时文件不进入Git。`audio_demo.py` 使用自己的临时端口/运行目录，结束时只关闭自己创建的测试进程。自动重连仅用于只读显示，不会恢复飞行控制。

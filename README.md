# 43K2 LiteHome

源码预览版 v0.1.0-source-preview。LiteHome 是轻量 Android 桌面，支持遥控方向键导航、应用排序、隐藏与恢复，并可为符合条件的应用打开系统卸载确认。主页面按返回会留在桌面，菜单内按返回会关闭菜单。

原适配对象为酷开 43K2 / 5S21_K2 / sky638_5s21 / Android 5.1.1（SDK 22）。公开源码可构建普通 Android 桌面应用，提供应用入口和标准 HOME Activity。原机的实体主页键和自动开机进桌面由酷开固件的 OEM 路径和私有部署配置共同实现。仅构建或安装公开 LiteHome 无法复现这条接管链，也无法取得 OEM 签名或权限。公开源码包包含 Java 源码、Windows 构建脚本和离线补丁工具。 [架构与验收范围](docs/architecture.md)

## 构建与签名

准备 Windows PowerShell 5.1、JDK 17、Android SDK 平台 android-36 和 Build Tools 35.0.0。从项目根目录运行以下命令。

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\lite-home\Build.ps1 -JavaHome "<JDK17目录>" -AndroidSdk "<Android SDK目录>" -OutputApk "$PWD\coocaa-lite-home-test.apk"

脚本使用干净的临时构建目录，生成并验签成功后才写入目标 APK。输出目录须事先存在。默认使用构建目录内的本地试用密钥，-KeyStorePath 可复用既有的兼容试用密钥。同一试用安装的更新需要相同签名。正式分发需单独准备并安全保管发布密钥，建立独立签名流程。试用签名、正式发布签名和 OEM 签名具有各自的身份，公开构建也不持有私有定制版 LiteHome 的签名。上述工具链可重复执行构建流程，签名密钥与生成时间会影响产物字节。

## 设备试用边界

**已安装私有定制版 LiteHome 的电视不得覆盖安装公开构建。遇到同包签名冲突必须停止，禁止通过卸载、移除或停用原有系统组件来解决冲突。** 私有定制版可能同时具备系统底座与 OEM 主页映射，移除它会影响启动和恢复路径。

如需验证公开 APK，请另选具有回退方式的独立测试设备，先确认没有任何同包安装或系统底座。可用 Platform-Tools 检查测试设备的包登记，将下方 DEVICE_SERIAL 替换为测试设备串号。

    adb -s DEVICE_SERIAL shell pm list packages -u local.coocaa.litehome

如查询报错、发现已有登记，或无法确定启动和恢复状态，停在预检阶段。仅在确认独立测试设备的状态后，才可按普通用户应用方式试装并手动启动。

    adb -s DEVICE_SERIAL install .\coocaa-lite-home-test.apk
    adb -s DEVICE_SERIAL shell am start -n local.coocaa.litehome/.LiteHomeActivity

回退时，先切回原桌面、清除试用版默认桌面设置，再通过系统应用管理处理自己安装的试用 APK。这条回退路径仍待独立设备实测。

## 输入源兼容性

输入源快捷入口依赖酷开固件的专用广播与 TV Activity。当前实现只在广播调用抛出异常时尝试备用 Activity。若固件静默忽略广播，公开 LiteHome 无法确认投递结果，备用入口也不会触发。快捷键无响应时使用遥控器原厂输入源键。设置、媒体和投屏快捷入口也需要目标机型提供相应组件。

## 离线镜像工具

tools/OfflineImage.ps1 可用维护者自己的镜像和补丁规格执行 Build 或 Undo。工具检查整个文件的 SHA256 并拒绝覆盖已有输出。补丁规格包含格式号 1、设备代号 sky638_5s21、镜像长度、原始与结果哈希，以及由 offset、before、after 组成的不重叠区间。before 和 after 是等长 Base64。

**Undo 只在电脑上撤销已知的离线文件字节修改。** 它生成一份新的校验输出文件。电视刷机回滚和救砖需要独立的设备恢复流程，不能使用 Undo 完成。 [离线工具说明](docs/offline-image.md)

## 验证状态

| 范围 | 项目 | 状态 |
| --- | --- | --- |
| 私人定制组合 | 开机进入极简桌面、实体 Home 返回、无原带声开机视频广告、乐播 iPhone 镜像 | 已通过 |
| 私人定制组合 | 断电冷启动 | 已通过，依据维护者补充的实机结果 |
| 高清视频与 HDMI | 实际高清硬件解码、HDMI 实际信号 | 未验证 |
| 公开 APK | 真机安装、卸载和系统接管 | 未验证 |

原机的短 H.264/AAC 样片播放与输入源菜单有独立记录，分别不足以证明高清硬解及 HDMI 实信号。公开源码和文档采用 MIT 许可。 [版本说明](RELEASE-NOTES.md) · [许可](LICENSE)

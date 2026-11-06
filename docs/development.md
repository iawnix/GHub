# 开发指南

## 构建环境

| 依赖 | 版本 |
|---|---|
| JDK | 21 |
| Android SDK Platform | 36 |
| Android SDK Build Tools | 35.0.0 |
| 最低运行版本 | Android 10 / API 29 |

项目使用 Gradle Wrapper 管理 Gradle 版本。Android Studio 可直接打开仓库根目录。

命令行构建时配置 JDK 和 SDK 路径：

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

也可以在本机 `local.properties` 中设置 `sdk.dir=/path/to/android-sdk`。

## 构建与安装

开发时建议使用独立包名：

```sh
./gradlew -PpreviewApplicationId=dev.remotegpt.preview :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

该构建显示为 **GHub Preview**，可与正式版共存。省略 `previewApplicationId` 时，包名为 `dev.remotegpt.app`；同一包名的 Debug 与正式版签名不同，开发时使用独立包名可保留正式版的数据。

Release 构建与签名见[发布指南](releasing.md)，日常检查见[测试指南](verification.md)。

## 项目结构

| 目录 | 职责 |
|---|---|
| `app/` | Compose 界面、ViewModel、Room 缓存、凭据存储、文件选择与语音等系统集成 |
| `codex-client/` | 纯 JVM 的 SSH、App Server 协议、会话同步和 SFTP |
| `terminal/` | Termux 终端模拟器、渲染与 SSH PTY 适配 |
| `scripts/` | 协议探针、隔离测试环境、设备测试与图标生成 |
| `docs/` | 使用、设计、协议、测试和发布文档 |
| `licenses/` | 随应用分发的第三方许可证 |

协议行为从 [CodexApi](../codex-client/src/main/kotlin/dev/remotegpt/client/CodexApi.kt) 和[协议文档](protocol.md)开始阅读；界面入口为 [Workbench](../app/src/main/java/dev/remotegpt/app/ui/Workbench.kt)。连接与资源生命周期主要由 [WorkbenchViewModel](../app/src/main/java/dev/remotegpt/app/WorkbenchViewModel.kt) 管理。

## 离线界面预览

Debug 构建中的 `DesignPreviewActivity` 使用实际组件和示例数据展示界面。可在 Android Studio 打开 [DesignPreviewActivity.kt](../app/src/debug/java/dev/remotegpt/app/DesignPreviewActivity.kt) 查看 Compose Preview，也可在模拟器中启动：

```sh
adb shell am force-stop dev.remotegpt.preview
adb shell am start -n dev.remotegpt.preview/dev.remotegpt.app.DesignPreviewActivity \
  --es theme light --es screen code
```

切换场景时先结束预览进程，再使用新的参数启动。`theme` 支持 `light` 和 `dark`。

| `screen` | 内容 |
|---|---|
| `projects`、`sessions` | 项目与会话列表 |
| `chat`、`reference` | 聊天和完整输入布局 |
| `workspace`、`approval` | 工作区操作和审批 |
| `status`、`markdown`、`math`、`code` | 状态、Markdown、数学公式、命令与代码阅读 |
| `picker-model`、`picker-effort` | 模型和思考级别选择 |
| `picker-hosts`、`picker-projects` | 服务器和项目选择 |
| `terminal`、`terminal-multi` | 终端和多会话切换 |

预览入口仅打包到 Debug APK，数据来自本地示例。README 中的截图使用这些场景生成。

## 修改界面和资源

组件、颜色与交互规则见[界面设计](ui-design.md)。新增文字同步维护中英文字符串资源；图标来源与生成方式见 [Lucide 记录](../app/design/lucide/UPSTREAM.md)，应用图标见[品牌资源](../app/design/brand/README.md)。

`dist/` 用于本地 APK、截图和验证报告，`.remote-gpt/` 用于本机开发资料；两个目录均由 Git 忽略。公开文档中的图片放在 `docs/images/`。

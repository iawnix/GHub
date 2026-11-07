# 测试指南

GHub 使用 JVM 单元测试、Android 设备测试和隔离 SSH / Codex 集成测试。常规检查与正式发布复用同一套 [GitHub Actions 工作流](../.github/workflows/check.yml)。

## 本地常规检查

先按[开发指南](development.md)配置 JDK 与 Android SDK：

```sh
./gradlew :codex-client:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

在已启动的模拟器上运行界面测试：

```sh
./gradlew -PpreviewApplicationId=dev.remotegpt.preview :app:connectedDebugAndroidTest
```

需要 SSH fixture 参数的用例在常规测试中跳过，按下文运行对应集成流程。报告位于各模块的 `build/reports/`，设备结果位于 `app/build/outputs/androidTest-results/`。

## SSH 与 App Server 集成

Linux 测试主机需要 Python 3、OpenSSH 客户端和服务端，以及兼容版本的 Codex CLI。处理压缩模型请求时需要 Python `zstandard` 包。

```sh
python3 scripts/probe.py                  # 检查当前 daemon 的握手
python3 scripts/probe.py --exercise       # 独立 daemon：审批、接续和断线恢复
python3 scripts/question_probe.py         # 独立 daemon：提问、回答、重连和多端同步
python3 scripts/ssh_fixture.py            # 经两级 SSH 跳板运行协议流程
python3 scripts/ssh_fixture.py --jvm      # Kotlin SSH、SFTP、PTY 集成测试
python3 scripts/test_fixture_safety.py    # 测试环境与清理选择器检查
```

集成脚本创建临时 SSH 密钥、高端口服务和独立 Codex 数据目录，并用本地 Responses fixture 提供确定性模型响应。测试结束后清理临时服务和目录。只读的 `probe.py` 使用当前 daemon，其余流程由脚本创建测试环境。

`ssh_fixture.py` 同时运行审批与问答探针；问答探针仅在私有 fixture 中启用 `default_mode_request_user_input`，不改动用户配置。`UserQuestionsDeviceTest` 验证明确选择后提交、自由输入、页面恢复、秘密答案不保存和历史问答展示。

## Android 完整工作流

使用可丢弃的 API 35 模拟器；脚本会清空所安装测试包的数据。测试主机还需要 `vim` 和 `tmux`。

```sh
export ADB="$ANDROID_HOME/platform-tools/adb"
export ANDROID_SERIAL=emulator-5556
./gradlew -PpreviewApplicationId=dev.remotegpt.preview \
  :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/device_check.py
```

此流程覆盖 SSH 认证、审批恢复、SFTP 编辑、图片上传、终端输入、Vim / tmux、Activity 重建和后台连接释放。

可通过 `DEVICE_TEST_CLASS` 运行专项：

```sh
DEVICE_TEST_CLASS='dev.remotegpt.app.WorkbenchDeviceTest#workbenchResumesPendingApprovalAndRunsRemoteTerminal' \
  python3 scripts/device_check.py

COMMAND_OUTPUT_FIXTURE=1 \
DEVICE_TEST_CLASS=dev.remotegpt.app.CommandHistoryDeviceTest \
  python3 scripts/device_check.py
```

第二项生成 180 行中文输出，以退出码 7 结束，用于检查失败输出、流式追加和重连历史。

`DEVICE_TEST_CLASS=dev.remotegpt.app.ProjectCreationDeviceTest python3 scripts/device_check.py` 验证添加项目会在服务器创建真实目录、失败时保留表单且不保存虚假入口、重复添加保留已有文件，以及离线重命名。SSH JVM 测试另外覆盖父目录创建、中文/空格/引号路径、文件占用和目录写权限不足。

未打开会话的状态同步另有专项脚本，使用上面构建的 `dev.remotegpt.preview`：

```sh
python3 scripts/status_device_check.py
```

该专项还验证：会话正文淘汰后先从 Room 恢复再接收下一轮；连续约 16 秒、每 25 毫秒一个增量（共 640 个）的输出在手动刷新和后台恢复时不丢失前缀；流式输出尚未完成时已有周期性磁盘快照；完成后缓存保留旧轮次和完整新回答；清空缓存后重连能恢复全部消息。还会检查隐藏会话的界面发布量、可见流式事件与发布次数，以及空闲 20 秒内的状态/发现请求量和 RPC 字节数。

同步单元测试覆盖活动历史省略未完成条目、请求期间完成事件的合并、缓存截断后的连续分页、v2 游标迁移、正文恢复失败和旧连接事件丢弃，以及持续输出、慢写入和清理缓存时的保存调度。

`ConversationStorageDeviceTest` 在独立数据库中验证 Room v2→v3 升级、旧正文迁移、长文本分块缩短后的清理，以及中途写入失败时整笔事务回滚。`StatePublicationTest` 验证界面节流不阻塞关键状态、多会话增量不使可见输出饿死；`SessionPollScheduleTest` 验证退避、请求合并、优先级、并发预算与模拟一分钟的查询数量。

## Release 运行测试

```sh
ANDROID_SERIAL=emulator-5556 python3 scripts/release_device_check.py
```

该脚本构建并安装启用 R8、资源裁剪且 `debuggable=false` 的 Release，在临时 SSH 服务上验证加密密钥、SFTP、PTY 和界面操作。无正式签名环境变量时使用测试签名。可设置 `ORG_GRADLE_PROJECT_previewApplicationId=dev.remotegpt.releasepreview` 使用独立包名。

## CI 覆盖

| 任务 | 环境与内容 |
|---|---|
| `build` | JVM 单元测试、Lint、Debug 和 instrumentation APK 构建 |
| `device` | API 29、35、36 模拟器上的常规设备回归 |
| `release-device` | API 35 上的精简 Release、临时 SSH 与界面检查 |
| `release` | 发布标签触发；前述检查通过后构建签名 APK、验证签名并上传附件 |

[Actions](https://github.com/iawnix/GHub/actions) 提供每次提交的结果和测试报告；[v0.1.40 的完整发布记录](https://github.com/iawnix/GHub/actions/runs/41294841435)包含上述全部任务。

真机体验检查主要关注厂商输入法、TalkBack、相机与语音服务、Wi-Fi / 蜂窝切换、长时间后台和内存表现。提交这类结果时，请记录设备、系统版本、操作步骤与观察时长，便于复现和比较。

`TaskNotificationTrackerTest` 覆盖重连已在线但请求尚未重放、多个会话独立同步、离线处理请求的撤回，以及新轮次清理旧结果通知。`TaskNotificationDeviceTest` 在 API 29/35/36 检查审批动作的解锁要求或打开应用确认、设置页跳转及同一会话重复通知跳转。

`DynamicToolTimelineTest` 与缓存测试覆盖动态工具的实时/历史一致性、结构化返回内容、失败结果和旧缓存兼容。`DynamicToolCallDeviceTest` 验证展开查看、参数与结果复制、媒体地址及旧缓存刷新提示。

`ContextCompactionTimelineTest` 和 `ContextCompactionDeviceTest` 覆盖上下文整理的开始/完成状态、实时与历史一致性、旧缓存显示，以及活动历史刷新时保留已完成状态。

`InterruptedInputTest` 覆盖可编辑范围、输入快照、普通草稿保留和缓存恢复；`InterruptedInputDeviceTest` 检查铅笔入口、复用底部输入框、原消息保留、普通草稿恢复、重新发送和交付不确定时的锁定。

`ConversationRowsTest` 验证一轮中的多个助手消息按顺序分别进入列表，后续消息出现时前文不会被重新归入执行摘要，并且回复操作只绑定到最后一条完整助手消息。Markdown 流式界面测试同时覆盖块解析和稳定复用；会话列表直接按助手块测量，长回复不会再作为一个轮次条目整体组合。

停止后修改的真实 SSH 专项使用独立 daemon、本地模型和临时附件：

```sh
ANDROID_SERIAL=emulator-5556 \
DEVICE_TEST_CLASS=dev.remotegpt.app.InterruptedInputSyncDeviceTest \
  python3 scripts/status_device_check.py
```

该专项检查重复停止/发送只执行一次、编辑草稿落盘、附件失效时不发送、原始与修改后的历史均保留，以及其他客户端推进会话后阻止旧编辑稿重发。先按前文构建预览包；脚本会清空预览应用数据，并通过临时 `adb reverse` 端口连接隔离 SSH 服务。fixture 默认模型提供方与测试会话保持一致，兼容要求两者一致的新版 Codex CLI。

`ChatMathParserTest` 覆盖四种公式分隔符、代码/价格排除、列表/表格混排、流式前缀及块复用。`MathRenderingDeviceTest` 验证分数、积分、矩阵的实际像素输出、深浅主题、字号、宽公式与源码复制；Release 设备回归也检查经过 R8 优化后的原生公式画布。

`ConversationActionsDeviceTest` 验证会话复制入口的原文、点击尺寸、勾选反馈、连续点击重新计时、流式内容更新清除反馈、长按提示，以及超长回复的分享引导。公式复制与内容同行，表格全屏入口与原始 Markdown 复制一并覆盖；代码阅读回归检查图标化后的全屏入口。

`StatusIndicatorDeviceTest` 覆盖服务器状态图标的读屏标签、长按说明和行选择，正常连接转失败时的文字提示，以及断网/同步过程中任务完成状态不被覆盖。配合 `HostDetailsDeviceTest`、`OutputVisualizationDeviceTest` 和终端交互测试检查连接操作及状态展示。

图标更换后用 `DesignPreviewActivity --es screen icons --es theme dark`（以及 `light`）查看完整 Phosphor Bold 图集，在 16dp / 20dp 实际尺寸下核对形状和填充；预览会实例化所有图标，包括不常见页面使用的图标。运行 `python3 scripts/generate_icons.py` 后生成文件应保持一致，常规构建不需要下载图标。

`HostEditorDeviceTest` 覆盖分组编辑共用草稿、缺失私钥跳转认证、无效跳板机定位与删除、未保存凭据的取消/丢弃，以及保存期间禁用修改和返回。`LifecycleDeviceTest` 检查元数据、编辑分组恢复与凭据不进入 SavedState；`WorkbenchDeviceTest` 检查编辑/删除另一服务器不影响当前工作区。

服务器布局离线预览使用 `DesignPreviewActivity --es screen host`、`host-editor`、`host-editor-auth`、`host-editor-jumps`、`host-editor-remote`。本轮在 API 35 模拟器核对中英文、深浅主题、320dp 宽度和 200% 字号；未执行真实远端 SSH 用例或真机 TalkBack 检查。

`HostDetailsDeviceTest` 同时验证标题栏单个连接图标按钮的 48dp 点击区域、状态读屏信息、连接/取消/断开/停止重连/初始化操作，以及远端环境详情的展开与关闭。

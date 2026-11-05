# App Server 协议实现

GHub 通过 SSH 使用 Codex 的 `app-server proxy` 访问远端 daemon。协议逻辑位于 [`codex-client`](../codex-client/src/main/kotlin/dev/remotegpt/client)，官方接口参考见 [Codex App Server](https://developers.openai.com/codex/app-server/)。

## 版本与传输

客户端接受 Codex CLI / App Server **0.164.0 及更新的 0.x 版本**，检查 CLI、daemon 和 `initialize` 返回的版本，并通过握手确认连接。版本不符合范围或无法解析时，界面显示兼容性错误。

传输过程如下：

```text
SSH exec: codex app-server proxy
  → HTTP WebSocket Upgrade
  → WebSocket text frame 中的 JSON-RPC 消息
  → initialize 响应
  → initialized 通知
```

proxy 通道使用原始字节流，无 PTY；stderr 单独读取。终端功能另行申请 PTY。

请求包含 `id`、`method`、`params`，按 App Server 格式省略 `jsonrpc` 字段。服务端请求具有 `id`，通知没有 `id`；客户端分别处理响应、审批请求和通知。

## 接口映射

| 能力 | 接口 |
|---|---|
| 初始化、账户、模型 | `initialize`、`initialized`、`account/read`、`model/list` |
| 会话列表与元数据 | `thread/list`、`thread/loaded/list`、`thread/read`、`thread/name/set` |
| 新建与订阅 | `thread/start`、`thread/resume` |
| 历史分页 | `thread/turns/list`，正文使用 `itemsView: full` |
| 状态核对 | `thread/turns/list(limit=1, sortDirection=desc, itemsView=notLoaded)` |
| 归档与恢复 | `thread/archive`、`thread/unarchive` |
| 对话与中断 | `turn/start`、`turn/interrupt` |
| 流式状态 | `thread/status/changed`、`turn/*`、`item/*` |
| 命令与文件审批 | `item/commandExecution/requestApproval`、`item/fileChange/requestApproval` |
| 用户提问与回答 | `item/tool/requestUserInput`，通过原请求 ID 返回 `answers` |
| 审批与提问同步 | `serverRequest/resolved` |

枚举沿用实际 schema，例如 `approvalPolicy: on-request`、`sandbox: workspace-write`。扩展字段可被忽略，未知时间线类型显示占位内容；暂未接入的服务端交互请求返回协议错误，用户可转到 CLI 处理。默认连接使用常规 API。

### 用户问答

根据 Codex 0.165.0 导出的 `ToolRequestUserInputParams` / `ToolRequestUserInputResponse`，客户端将 `item/tool/requestUserInput` 展示为问题卡片，支持多问题、选项说明、无选项时的文字输入，以及 `isOther` 开启的自定义答案。`isSecret` 输入使用密码显示且不进入页面保存状态。选项不会自动选中或提交。

回答通过原 JSON-RPC 请求 ID 返回，例如 `{"answers":{"scope":{"answers":["Prepare structures"]}}}`；它不是 `turn/start` 消息，也不使用审批的 `decision` 字段。提交后禁止重复发送，直到 `serverRequest/resolved` 关闭卡片；另一客户端回答、回合中断及重连重放由同一机制处理。请求绑定连接 epoch，旧连接上的问题不能重放到新连接。

`isBlocking: true` 显示「等待回答」；`false` 保留任务运行状态并提示用户可以在任务继续时回答。旧请求缺少该字段时按阻塞处理，不根据已废弃的 `autoResolutionMs` 自动替用户回答。

历史中的用户消息若以 `<send_user_message_question_reply>` 开头，客户端解析其中的 JSON 数组，显示问题、答案和标签后附加的文字。`questionItemId` 按不透明字符串保留；原始消息仍完整存入缓存。字段损坏、未闭合标签及普通引用示例保留原文。`agentMessage.questions` 中的问题和选项也会显示并保存，但这些历史元数据本身不创建可提交的 RPC 请求。

`request_user_input_async` 是用户示例中的工具名称，不是 App Server 方法。本机 0.165.0 未提供该工具；客户端不会从历史 ID 猜测提交接口。可交互的提问以服务端实际发出的 `item/tool/requestUserInput` 为准。

## 会话生命周期

### 已有会话

第一次打开会话时，客户端通过 `thread/resume` 建立订阅并读取历史。同一连接内再次打开时复用订阅和已同步正文。重建连接后重新订阅，并与服务端历史合并。

CLI 和手机共享同一个用户、配置目录及 daemon 时，可订阅同一持久化会话，继续处理原任务和审批。

### 首条消息之前

新会话可能尚未生成 rollout。此时 `thread/read` 能返回元数据，`thread/resume` 或 `thread/turns/list` 可能返回 `no rollout found`、`not materialized yet`。

客户端保存 `awaitingFirstMessage` 标记，直接呈现空会话，并在列表刷新时保留该记录。首条消息确认后清除标记，建立所需订阅并补齐历史。

旧记录缺少本地标记时，`CodexApi.resumeOrReadEmpty` 依次核对元数据和历史错误来识别空会话。若另一客户端已发送消息，则重新订阅原 ID。尚未持久化的空会话可能随 daemon 重启消失。

### 失效记录

当恢复和元数据查询都确认会话不可用时，返回 `ThreadUnavailable`。界面保留草稿与缓存，显示重新检查和返回项目入口。会话错误只影响对应会话；传输故障交由连接恢复流程处理。

## 消息与审批一致性

- 每次连接建立独立 epoch，审批响应同时核对请求 ID 和 epoch。
- `serverRequest/resolved` 关闭其他客户端上的同一审批；已提交审批保持提交状态，等待服务端结果。
- 历史、最终条目与流式增量按 thread / turn / item ID 合并，最终条目替换对应增量。
- 完成事件触发历史校验；历史请求与事件处理通过本地屏障协调。
- `turn/start` 发出后若响应丢失，草稿标记为「发送状态待确认」，待用户核对历史。`clientUserMessageId` 用于关联消息，重试决策仍由发送状态决定。

连接调度、缓存与后台资源管理见[连接与同步](connection-recovery.md)。

## 模型与思考级别

`model/list` 按 `nextCursor` 分页。界面使用服务端提供的 `model`、`displayName`、`isDefault`、`hidden`、`supportedReasoningEfforts` 和 `defaultReasoningEffort`。

`thread/start` / `thread/resume` 返回的 `model` 与 `reasoningEffort` 映射到会话配置。发送时，`turn/start` 使用 `model` 和 `effort`；未选择覆盖值时省略参数，沿用会话设置。切换模型后采用新模型的默认思考级别，在下一条消息生效。

## 状态观察

未打开会话的状态通过 `thread/loaded/list`、`thread/read` 和不含条目正文的 `thread/turns/list` 查询。列表发现负责标题、目录等元数据，打开会话时再加载正文。各主机的观察循环独立于当前页面和归档筛选。

## 协议变更的验证

调整兼容范围或接入新接口时，先核对目标 CLI 导出的 schema，再运行协议单元测试、独立 daemon / SSH 集成测试和相关 Android 流程。测试命令见[测试指南](verification.md)。

[protocol-samples.json](protocol-samples.json) 保存了脱敏消息示例，可与 `ProtocolTest` 和 `scripts/probe.py` 对照阅读。

### 动态工具调用记录

`dynamicToolCall` 通过 `item/started`、`item/completed` 与历史轮次中的条目统一解析。界面显示工具命名空间和名称、JSON 参数、执行状态、耗时和返回内容；`success: false` 即使伴随 `status: completed` 也标记为失败。文本结果可查看和复制，图片、音频结果显示可复制的地址，未知内容类型保留为 JSON。结构化详情参与缓存和内存预算；旧版缓存中只有占位提示的记录需要联网刷新会话后恢复详情。

字段依据本机 Codex 0.166.0 导出的 `ThreadItem.dynamicToolCall` 与 `DynamicToolCallOutputContentItem`。这里支持执行记录展示；服务端要求客户端执行工具的请求仍按现有支持范围处理。

`contextCompaction` 是仅含 ID 和类型的上下文整理标记，按开始/完成事件显示「正在整理上下文」或「上下文已整理」，在执行记录中按已支持类型展示。旧缓存中的占位文本也按类型替换，离线可显示正确状态；活动轮次的历史快照不会使已确认完成的整理记录回退为进行中。

## 停止后修改输入

只有最新且已确认 `interrupted` 的轮次提供编辑入口。点击用户消息下方的铅笔后，原文与附件载入底部现有输入框，顶部显示编辑提示与取消入口；模型、附件与语音工具沿用普通输入模式。发送按钮沿用原来的圆形箭头，位置和尺寸保持一致；编辑状态下其无障碍名称为「修改后重发」（English: “Edit and resend”）。顶部提示允许随语言和字号换行。原消息始终保留，取消编辑或重发成功后恢复普通草稿，重发会创建新轮次。原始消息和执行记录保留，停止前产生的文件改动不会回滚。

本机发送的输入保存原始文本、附件、模型和思考级别快照；编辑草稿与普通输入框草稿分别持久化，取消或发送编辑稿不会覆盖普通草稿。旧消息或其他客户端发送的消息缺少快照时，保留历史文本和明确记录的图片路径，不从正文猜测附件元数据。

发送前校验远程附件可读性和最新轮次状态；会话已被其他客户端推进时，保留编辑稿并阻止重发。发送中锁定输入，交付不确定或应用在发送中退出后，需刷新历史并确认后才能解锁。服务端核对和 `turn/start` 是两次请求，不能保证跨客户端的原子比较与发送；服务端的活动轮次约束仍是最后一道检查。

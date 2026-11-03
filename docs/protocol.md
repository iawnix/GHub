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

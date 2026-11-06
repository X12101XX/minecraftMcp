# 工具与内部桥契约

> 本文已与当前实现对齐，JSON 仍以接口示例形态展示；实际验证环境、结果和未覆盖边界见 [验证报告](verification-report.md)。相关文档：[总体架构](architecture.md)、[实施计划](implementation-plan.md)、[Skill 设计](skills-design.md)、[项目说明](../README.md)。

## 1. 边界与约定

- Fabric Mod 提供仅绑定 loopback 地址、使用 Token 认证的内部 HTTP 桥。它不是 MCP 服务端，不实现另一套同名 MCP 协议。
- 独立 TypeScript MCP Server 使用官方 SDK，通过 stdio 提供普通 `tools/list` 与 `tools/call`。协议版本、初始化和能力协商以选定 SDK 实际支持范围为准；不凭本文声称兼容某个未验证版本。
- 不依赖实验性 MCP 长任务扩展。本文的 operation 是普通工具返回的应用层任务；客户端通过普通工具轮询。
- 所有真实世界、玩家、区块、方块状态和碰撞形状的访问都在 Minecraft 服务端线程进行。后台工作只允许处理已脱离世界引用的 DTO，包括编码、压缩与分页。
- 所有写操作固定异步；`get_region`、`get_terrain` 固定异步，即使只查询一个方块也不改成同步。其余三个小查询同步。异步查询和修改使用同一任务生命周期，但只有查询产生结果页。
- 唯一支持的区块策略为 `loaded_only`：只读写执行时已加载的区块，绝不为了请求而生成、加载、保活或等待加载区块。不提供可切换为隐式加载的参数。
- JSON 属性、枚举、工具名采用本文给出的英文拼写；解释和面向用户的信息使用中文。标记“必需”的属性不得省略，所有未声明属性均拒绝，不进行字符串到数字等隐式类型转换。

## 2. 通用输入类型与硬限制

### 2.1 标识符和坐标

| 类型 | 约束 |
| --- | --- |
| `world_session_id` | 非空不透明字符串，由 `get_world_info` 返回，调用者不得自行构造；表示一次世界实例生命周期，而不是存档名称 |
| `operation_id` | 非空不透明字符串，由服务端生成，不跨 session 复用 |
| `request_id` | 客户端生成的 UUID 字符串；所有异步提交必需，格式统一使用小写标准连字符形式 |
| `player` | 可选非空字符串；可为小写标准连字符 UUID 或玩家账户名称，不能使用聊天显示名、选择器或“最近玩家” |
| `dimension` | 完整资源标识符，如 `minecraft:overworld`，区分大小写，必须存在于指定 session |
| `pos` | 对象 `{ "x": integer, "y": integer, "z": integer }`，三个属性必需；整数须能表示为有符号 32 位值，并满足该世界的合法方块坐标范围 |
| `min`、`max` | 两个 `pos`；每轴 `min <= max`，不得静默交换，边界均包含 |
| `cursor` | 服务端返回的不透明分页游标；不得解释为数组偏移，不得跨任务、跨端点使用 |

区域体素数为 `(max.x-min.x+1) × (max.y-min.y+1) × (max.z-min.z+1)`，计算前进行安全范围检查，不能依赖可能溢出的乘法结果。

`get_world_info` 对每个维度给出 `min_y` 和 `max_y_exclusive`。合法方块 Y 范围为 `min_y <= y < max_y_exclusive`。区域的 `max.y` 仍然是包含边界，因此不得直接传入 `max_y_exclusive`。XZ 合法坐标范围由世界信息公布；所有方块和区域读写还必须在实际执行时验证当前游戏世界边界，越界返回 `OUT_OF_BOUNDS`，不得另选允许越界策略。世界边界可能变化，bootstrap 的范围只是当时信息。

所有字符串必须为合法 Unicode。资源 ID 最长 256 个字符；session、operation、cursor 最长分别为 128、128、2048 个字符；玩家账户名称最长 64 个字符，UUID 为 36 个字符。properties 最多 64 项，属性键和值分别最长 64 个字符，均非空；这些字段限制与 2 MiB 请求总量限制同时生效，不代表实际注册表支持任意这些长度的标识符。实现必须为每个工具注册完整 inputSchema；选用 SDK 支持 outputSchema 时也必须提供与本文成功结果匹配的输出 Schema，并验证实际输出。

### 2.2 方块状态与匹配条件

方块状态对象 `block_state`（写入简写与完整读结果共用形状）：

```json
{"id":"minecraft:oak_log","properties":{"axis":"y"}}
```

- `id` 必需，为已注册方块的完整资源标识符；未知方块 ID 返回 `INVALID_BLOCK_ID`。
- 写入目标的 `properties` 可选，默认 `{}`，为键和值均为字符串的对象；从该版本真实注册表的默认状态出发，应用提供的合法属性，补全未提供属性，不臆造默认值。
- 查询结果和 `expected_state` 必须带该方块全部属性，无属性方块也提供 `{}`。expected_state 是精确旧状态，不允许默认值补全或子集匹配。未知属性、不合法属性值或 expected_state 缺失属性返回 `INVALID_BLOCK_STATE`。
- “完整状态”只指方块类型与 block state 属性，不含方块实体 NBT、物品、实体或计划刻数据。工具不提供复制、保留或写入 NBT 的能力，也不能把 palette 编码宣传为完整存档备份。

`block_filter`：

```json
{"id":"minecraft:oak_log","properties":{"axis":"y"}}
```

- `id` 必需且精确匹配，不支持通配符、tag、正则表达式；未知 ID 返回 `INVALID_BLOCK_ID`。
- `properties` 可选，默认 `{}`，仅约束提供的属性；因此省略时匹配该类型的全部合法状态。
- 所提供属性必须是该方块实际支持的合法属性和值。空条件不代表匹配所有方块。

### 2.3 初始限制

这些是初始安全上限，不是吞吐量或响应时间保证。运行时 `get_world_info.limits` 公布实际生效值；服务端配置只能在已声明的安全边界内调整，客户端不得硬编码为能力承诺。

| 项目 | 初始上限或预算 | 计量规则 |
| --- | --- | --- |
| 一次区域查询 | 65,536 体素 | `get_region` 的区域体积；`get_terrain` 的列数乘以扫描高度 |
| 一次区域修改 | 262,144 个候选坐标 | 填充和替换均按完整区域体积计算，不按预估匹配数计算 |
| 一次批量设置 | 8,192 项 | `batch_set_blocks.blocks` 数组长度 |
| 一次世界任务涉及区块 | 64 个 | 区域覆盖或批量位置去重后的 XZ 区块数；负坐标使用向下取整的区块换算 |
| 全局排队任务 | 32 个 | 不含已经开始执行的任务；不同客户端和读写任务共享队列容量 |
| 内部桥请求体 | 2 MiB | 2,097,152 字节，接收时计量并拒绝超限；不接受压缩请求体绕过上限 |
| 一页结果或错误响应 | 512 KiB | 524,288 字节，按实际 UTF-8 JSON 响应体计量，包含信封、palette 和游标 |
| 终态保留期 | 5 分钟 | 进入终态后开始计时；读取、重试和取消请求不续期 |
| 每 tick 工作预算 | 全局约 2 ms | 读写共享服务端线程调度预算，至少在每个工作单元之间检查；不承诺单个引擎调用可被中断 |
| 每 tick 写入预算 | 全局最多 1 个执行阶段候选坐标 | 变更、无需变更、跳过与执行失败都消耗候选额度；batch 注册表预校验独立分片，全部通过后才开始执行 |

单次引擎调用和邻居更新可能超过约 2 ms；这不是硬实时保证。查询扫描也必须分片并计入全局时间预算，不能用“不修改方块”为理由在一个 tick 内扫描全部区域。

## 3. 世界 session、维度与一致性

### 3.1 Bootstrap 与失效

`get_world_info` 是唯一无需 `world_session_id` 的世界工具，输入为 `{}`。当前实现只在世界就绪后启动桥，停止/退出时关闭桥；桥关闭后 MCP 工具返回 `BRIDGE_UNAVAILABLE`，不能成功查询一个虚构的离线世界。正常响应 `ready=true` 并提供当前 session、维度信息、在线玩家清单、限制和支持工具。

世界卸载、切换、重新打开或服务端重启均产生新的 session。相同存档路径不代表相同 session；客户端必须重新 bootstrap，不得自动把旧写任务改投新 session。

- `get_player_position` 必须提供 session，但不接受 dimension；玩家可以跨维度，响应携带其在读取时的实际 dimension。
- `get_block`、两种大查询和所有修改必须同时提供 session 与 dimension；没有隐式主世界，也不推断玩家所在维度。
- 四个任务管理工具必须提供任务所属的 session 与 operation ID，不接受 dimension，因为任务记录已固定维度。
- 世界调用的旧 session 返回 `WORLD_SESSION_MISMATCH`；session 匹配但世界未就绪返回 `WORLD_NOT_READY`。
- session 终止时，不再接受新任务，未完成任务在内存中以 `WORLD_SESSION_ENDED` 终止并停止世界访问，随后清理任务、结果页、错误页、幂等记录及网络监听。客户端可能来不及观察终态，不能承诺关服后可读取旧任务，也不承诺跨 session 保留结果。
- 世界结束或进程重启后不保留任务、幂等记录或结果页。桥关闭时返回连接层 `BRIDGE_UNAVAILABLE`；新桥中查询旧任务，或任务不存在/已过期时统一返回 `OPERATION_NOT_FOUND`，不能把“查不到”解释成“没有执行过”。TTL 保留承诺仅适用于同一存活 session，生命周期终止优先于 TTL。

### 3.2 读取和修改的观察窗口

`get_block` 与 `get_player_position` 在服务端线程单次读取并记录 `observed_tick`。异步查询跨多个 tick 采集，响应任务元数据给出 `started_tick` 与 `finished_tick`，结果标记 `consistency="incremental"`：不同位置可能来自不同 tick，既不是原子快照，也不加世界锁。

查询成功后，结果 DTO 冻结；后续分页只读冻结 DTO，不重新读取世界，所以每页在 TTL 内不可变，重复同一游标返回相同内容。后台压缩不能扩大成“后台读世界”。

所有修改在被执行的位置上检查当时状态；其他玩家、红石或其他 Mod 可以在任务期间改变世界。`replace_blocks` 在处理该位置时匹配，而不是使用提交时扫描的旧快照。服务端内部写任务按接收顺序执行，不并行交错两个写任务；查询可以在写任务的分片之间推进，不能据此承诺读写事务隔离。

## 4. 公共返回、任务状态和分页

### 4.1 应用层结果与 MCP 映射

以下工具示例展示应用层 JSON 结果。MCP Server 将成功 JSON 作为普通工具结果的一个 `text` 内容项返回；当前实现不额外提供 `structuredContent`，客户端按该文本 JSON 解析。

```json
{"content":[{"type":"text","text":"{\"ok\":true,\"world_session_id\":\"s-example\"}"}],"isError":false}
```

工具执行前或执行期间同步失败时，返回 `isError=true`，文本内容为本文的 typed error 对象；SDK 或 MCP 协议层的畸形消息、未知方法等由 SDK 按其实际协议能力处理，不强行改写为业务任务失败。

异步提交成功后，后续任务 `failed` 或 `cancelled` 是任务状态，不是轮询调用本身失败：成功取得该记录的 `get_operation` 必须为 `isError=false`，应用层 `ok=true`。只有查不到任务、身份认证失败等导致这次调用无法完成时，才返回工具错误。

### 4.2 统一异步任务记录

提交任一异步读写工具，均返回：

```json
{
  "ok":true,
  "operation":{
    "operation_id":"op-example",
    "request_id":"11111111-1111-4111-8111-111111111111",
    "world_session_id":"s-example",
    "dimension":"minecraft:overworld",
    "tool":"fill_region",
    "kind":"write",
    "state":"queued",
    "counts":{"total":8,"processed":0,"succeeded":0,"changed":0,"unchanged":0,"skipped":0,"failed":0},
    "error_count":0,
    "created_at":"2026-11-05T12:00:00Z",
    "started_tick":null,
    "finished_tick":null,
    "expires_at":null,
    "result_available":false,
    "terminal_error":null
  }
}
```

- `kind` 为 `read` 或 `write`。`state` 为 `queued`、`running`、`succeeded`、`failed`、`cancelled`。
- 正常迁移为 `queued → running → succeeded/failed/cancelled`；未开始的任务允许 `queued → cancelled/failed`。终态不可再迁移。
- `created_at`、非空 `expires_at` 使用 UTC RFC 3339 字符串。运行中 `expires_at=null`；进入终态后值为终态时间加 5 分钟，不会通过轮询延长。
- `started_tick`、`finished_tick` 为 session 内单调的服务端 tick 标识；尚未开始为 null，尚未终结的 `finished_tick` 为 null。排队取消时两个 tick 均可为 null。
- 初次提交成功通常返回 queued，但调度竞争也可能已是 running 或终态；客户端必须检查 state，不能依赖第一次一定 queued。
- 查询采集结束并冻结结果 DTO 后进入 succeeded；分页在读取 `get_query_result` 时从已冻结 DTO 确定性生成，不再读取世界。
- `terminal_error` 为任务级终止错误对象或 null。failed 必须非空；普通用户取消可为 null。
- 只有 succeeded 的 read 任务为 `result_available=true`。失败或取消的查询不开放部分结果页；部分扫描仍体现在 counts。写任务永远没有查询结果页。

### 4.3 计数定义

所有字段均为非负整数，`processed <= total`，且 `processed = succeeded + skipped + failed`。

- `total`：计划处理的候选坐标数；区域查询/修改为区域体积，地形查询为列数乘扫描高度，批量设置为数组项数，单方块写为 1。
- `processed`：已经得到成功、跳过或失败结论的候选坐标数，不包含尚未处理部分。
- `succeeded`：读取成功或写入成功处理的坐标数。
- `changed`：写任务中实际由本任务改变 block state 的坐标数，不包含邻居更新引起的其他坐标改变。
- `unchanged`：写任务中目标状态已相同、未执行状态修改的坐标数。写任务满足 `succeeded = changed + unchanged`；读任务的 changed、unchanged 均为 0。
- `skipped`：仅用于 replace 的过滤条件未匹配；其他工具正常情况下为 0。区块未加载不得伪装为 skipped 或空气。
- `failed`：定位到候选坐标的执行失败数。不能定位到候选坐标的任务级故障仅计入错误记录，不虚增 processed/failed。

`error_count` 为错误记录总数，不等于 failed：任务级错误可以有一条记录但 failed 为零。错误记录与终态计数在任务结束后冻结。任务成功意味着计划全部处理且 failed 为零；有坐标级失败则最终 state 为 failed，允许同时存在 changed > 0。单任务最多保留 65,536 条错误记录；达到上限时任务停止并以 `RESOURCE_EXHAUSTED` 终止，不静默丢弃错误。

### 4.4 幂等性

所有异步提交必须包含 `request_id`。幂等键作用域为“已认证调用主体 + world_session_id + request_id”，键不包含工具名，因此同一键不能被不同工具复用。

1. 在任何世界副作用前，原子地创建幂等记录并提交排队。相同键的并发请求最多创建一个任务。
2. 先做 schema、身份和大小检查，再规范化参数：填充声明的参数默认值、排序对象键；数组保持原顺序，尤其 batch 顺序不得重排。规范化对象包含工具名和除 request_id 外的全部参数。写入 properties 省略与 `{}` 规范化为同一输入；注册表默认状态解析是执行计划的一部分，不把“显式填写了某默认属性”和“省略该属性”的不同请求强行视为同一输入。
3. 相同键、相同规范化内容返回原任务的当前记录，包括原有终态和 operation ID，不重复读写；相同键、不同内容返回 `IDEMPOTENCY_CONFLICT`。
4. 接受任务前发生的校验失败、队列满或请求过大不占用 request_id；只有成功接受的任务具有幂等记录。
5. 幂等记录与任务共同保留：活动任务不因 5 分钟到期而丢失，终态保留 5 分钟。TTL 之后复用键可能产生新任务，因此客户端必须把未知结果当作不确定，不能把 TTL 后重提当成安全恢复；一个逻辑新动作必须使用新 UUID。
6. 响应丢失时，在保留期内使用相同键与相同参数重提。查询是同一份采集任务，而不是隐式刷新数据；需要刷新时使用新键。

### 4.5 分页公共规则

`get_operation_errors` 与 `get_query_result` 仅在相应任务终态且数据可读取时使用。`cursor` 可选，省略表示第一页；不接受空字符串代表第一页。结果提供 `next_cursor`，null 表示最后一页。

游标绑定调用主体、session、operation、端点和冻结版本；伪造、跨端点、越界或不匹配游标返回 `INVALID_CURSOR`。任务过期优先返回 `OPERATION_NOT_FOUND`。页边界由服务端按字节预算冻结，没有可改变分页的 page_size 参数。相同游标在 TTL 内返回相同页内容，不能因其他客户端请求而前移。

若服务端无法把单个不可拆分记录编码进 512 KiB，查询在发布结果前以 `RESULT_TOO_LARGE` 失败，不返回超限页或静默截断。

### 4.6 游戏内过程展示（不改变工具参数）

观看者客户端与服务端均安装当前构建的 Mod + Fabric API，单人集成世界安装一次即可。无需额外预览工具或逐方块网络请求：`get_block` 标出单方块，`get_region` / `get_terrain` 标出与请求 min/max 相同的包含边界框，按 30 Tick × 50 ms 的客户端本地时长淡出。工具标签、玩家查询、bootstrap、分页和任务轮询显示在活动面板；每服务端 Tick 的同工具通知合并，已认证请求的同步验证失败显示红色错误码（同 Tick 多个同步错误只显示最近一个）。

五个写工具共用串行执行器，每 Tick 最多处理一个候选；区域按 X、Z、Y 顺序，batch 严格保留输入顺序。不变、过滤跳过、条件失败不播放伪造的成功落位。正常更新、邻居变化、保存、计数、部分失败和取消不回滚的语义保持不变。

`batch_set_blocks` 在所有目标/expected_state 注册表预校验成功后发布待放列表，等待 **20 个服务端 Tick** 才开始执行；这段时间 state 为 running，processed 仍为 0。非法后续片段必须阻止整个批次的任何写入/目标模型。目标模型是可交互世界之外的半透明预览；普通状态使用真实烘焙模型，特殊渲染目标使用真实形状的半透明光体，空气用删除框表示。处理一个候选后移除该项；完成、取消、失败时清掉剩余预览。

展示通过单向 `minecraft-mcp:visual_v1` S2C payload 发送：session、dimension、operation/event ID、单调 sequence、事件类型、duration、包含边界 min/max、style、offset/total/processed，以及每页至多 128 个 `{pos,完整 BlockState}`。一次最多 8192 项、每 Tick 最多 4 页；不传凭据、不引入客户端写入入口、不加载区块。客户端最多保留 64 个选框、一个 8192 项活动列表和最近 3 个工具标签；10 秒无进度清除列表，换维度/换世界/断连/session 变更立即清空。

原版客户端仍看到真实逐块世界更新，但没有自定义展示。HUD/光效/幽灵模型都不证明任务完成，仍须轮询并回读；客户端慢帧、丢失观察时机或离得太远可能看不到每次短时提示，不能将显示帧率当作服务端处理计数。


## 5. 十个世界工具

以下调用使用普通 `tools/call` 的 `{name, arguments}` 形状；外层 JSON-RPC ID 和版本由 SDK 处理。示例 `s-example`、`op-*` 是服务端标识符的示意值。

### 5.1 `get_world_info`

**输入：** 空对象，不接受 session、dimension 或其他参数。

**输出：** `ready`、`world_session_id`、`dimensions`、`players`、`limits`、`supported_tools`。就绪时维度项包含 `id`、`min_y`、`max_y_exclusive`、`min_x`、`max_x`、`min_z`、`max_z`；XZ 最大值均包含。玩家清单包含 UUID 和账户名称，供 get_player_position 的显式选择使用；省略选择只在恰好一名在线玩家时有效。

```json
{"name":"get_world_info","arguments":{}}
```

```json
{
  "ok":true,
  "ready":true,
  "world_session_id":"s-example",
  "dimensions":[{"id":"minecraft:overworld","min_y":-64,"max_y_exclusive":320,"min_x":-29999984,"max_x":29999983,"min_z":-29999984,"max_z":29999983}],
  "players":[{"player_uuid":"22222222-2222-4222-8222-222222222222","name":"Builder"}],
  "limits":{"query_voxels":65536,"write_voxels":262144,"batch_entries":8192,"chunks_per_operation":64,"queued_operations":32,"request_bytes":2097152,"page_bytes":524288,"terminal_ttl_seconds":300,"tick_budget_ms":2,"write_candidates_per_tick":1},
  "supported_tools":["get_world_info","get_player_position","get_block","get_region","get_terrain","set_block","remove_block","fill_region","replace_blocks","batch_set_blocks","get_operation","cancel_operation","get_operation_errors","get_query_result"]
}
```

具体维度、构建高度和坐标范围必须来自实际版本/世界，不把示例值写死。`supported_tools` 只列出当前可调用工具。桥关闭时返回 `BRIDGE_UNAVAILABLE`，不返回伪造的就绪响应。

### 5.2 `get_player_position`

**必需：** `world_session_id`。**可选：** `player`，接受玩家 UUID 或账户名称；省略时仅在恰好一名在线玩家时自动选中，否则返回 `PLAYER_REQUIRED`。不接受 dimension。

**语义：** 查找本 session 的在线玩家，UUID 精确匹配，账户名称按完整名称不区分大小写匹配，不接受模糊匹配；明确指定但不存在或读取前离线返回 `PLAYER_NOT_FOUND`，任何选择歧义返回 `PLAYER_REQUIRED`。省略 player 时在实际服务端线程读取阶段判断在线人数，不根据旧 bootstrap 清单猜测。返回玩家实际 UUID、真实位置及所在维度，不对浮点坐标取整；`block_pos` 为每轴向下取整的方块位置。朝向使用 Minecraft 角度约定，单位为度，不能把 yaw/pitch 当作弧度。

```json
{"name":"get_player_position","arguments":{"world_session_id":"s-example","player":"Builder"}}
```

```json
{"ok":true,"world_session_id":"s-example","player_uuid":"22222222-2222-4222-8222-222222222222","dimension":"minecraft:the_nether","position":{"x":-0.25,"y":65.0,"z":8.5},"block_pos":{"x":-1,"y":65,"z":8},"yaw":90.0,"pitch":0.0,"observed_tick":1200}
```

### 5.3 `get_block`

**必需：** `world_session_id`、`dimension`、`pos`。无可选参数。

**语义：** 同步读取一个已加载位置的完整 block state，同时返回 air/fluid/collision 的独立事实。不得用一次不安全的取区块 API 顺便加载区块。

```json
{"name":"get_block","arguments":{"world_session_id":"s-example","dimension":"minecraft:overworld","pos":{"x":0,"y":64,"z":0}}}
```

```json
{"ok":true,"world_session_id":"s-example","dimension":"minecraft:overworld","pos":{"x":0,"y":64,"z":0},"block_state":{"id":"minecraft:stone","properties":{}},"is_air":false,"fluid":{"is_empty":true,"id":null,"is_source":null},"has_collision":true,"observed_tick":1201}
```

`is_air` 使用引擎的空气判断。`fluid` 基于该位置实际 fluid state；空 fluid 的 id/is_source 为 null，非空时 id 为注册流体标识符、is_source 为布尔值。`has_collision` 表示该位置使用无实体上下文查询得到的碰撞形状非空，不代表整块实心、可站立或对所有实体都相同。

### 5.4 `get_region`

**必需：** `world_session_id`、`dimension`、`min`、`max`、`request_id`。**可选：** `encoding`，枚举 `auto` / `palette_indices` / `palette_rle`，默认 `auto`。

输入体积不得超过 65,536，涉及区块不得超过 64；固定异步。返回第 4.2 节完整任务信封，kind=read、tool=get_region、total=区域体积。查询在每个位置采集完整 block state；它不附带每个方块的 fluid 或 collision 信息，不承诺 NBT 快照。

```json
{"name":"get_region","arguments":{"world_session_id":"s-example","dimension":"minecraft:overworld","min":{"x":0,"y":64,"z":0},"max":{"x":1,"y":64,"z":0},"encoding":"palette_rle","request_id":"33333333-3333-4333-8333-333333333333"}}
```

提交返回示例使用第 4.2 节信封，其中 operation_id=`op-region`、request_id 为上值、tool=`get_region`、kind=`read`、counts.total=2，其余初始计数为零。最终数据通过 `get_query_result` 获取，完整示例见第 6.4 节。

**体素索引和无损编码：**

设 `sx=max.x-min.x+1`、`sz=max.z-min.z+1`。线性索引：

`index = (x-min.x) + sx × ((z-min.z) + sz × (y-min.y))`

即 x 最快、z 次之、y 最慢。每页覆盖连续线性区间 `[offset, offset+count)`，所有页无重叠、无遗漏地覆盖整个区域，页可以在行或层中间结束。

每页自带局部 `palette`，每项为完整 block state，等价状态按名称和排序后的属性内容去重，不能只按方块名称合并。palette 索引仅在本页有效。

- `palette_indices` 编码的数据为 `indices` 非负整数数组，长度恰为 count，每个索引小于 palette 长度。
- `palette_rle` 编码的数据为 `runs` 数组，每项 `{ "palette_index": integer, "length": integer }`，length >= 1；所有 length 之和恰为 count。连续相同状态可以合并，跨页 run 必须切开。
- 两种编码解码后必须逐位置完全一致，RLE 不降采样、不省略空气、不合并不同属性。错误、未加载或未读取位置不得编码成空气或特殊 palette 占位符。
- `auto` 对同一候选页分别计算两种编码的实际 UTF-8 JSON 响应字节数，选择较小者，相等时使用 palette_indices；超过页预算则缩小候选页并重新比较。每页返回实际 encoding，不返回 auto；不同页可使用不同编码，页划分与编码在发布前冻结。

### 5.5 `get_terrain`

**必需：** `world_session_id`、`dimension`、`min`、`max`、`request_id`。**可选：** `clearance_height`，整数 1..64，默认 2，表示支撑面上方要求的连续空气方块高度。min/max 同时约束 XZ 列范围与 Y 扫描区间，不存在“自动从世界顶到底”默认扫描，也不为净空查询读取区间外世界。

**语义：** 为每个 XZ 列在 `[min.y,max.y]` 内返回完整有界地形描述：最高非空气/流体/碰撞位置、空气区间、流体区间、碰撞障碍区间，以及多层候选支撑面与材质/净空信息。不能将洞穴、屋檐和多层结构压缩为单个最高点。扫描体积不得超过 65,536，涉及区块不得超过 64；固定异步。

- 空气依据 `is_air`，不以方块 ID 字符串硬编码替代引擎判断。
- 非空流体包括含水方块中的 fluid state；流体和碰撞可以在同一位置同时存在。
- 碰撞依据 `get_block` 相同的无实体上下文非空碰撞形状语义；草、花、流体和部分非实心方块不能被一概当作可支撑地面。碰撞查询不会隐式加载区块。
- 三个 highest 字段的条件在扫描范围内没有命中时对应值为 null；区间或候选不存在时返回空数组。null/空数组仅代表“此有界区间未找到”，不代表整列世界为空，也不代表区块未加载。
- `air_intervals` 为连续 is_air=true 的包含边界区间；`fluid_intervals` 为连续且流体 ID、is_source 和所在完整 block state 均相同的非空流体区间；`collision_intervals` 为连续且完整 block state、碰撞事实及完整顶面事实相同的障碍区间。各数组按 min_y 升序；流体与碰撞数组允许重叠，含水方块不得被归入空气。每个区间带 min_y/max_y、lower_truncated/upper_truncated；触及请求边界且该侧仍有合法世界高度时为 true，只表示区间外未知，不能据此断言延伸长度或已知水深。
- `support_candidates` 保留所有碰撞方块上方可见非碰撞空间的候选，以及扫描顶边界处的碰撞候选，按 y 升序；半砖/楼梯等不完整顶面候选也保留。每项带 y、完整 block_state、full_top_face、clear_height、clearance_truncated、support_ok。full_top_face 仅在无实体上下文碰撞形状于方块顶部 y+1 完整覆盖单位 XZ 顶面时为 true，不用方块名称猜测。
- clear_height 是从 y+1 起在本次扫描区间内已观察到的连续空气且 fluid state 为空的格数，遇到任一非空气或非空流体即停止；到请求上边界仍为空且世界上方尚存在时 clearance_truncated=true。support_ok 仅表示 full_top_face=true、支撑方块自身无流体、clear_height>=clearance_height。未满面或净空不足/未知的候选不得标为满足支撑条件；足量净空之后的截断不否定已知足量空间。该保守几何判定不检查伤害方块、动态实体、可燃性或完整路径，不是通用安全落脚保证。
- 此版按整个有界扫描体积逐位置处理，counts.total 与 processed 按体素计；最终输出条数为 XZ 列数。后台不补查世界。

```json
{"name":"get_terrain","arguments":{"world_session_id":"s-example","dimension":"minecraft:overworld","min":{"x":0,"y":60,"z":0},"max":{"x":0,"y":66,"z":0},"request_id":"44444444-4444-4444-8444-444444444444"}}
```

提交返回第 4.2 节完整信封，operation_id=`op-terrain`、kind=`read`、tool=`get_terrain`、counts.total=7。成功后的结果页例如：

```json
{
  "ok":true,"operation_id":"op-terrain","world_session_id":"s-example","dimension":"minecraft:overworld",
  "result_type":"terrain","consistency":"incremental","started_tick":1202,"finished_tick":1203,
  "min":{"x":0,"y":60,"z":0},"max":{"x":0,"y":66,"z":0},
  "offset":0,"count":1,"total_columns":1,"clearance_height":2,
  "columns":[{
    "x":0,"z":0,
    "highest_non_air":{"y":65,"block_state":{"id":"minecraft:water","properties":{"level":"0"}}},
    "highest_fluid":{"y":65,"fluid":{"id":"minecraft:water","is_source":true},"block_state":{"id":"minecraft:water","properties":{"level":"0"}}},
    "highest_collision":{"y":63,"block_state":{"id":"minecraft:stone","properties":{}}},
    "air_intervals":[{"min_y":66,"max_y":66,"lower_truncated":false,"upper_truncated":true}],
    "fluid_intervals":[{"min_y":64,"max_y":65,"fluid":{"id":"minecraft:water","is_source":true},"block_state":{"id":"minecraft:water","properties":{"level":"0"}},"lower_truncated":false,"upper_truncated":false}],
    "collision_intervals":[{"min_y":60,"max_y":63,"block_state":{"id":"minecraft:stone","properties":{}},"full_top_face":true,"lower_truncated":true,"upper_truncated":false}],
    "support_candidates":[{"y":63,"block_state":{"id":"minecraft:stone","properties":{}},"full_top_face":true,"clear_height":0,"clearance_truncated":false,"support_ok":false}]
  }],
  "next_cursor":null,"expires_at":"2026-11-05T12:05:01Z"
}
```

列排序 x 最快、z 次之，`offset` 和 `count` 按列计量，区别于任务 counts 的体素计量。每列是不可拆分分页记录。

### 5.6 写入共同行为

下列五个写工具始终异步，必须显式指定目标维度和 session，必须提供 request_id。不存在 fire-and-forget 成功、隐式回滚或客户端超时自动取消。

所有五个写工具均接受 `on_error`，枚举 stop/continue，默认 stop。set/remove/fill/replace 可提供一个可选 `expected_state`，对各目标坐标使用同一完整精确旧状态；batch 只在每项提供可选 expected_state，不接受顶层 expected_state。省略表示不检查旧状态；提供时必须在实际写入前、同一服务端线程工作单元内比较完整状态，不匹配记录 `PRECONDITION_FAILED` 并计 failed，不修改该坐标。replace 先匹配 filter，不匹配仍为 skipped；匹配后才检查 expected_state。前置条件不构成全区域事务锁。

- 使用目标 Minecraft/Fabric 版本支持的服务端正常状态更新路径，发送必要客户端同步并触发正常邻居更新。不提供“禁用更新更快”的工具参数；具体 API/更新标志在版本确定后实现和验证，不能猜测某常数在所有版本含义一致。
- 不执行模拟玩家挖掘，不自动生成物品掉落；状态替换仍可能触发游戏和其他 Mod 的正常副作用。
- 目标状态与当前完整状态相同计为 unchanged，不重复调用状态修改以制造额外更新。
- 替换方块类型可能销毁原方块实体内容，本契约不提供库存/NBT 备份或恢复。调用者必须在授权范围内使用写工具，建筑 Skill 的确认规则见 [Skill 设计](skills-design.md)。
- 原子性仅限单次引擎调用能够提供的范围；多方块任务不是事务。中途失败、取消、世界关闭、区块卸载或进程崩溃，都可能已产生部分修改，且不会自动回滚。
- 默认 on_error=stop：首次坐标级错误后停止，保留已修改部分；continue：记录明确无副作用的坐标级拒绝或前置条件不匹配，继续后续坐标，最终仍为 failed。任务级故障、session 结束、区块卸载、越界或未知副作用无论 on_error 均立即停止。无法确认副作用状态的引擎异常标记 `WRITE_OUTCOME_UNKNOWN`，不得自动重试该坐标或宣称未发生修改。
- 计数只涵盖能够确认的结果；`WRITE_OUTCOME_UNKNOWN` 的错误记录标记 `effect_unknown=true`，counts.changed 不能被当作全部副作用的精确上界。

### 5.7 `set_block`

**必需：** `world_session_id`、`dimension`、`pos`、`block_state`、`request_id`。**可选：** `expected_state`（默认不检查）、`on_error`（默认 stop）；block_state.properties 可省略并使用真实默认状态补全。counts.total=1。

```json
{"name":"set_block","arguments":{"world_session_id":"s-example","dimension":"minecraft:overworld","pos":{"x":0,"y":64,"z":0},"block_state":{"id":"minecraft:oak_log","properties":{"axis":"y"}},"expected_state":{"id":"minecraft:air","properties":{}},"on_error":"stop","request_id":"55555555-5555-4555-8555-555555555555"}}
```

返回第 4.2 节完整信封，operation_id=`op-set`、kind=`write`、tool=`set_block`、counts.total=1。写入成功且发生变化的终态计数为：

```json
{"total":1,"processed":1,"succeeded":1,"changed":1,"unchanged":0,"skipped":0,"failed":0}
```

### 5.8 `remove_block`

**必需：** `world_session_id`、`dimension`、`pos`、`request_id`。**可选：** `expected_state`（默认不检查）、`on_error`（默认 stop）。

**语义：** 精确设置为 `minecraft:air` 的完整状态；不是破坏方块动作，不产生工具主动生成的掉落。若当前位置为 cave_air 或 void_air，它们与 air 不是同一完整状态，仍可发生 changed。counts.total=1。

```json
{"name":"remove_block","arguments":{"world_session_id":"s-example","dimension":"minecraft:overworld","pos":{"x":0,"y":64,"z":0},"request_id":"66666666-6666-4666-8666-666666666666"}}
```

返回第 4.2 节完整信封，operation_id=`op-remove`、kind=`write`、tool=`remove_block`、counts.total=1。当前位置原本就是 minecraft:air 时，终态为 succeeded，计数示例：

```json
{"total":1,"processed":1,"succeeded":1,"changed":0,"unchanged":1,"skipped":0,"failed":0}
```

### 5.9 `fill_region`

**必需：** `world_session_id`、`dimension`、`min`、`max`、`block_state`、`request_id`。**可选：** `expected_state`（默认不检查）、`on_error`（默认 stop）；block_state.properties 可省略。

对包含边界的整个区域设置同一完整状态，候选体积最多 262,144，最多 64 区块，按 x/z/y 顺序处理。不提供 hollow、outline 或自动替换空气等隐式模式。

```json
{"name":"fill_region","arguments":{"world_session_id":"s-example","dimension":"minecraft:overworld","min":{"x":0,"y":64,"z":0},"max":{"x":1,"y":65,"z":1},"block_state":{"id":"minecraft:stone","properties":{}},"request_id":"11111111-1111-4111-8111-111111111111"}}
```

提交返回就是第 4.2 节完整示例。若七个位置改变、一个位置原本相同，则 succeeded 终态的计数为：

```json
{"total":8,"processed":8,"succeeded":8,"changed":7,"unchanged":1,"skipped":0,"failed":0}
```

### 5.10 `replace_blocks`

**必需：** `world_session_id`、`dimension`、`min`、`max`、`filter`、`block_state`、`request_id`。filter 使用第 2.2 节 block_filter。**可选：** `expected_state`（默认不检查）、`on_error`（默认 stop）；block_state.properties 可省略。

每个位置在执行时判断 filter，匹配则设置目标完整状态，否则计 skipped；扫描顺序、区域/区块限制与 fill_region 相同。不能因为预计只匹配一个位置而提交超大区域。

```json
{"name":"replace_blocks","arguments":{"world_session_id":"s-example","dimension":"minecraft:overworld","min":{"x":0,"y":64,"z":0},"max":{"x":1,"y":64,"z":1},"filter":{"id":"minecraft:oak_log","properties":{"axis":"y"}},"block_state":{"id":"minecraft:stone","properties":{}},"request_id":"77777777-7777-4777-8777-777777777777"}}
```

返回第 4.2 节完整信封，operation_id=`op-replace`、kind=`write`、tool=`replace_blocks`、counts.total=4。两处匹配并改变、两处不匹配的 succeeded 终态计数：

```json
{"total":4,"processed":4,"succeeded":2,"changed":2,"unchanged":0,"skipped":2,"failed":0}
```

### 5.11 `batch_set_blocks`

**必需：** `world_session_id`、`dimension`、`blocks`、`request_id`。**可选：** `on_error`，默认 stop。

`blocks` 是长度 1..8192 的数组，每项必需 `{pos, block_state}`，可选完整 expected_state（默认不检查）；block_state.properties 可省略，不允许项目内 session、dimension 或 on_error。整批必须同一维度，最多 64 个区块。重复坐标直接返回 `DUPLICATE_POSITION`，不采取“最后一个胜出”规则。静态 JSON/schema 校验失败时不创建任务；注册表状态校验可在接受任务后的 running 阶段按 tick 分批预校验，但所有目标状态及 expected_state 都验证通过之前不得进行任何写入。预校验失败时任务 failed、changed=0，并保留错误详情。实际执行保留数组顺序，运行时失败不承诺整批原子性。

```json
{"name":"batch_set_blocks","arguments":{"world_session_id":"s-example","dimension":"minecraft:overworld","blocks":[{"pos":{"x":0,"y":64,"z":0},"block_state":{"id":"minecraft:stone","properties":{}}},{"pos":{"x":1,"y":64,"z":0},"block_state":{"id":"minecraft:oak_log","properties":{"axis":"x"}}}],"request_id":"88888888-8888-4888-8888-888888888888"}}
```

返回第 4.2 节完整信封，operation_id=`op-batch`、kind=`write`、tool=`batch_set_blocks`、counts.total=2。第一项成功、第二项明确被拒绝的 failed 终态计数示例：

```json
{"total":2,"processed":2,"succeeded":1,"changed":1,"unchanged":0,"skipped":0,"failed":1}
```

## 6. 四个任务管理工具

### 6.1 `get_operation`

**必需：** `world_session_id`、`operation_id`。无可选参数。只读取任务记录，不重新执行任务，不刷新 TTL。

```json
{"name":"get_operation","arguments":{"world_session_id":"s-example","operation_id":"op-batch"}}
```

```json
{
  "ok":true,
  "operation":{
    "operation_id":"op-batch","request_id":"88888888-8888-4888-8888-888888888888","world_session_id":"s-example","dimension":"minecraft:overworld","tool":"batch_set_blocks","kind":"write","state":"failed",
    "counts":{"total":2,"processed":2,"succeeded":1,"changed":1,"unchanged":0,"skipped":0,"failed":1},
    "error_count":1,"created_at":"2026-11-05T12:00:00Z","started_tick":1204,"finished_tick":1205,"expires_at":"2026-11-05T12:05:01Z","result_available":false,
    "terminal_error":{"code":"PARTIAL_FAILURE","message":"部分坐标写入失败，已完成修改不会回滚。","retryable":false,"details":{"failed":1}}
  }
}
```

这里 MCP `isError=false`：轮询成功取得一个失败任务，不等于工具调用失败。

### 6.2 `cancel_operation`

**必需：** `world_session_id`、`operation_id`。无可选参数，无需 request_id，取消动作天然幂等。

```json
{"name":"cancel_operation","arguments":{"world_session_id":"s-example","operation_id":"op-example"}}
```

返回字段为 `ok`、`cancel_requested`、`operation`。operation 使用第 4.2 节完整记录，不使用另一套精简任务结构。

- 对 queued 任务，取消并移出队列，返回 cancelled，cancel_requested=true。
- 对 running 任务，设置取消标记并在下一个安全工作单元边界停止；响应可能仍为 running，cancel_requested=true。用户必须继续轮询到终态，不能以“已请求取消”宣称“已经停止”。
- 若任务已经处于终态，原样返回；cancelled 时 cancel_requested=true，succeeded/failed 时为 false。若完成与取消竞争，以实际冻结终态为准。
- 已修改部分保留，已完成的正常游戏副作用也不会撤回。取消可能发生在查询压缩期间，未发布结果直接丢弃。

例如取消已经被处理，三处修改保留，响应为：

```json
{
  "ok":true,"cancel_requested":true,
  "operation":{
    "operation_id":"op-example","request_id":"11111111-1111-4111-8111-111111111111","world_session_id":"s-example","dimension":"minecraft:overworld","tool":"fill_region","kind":"write","state":"cancelled",
    "counts":{"total":8,"processed":3,"succeeded":3,"changed":3,"unchanged":0,"skipped":0,"failed":0},
    "error_count":0,"created_at":"2026-11-05T12:00:00Z","started_tick":1204,"finished_tick":1205,"expires_at":"2026-11-05T12:05:01Z","result_available":false,"terminal_error":null
  }
}
```

### 6.3 `get_operation_errors`

**必需：** `world_session_id`、`operation_id`。**可选：** `cursor`，默认第一页。只在终态提供冻结错误列表；任务尚未终态返回 `OPERATION_NOT_READY`。零错误终态返回空数组和 next_cursor=null。

```json
{"name":"get_operation_errors","arguments":{"world_session_id":"s-example","operation_id":"op-batch"}}
```

```json
{
  "ok":true,"world_session_id":"s-example","operation_id":"op-batch","error_count":1,
  "errors":[{"index":0,"candidate_index":1,"pos":{"x":1,"y":64,"z":0},"code":"WRITE_REJECTED","message":"服务端拒绝该位置的状态更新。","retryable":false,"effect_unknown":false,"details":{}}],
  "next_cursor":null,"expires_at":"2026-11-05T12:05:01Z"
}
```

错误的 `index` 是从零开始的稳定错误序号；`candidate_index` 是区域线性索引或 batch 数组索引，非坐标级错误为 null；pos 同理。每条错误含 code/message/retryable/details，并独立给出 effect_unknown。无副作用不确定性时必须为 false。错误按记录顺序排列，禁止静默裁剪或只保留最后 N 条；容量不足必须在资源接纳阶段拒绝，不能接受后丢失已承诺错误记录。

### 6.4 `get_query_result`

**必需：** `world_session_id`、`operation_id`。**可选：** `cursor`，默认第一页。

仅 succeeded 的 read 任务可读。read 任务 queued/running 返回 `OPERATION_NOT_READY`；failed/cancelled 返回 `RESULT_UNAVAILABLE`；write 任务返回 `OPERATION_KIND_MISMATCH`。不能在失败查询上把已扫描部分伪装为完整结果。

```json
{"name":"get_query_result","arguments":{"world_session_id":"s-example","operation_id":"op-region"}}
```

```json
{
  "ok":true,"operation_id":"op-region","world_session_id":"s-example","dimension":"minecraft:overworld",
  "result_type":"region","consistency":"incremental","started_tick":1202,"finished_tick":1203,
  "min":{"x":0,"y":64,"z":0},"max":{"x":1,"y":64,"z":0},
  "encoding":"palette_rle","offset":0,"count":2,"total_voxels":2,
  "palette":[{"id":"minecraft:oak_log","properties":{"axis":"y"}}],
  "runs":[{"palette_index":0,"length":2}],
  "next_cursor":null,"expires_at":"2026-11-05T12:05:01Z"
}
```

若请求 encoding=palette_indices，上例将保留其余元数据、设置 encoding=palette_indices，并用 `"indices":[0,0]` 替代 runs。不会同时提供两种编码数组。auto 页也返回实际选择的 encoding。terrain 页形状见第 5.5 节；工具通过 result_type 区分两种结果。

后续页调用示例，cursor 必须来自前页而非手写：

```json
{"name":"get_query_result","arguments":{"world_session_id":"s-example","operation_id":"op-region","cursor":"cursor-returned-by-server"}}
```

## 7. 校验、错误分类和失败策略

### 7.1 typed error

工具失败的应用层 JSON：

```json
{"ok":false,"error":{"code":"CHUNK_NOT_LOADED","message":"目标区块未加载；本工具不会加载或生成区块。","retryable":false,"details":{"dimension":"minecraft:overworld","chunk_x":0,"chunk_z":0}}}
```

`code` 是稳定机器标识，message 供人阅读，不应作为程序分支条件；details 是按错误类型定义的有限 JSON 对象，不包含 Token、堆栈、存档绝对路径或内部敏感信息。retryable 只表示相同业务意图在满足前置条件后可能再次提交，不授权自动重做写任务，尤其不覆盖幂等保留期和未知副作用规则。

### 7.2 错误码

| code | 情况 | 默认 retryable / 处理 |
| --- | --- | --- |
| `UNAUTHORIZED` | Token 缺失或无效 | false；修复凭证 |
| `FORBIDDEN` | 写能力或目标范围被服务端策略禁止 | false；不能绕过策略 |
| `INVALID_ARGUMENT` | 缺失/多余属性、类型、UUID、范围顺序或枚举错误 | false；修正参数 |
| `UNKNOWN_TOOL` | 内部桥收到未注册工具名 | false；MCP 未知工具按 SDK 支持路径报告 |
| `INVALID_BLOCK_ID` | 目标、filter 或 expected_state 的方块 ID 未注册 | false |
| `INVALID_BLOCK_STATE` | 属性名/值不合法，或 expected_state 缺少完整属性 | false |
| `PRECONDITION_FAILED` | 执行时真实完整状态不等于 expected_state | false；不修改该坐标 |
| `DUPLICATE_POSITION` | batch 中出现重复坐标 | false |
| `OUT_OF_BOUNDS` | 高度或其他合法世界坐标越界 | false |
| `WORLD_NOT_READY` | 当前世界尚未可用 | true；重新 bootstrap |
| `WORLD_SESSION_MISMATCH` | 世界调用携带错误或旧 session | false；不得自动迁移原写意图 |
| `WORLD_SESSION_ENDED` | 任务执行期间所属世界结束 | false；任务终止 |
| `DIMENSION_NOT_FOUND` | 维度未注册或不可用 | false |
| `PLAYER_NOT_FOUND` | 指定玩家不在线 | false |
| `PLAYER_REQUIRED` | 未指定玩家且在线人数不为一，或明确选择存在歧义 | false；指定唯一 UUID 或账户名称 |
| `CHUNK_NOT_LOADED` | 提交校验或执行时目标区块未加载 | false；不得隐式加载或自动等待 |
| `LIMIT_EXCEEDED` | 体素、项数、区块数超限 | false；由调用者明确拆分 |
| `REQUEST_TOO_LARGE` | 请求超过 2 MiB | false |
| `QUEUE_FULL` | 全局等待队列已满 | true；尚未创建任务 |
| `RESOURCE_EXHAUSTED` | 无法预留结果/错误记录等有界资源 | true；尚未创建任务或已明确终止 |
| `IDEMPOTENCY_CONFLICT` | 相同幂等键对应不同规范化输入 | false |
| `OPERATION_NOT_FOUND` | 不存在、已过期、所属 session 或调用主体不匹配 | false；不泄露其他主体的任务 |
| `OPERATION_NOT_READY` | 数据尚未冻结可读 | true；轮询任务，不重提写任务 |
| `OPERATION_KIND_MISMATCH` | 对写任务请求查询结果 | false |
| `RESULT_UNAVAILABLE` | 查询失败或取消，无完整结果 | false |
| `RESULT_TOO_LARGE` | 单条结果不能适配页大小 | false；查询失败，不静默截断 |
| `INVALID_CURSOR` | 游标无效、不匹配或越界 | false |
| `WRITE_REJECTED` | 引擎/策略明确拒绝某一坐标写入且确认未发生该写入 | false；记录坐标错误 |
| `WRITE_OUTCOME_UNKNOWN` | 引擎异常导致无法确认是否已发生副作用 | false；停止任务，禁止自动重试 |
| `PARTIAL_FAILURE` | 已处理部分坐标但至少一个失败 | false；终态摘要，详情分页 |
| `SERVER_TICK_TIMEOUT` | 服务端在 15 秒内未处理内部请求；暂停或卡顿时可能已接受任务 | true；先用相同 request_id 查询/重试，再决定是否重报 |
| `BRIDGE_UNAVAILABLE` | MCP Server 无法连接 Mod 内部桥（MCP 层错误） | true；确认游戏与世界已启动 |
| `BRIDGE_RESPONSE_INVALID` | 内部桥返回无法解析的响应（MCP 层错误） | false；检查版本与日志 |
| `INTERNAL_ERROR` | 其他未预期内部错误 | false；对外不泄露异常堆栈 |

### 7.3 接受前与接受后的分界

JSON/schema、请求总量、区域体积、批量重复位置等纯输入检查在创建任务前完成，失败时没有 operation ID，也没有写入。世界相关的注册表状态解析、默认属性补全、当前 session/维度/实际世界边界/已加载区块检查必须在服务端线程执行。小规模检查可在接纳前完成；batch 状态预校验允许接受后进入 running 并按 tick 分批进行，但全部目标状态验证通过前绝不开始写入。此时预校验失败属于已接受任务失败，counts.changed=0，错误详情完整保存。

预检不是锁定承诺。排队后、每次实际接触目标位置前，仍检查 session、维度、实际世界边界和区块 loaded_only 条件；写入额外检查 expected_state。已接受任务之后遇到错误，按 on_error 和任务级强制终止规则处理并保存错误记录，而不是把未来某次无关的轮询变成原始 tools/call 错误。

提交在响应丢失时是否被接受可能不可知；MCP Server 不得因为 HTTP 超时就构造一个 failed operation，也不得自动使用新 request_id 重试。服务端尚未获得 HTTP 响应或客户端断开连接，不意味着任务被取消。

## 8. 内部 HTTP 桥的最小路由

### 8.1 认证和传输

- 仅监听 `127.0.0.1`，如明确启用 IPv6 则只监听 `::1`，禁止 `0.0.0.0` 或局域网地址。服务端配置端口，SDK Server 从显式配置读取地址。
- 所有路由，包括 info，都要求 `Authorization: Bearer <token>`。Token 不进 URL、日志、错误对象或 MCP 返回；不对网页开启通用 CORS。
- 请求与响应采用 UTF-8 JSON；有请求体的路由要求 `Content-Type: application/json`。本契约不接受压缩请求体；所有大小限制以实际字节为准。
- `/v1` 是内部契约版本，不是 MCP 版本。内部 HTTP 适配层负责转换应用层对象，不能把 `/v1/operations` 描述成 MCP Streamable HTTP 端点。

### 8.2 完整路由表

| 方法与路径 | 输入 | 对应行为 |
| --- | --- | --- |
| `GET /v1/info` | 无 body、无 query | `get_world_info` |
| `POST /v1/operations` | `{ "tool": string, "arguments": object }` | 接受除 bootstrap 与四个管理工具之外的九个世界工具；小读返回同步值，大查询/写入返回统一异步任务 |
| `GET /v1/operations/{operation_id}?world_session_id=...` | 必需 world_session_id；无 body | `get_operation` |
| `POST /v1/operations/{operation_id}/cancel` | `{ "world_session_id": string }` | `cancel_operation` |
| `GET /v1/operations/{operation_id}/errors?world_session_id=...&cursor=...` | 必需 world_session_id，可选 cursor | `get_operation_errors` |
| `GET /v1/operations/{operation_id}/result?world_session_id=...&cursor=...` | 必需 world_session_id，可选 cursor | `get_query_result` |

所有 query 参数都只能出现一次，未知参数拒绝；字符串 URL 编码。路径 operation_id 与参数不能互相覆盖。异步 request_id 属于 arguments，不另设第二个幂等 header。工具管理输入通过适配层映射到路径/query/body，应用层返回 JSON 保持一致。

提交示例：

```http
POST /v1/operations HTTP/1.1
Host: 127.0.0.1:配置端口
Authorization: Bearer <由配置提供的令牌>
Content-Type: application/json

{"tool":"set_block","arguments":{"world_session_id":"s-example","dimension":"minecraft:overworld","pos":{"x":0,"y":64,"z":0},"block_state":{"id":"minecraft:stone"},"request_id":"99999999-9999-4999-8999-999999999999"}}
```

新异步任务接受成功返回 HTTP 202 和完整任务信封；幂等重放、同步查询、任务读取、取消请求及分页成功返回 HTTP 200。重复提交返回 200 不意味着原任务已完成，必须读取 state。

### 8.3 HTTP 与工具错误映射

| HTTP 状态 | 应用情况 |
| --- | --- |
| 400 | 参数、状态、范围、重复坐标、非法游标、未知工具或无效 JSON |
| 401 / 403 | 身份认证失败 / 权限或目标策略拒绝 |
| 404 | 未知路由、维度/玩家/任务不存在；错误体继续使用具体 code |
| 409 | session 不匹配、区块未加载、幂等冲突、任务未就绪、任务种类不匹配或结果不可用 |
| 413 | 请求体或声明工作量超限 |
| 429 | 队列满或接纳阶段资源不足 |
| 503 | 世界尚未就绪 |
| 500 | 同步内部故障 |

HTTP 错误体使用第 7.1 节 typed error，不能只返回 HTML。异常失联、连接拒绝或无法解析错误体属于 MCP Server 与桥之间的传输失败：MCP Server 返回 `BRIDGE_UNAVAILABLE`（retryable=true）或 `BRIDGE_RESPONSE_INVALID`（retryable=false）工具错误，details 仅说明是否已发送请求、是否无法确认任务接纳，不猜测任务结果。它们不由 Fabric 世界任务记录生成。

任务 failed 时读取仍返回 HTTP 200，且 MCP `isError=false`。内部 HTTP 200/202、MCP 传输成功、工具调用成功、任务成功是不同层级，不能混用。

## 9. 客户端必须遵守的操作流程

1. 调用 get_world_info，确认 ready、显式选择 session 与维度；玩家可使用 UUID 或账户名称，只有恰好一名在线玩家时才允许省略 player，其他目标信息不猜测。
2. 在发起写入前形成明确坐标、完整状态、修改范围和副作用说明；确认权限依据 [Skill 设计](skills-design.md)，本文不把任意网页或世界文本视为操作授权。
3. 为一个逻辑异步动作生成一个 request_id，记录原始参数；严格遵守请求体、体素、区块和批量限制，超限拆分必须由调用者显式规划。
4. 提交后保存 operation_id；响应丢失只在幂等保留语义下重放同一请求，不自动改键。
5. 轮询到终态；取消请求也必须轮询到终态。failed/cancelled 不能被摘要成“未执行”。
6. 读取最终 counts 和全部错误页；查询仅在 succeeded 时读取全部冻结结果页。页过期后不可将旧页与新查询页混拼成同一结果。
7. 需要验证真实世界最终状态时发起新的显式查询，使用新 request_id；不要把任务成功等同于此后世界永远不变。实际 E2E 结果见 [验证报告](verification-report.md)。

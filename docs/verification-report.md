# 验证报告

初始验收日期：2026-11-05（CST）；建造过程可视化回归：2026-11-06（CST），见第 8 节。第 1—7 节保留 1.0.0 的历史结果（尤其 256/Tick 吞吐），不能当作当前逐块模式的性能数据。本报告只记录实际执行过的检查，未验证项不从其他通过项推断。

## 1. 环境

| 项目 | 实际值 |
| --- | --- |
| OS | Linux 7.3.1-1-cachyos x86_64 |
| JDK | OpenJDK 27.0.1.0.3（编译目标 Java 25） |
| Gradle | 9.8.2（仓库 Wrapper） |
| Fabric Loom | 1.19.3 |
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.2+26.3 |
| Node.js | 26.12.1 |
| MCP SDK | 1.32.2 |
| 测试服务端 | dedicated，offline-mode，flat world，force-loaded 测试区块；本地 loopback |
| 测试客户端 | Minecraft 26.3 + Fabric，离线用户名 `DevBuilder`，连接 dedicated 与 integrated 世界 |

测试世界不是用户生产存档。dedicated 测试服使用用户明确授权的本地 EULA；测试结束后凭据文件已删除，下一次启动会重新生成 Token。

## 2. 构建与自动化测试

| 命令 | 结果 |
| --- | --- |
| `cd fabric-mod && ./gradlew test build` | BUILD SUCCESSFUL；10 个 JUnit 测试，0 失败；JAR `build/libs/minecraft-mcp-1.0.0.jar` |
| `cd mcp-server && npm test` | 5 个测试通过；其中 1 个使用官方 SDK Client 经真实 stdio 完成初始化、`tools/list`、`tools/call` 和错误路径 |
| `npm run build` | TypeScript 编译成功 |

Java 测试覆盖：区域坐标展开、palette/RLE 无损分页（含 65,536 个唯一状态的多页案例）、签名游标、计数不变量、地形空气/流体/碰撞/支撑/截断语义、无事实列。MCP 测试覆盖：14 个工具与 Schema、写工具注解、限制/枚举、stdio 发现与转发、未知工具。

## 3. dedicated 真实世界验收

### 3.1 单方块与状态

- 读取 `(0,-64,0)`：真实 `minecraft:bedrock`。
- 在 `(0,-60,0)` 放置 `minecraft:oak_stairs {facing:east}`，回读完整状态为 `facing=east, half=bottom, shape=straight, waterlogged=false`。
- `remove_block` 后回读 `minecraft:air`。
- `get_block` 返回独立 `is_air`、`fluid`、`has_collision` 和 `observed_tick`。

### 3.2 区域读取与无损还原

- 小型区域 8×8×8（512 体素）：解码后逐点与真实 `get_block` 比较，**0 差异**。
- 最大查询 64×1×16×64 = 65,536 体素：成功；1 页；页大小 38,298 字节；调色板 12 项；耗时 3,276 ms。
- 多页编码由 Java 单元测试覆盖：65,536 个唯一状态被拆成多页，每页不超过 512 KiB，逐页解码总数正确。

### 3.3 地形

- 真实 96×96 列、5 层扫描（46,080 体素）：9,216 列，19 页，9,448,502 字节，5,610 ms。
- 真实水方块：`highest_fluid` 返回 `water level=0 is_source=true`；`fluid_intervals` 与空气区间分离；碰撞区间为下方草方块。
- 单元测试覆盖空列、纯液体、含水/碰撞重叠、树冠、不完整顶面、净空不足与边界截断。
- 真实玩家附近区域 1,089 列中 676 列被识别为 `support_ok` 的草方块支撑候选。

### 3.4 写入、规模与错误

- `fill_region` 100 个方块：`changed=100`，回读正确。
- `replace_blocks` 50 个匹配方块：`changed=50`，未匹配区域保留原状态。
- `batch_set_blocks` 6 个墙体/状态各异方块：全部成功并逐点回读。
- batch 第二项非法状态：任务 `failed`，`changed=0`，错误定位到第二项，第一项**未被写入**。
- `on_error=continue`：第一项写入成功、第二项 `PRECONDITION_FAILED`；终态 `failed`，`changed=1,failed=1`，错误含 expected/actual。
- 重叠坐标 batch：`DUPLICATE_POSITION`。
- 超界 Y、未加载区块、非法方块 ID、非法属性、错误 session、超大区域、不存在任务、未知工具：MCP 层均返回对应 typed error（`OUT_OF_BOUNDS`、`CHUNK_NOT_LOADED`、`INVALID_BLOCK_ID`、`INVALID_BLOCK_STATE`、`WORLD_SESSION_MISMATCH`、`LIMIT_EXCEEDED`、`OPERATION_NOT_FOUND`、`UNKNOWN_TOOL`）。
- 取消 72,000 方块填充：终态 `cancelled`，已处理 1,536，修改保留，未回滚；读取确认已写入方块为 `minecraft:stone`。
- 96,000 方块填充：18.98 s 完成；`changed=95,999`、`unchanged=1`。按 256 个/ Tick 推进，约 375 Tick，对应约 20 TPS。
- 调度器自测：200/400 个工作 Tick 时记录 `avg_work_ms=0.53`、`max_work_ms=3.43`、`last_writes=256`；服务端未出现卡死或看门狗终止。
  - 说明：2 ms 是主动让出预算，单次原版引擎调用不能被硬中断；实测最大调度工作 3.43 ms，仍远低于 50 ms Tick 预算。

### 3.5 两层房屋闭环

最终 clean build 后的 MCP E2E（`test/e2e.mjs`）：

- 施工 9 个异步操作，总计 1,540 个受检方块。
- 首次逐坐标验证：**0 差异**。
- 注入 2 个缺陷（楼梯朝向/半部错误、玻璃缺失）后重读：准确检出 **2 个差异**。
- 只提交两个差异坐标修复；再次完整重读：**0 差异**。
- 房屋包含地基、两层主体、楼板、屋顶、玻璃窗、门、室内空气和可通行楼梯。
- 同一会话的最终复跑结果一致：1,540 个方块、0 差异、注入 2 个缺陷均被检出、修复后 0 差异。

### 3.6 真实玩家

- 真实 Minecraft 图形客户端加入 dedicated 服，玩家 `DevBuilder`。
- `get_player_position` 返回真实位置 `(1.5,-60,3.5)`、方块位置 `(1,-60,3)`、维度 `minecraft:overworld`、`yaw=0`。
- 由玩家位置推导建筑起点，读取附近地形并执行 891 方块的玩家旁小屋施工；完整区域重读 **0 差异**。
- 无玩家时同一工具返回 `PLAYER_REQUIRED`（而不是随机选人）。

### 3.7 保存与重启

- 写入两层房屋并正常停止服务端。
- 重启后获得**新的** `world_session_id`；`(21,-58,10)` 仍为玻璃，`(19,-57,12)` 仍为正确朝向的楼梯。
- 证明写操作进入正常区块保存路径，而非仅存在于内存。

## 4. integrated 服务端验收

使用真实 Minecraft 26.3 客户端 + Fabric 载入本 Mod，打开复制的单人世界：

- Mod 在客户端加载（`environment: "*"`），进入世界后 integrated server 启动，bridge 在 `config/minecraft-mcp.credentials.json` 生成凭据。
- 当前 session 为独立值；`get_player_position` 返回 integrated 世界中的真实玩家与位置。
- `get_block` 读到真实 `minecraft:bedrock`。
- `get_terrain` 扫描 8,125 体素成功。
- 玩家旁 5×5 小屋施工与完整区域重读：**0 差异**。
- 再次用玩家位置推导 891 方块小屋并验证：**0 差异**。

integrated 世界的保存/退出后重启未单独测试；dedicated 持久化已测试。

## 5. MCP 协议与安全

- 官方 SDK Client 经 stdio 初始化成功，发现 14 个工具，调用 `get_world_info`、`get_block`、`get_region`。
- 工具错误以 `isError=true` 返回 typed error；异步任务的失败状态在成功轮询时仍为 `isError=false`。
- 缺失 Token：HTTP 401 `UNAUTHORIZED`。
- 伪造 Host：HTTP 403 `FORBIDDEN`。
- 浏览器 Origin：HTTP 403 `FORBIDDEN`。
- bridge 仅绑定 `127.0.0.1`；凭据文件权限为 `0600`（Linux）。

## 6. 未验证或受限项

以下项目**没有**在当前环境完成验证，不应视为通过：

1. 公开互联网/局域网监听、TLS、OAuth、多用户授权；当前只支持 loopback。
2. MCP Streamable HTTP 传输；当前只有 stdio。
3. Windows、macOS 和不同 JDK/Minecraft 版本组合。
4. online-mode=true 与真实 Microsoft 登录的 dedicated 服；测试使用 offline-mode。
5. 长时间（小时级）运行、GC、内存泄漏和极端并发压力；当前最长单次负载约 19 秒。
6. 扫描过程中区块被卸载的真实竞态；admission 阶段未加载区块已测试，代码在每批执行前复检。
7. `RESULT_TOO_LARGE` 的真实单列触发、错误分页超过一页的真实案例；相关分页/边界由代码和单元测试覆盖，但未用真实世界构造。
8. 真实 LLM Agent 的规划质量；验收使用官方 SDK 的自动化 MCP Client，执行与 Agent 相同的工具调用闭环。工具发现、调用、建造、重读和修复链路已真实通过，但未运行具体 LLM 供应商。
9. 任意 NBT/容器/实体/命令；这些明确不在当前工具范围。
10. integrated 世界退出后再次进入的持久化；dedicated 已做完整保存重启验证。

## 7. 复现入口

```bash
# Java
cd fabric-mod && ./gradlew test build

# MCP 单元与协议
cd mcp-server && npm ci && npm test

# 真实世界（先启动带 Mod 的世界）
cd mcp-server
export MINECRAFT_MCP_CONFIG=/absolute/path/to/minecraft-mcp.credentials.json
node test/e2e.mjs          # 两层房屋、重读、缺陷、修复
node test/negative.mjs     # MCP 层错误
node test/region_large.mjs # 65,536 体素
node test/terrain_large.mjs
node test/cancel.mjs
node test/player_house.mjs # 需要真实玩家在线
node test/integrated.mjs   # 需要 integrated 世界
MINECRAFT_VISUAL_Y=190 node test/visualization.mjs # 需 x=24..35,z=8..12,y=190..200 对应夹具位置为空气
```

`get_world_info` 的 `world_session_id` 每个世界生命周期都会变化；重启或切换世界后必须重新获取。所有限制以该工具返回的 `limits` 为准。

## 8. 建造过程可视化回归 — 2026-11-06

### 8.1 构建与执行环境

- 沿用 Minecraft 26.3 / Loader 0.20.1 / Fabric API 0.165.2+26.3 / Java 27 / Node 26.12.1；没有新增前置 Mod。
- `cd fabric-mod && ./gradlew test build`：通过，14 个 JUnit 测试、0 失败。新增测试覆盖完整方向/半部 BlockState 与负坐标编解码、128 项页边界、声明总量/偏移冲突、超大 wire count 在读取条目前拒绝、反向/溢出范围。
- `cd mcp-server && npm test`：5 个 schema/SDK stdio 协议测试通过；14 个工具不变。
- 使用最终 JAR 安装在 `integration/client/mods/`，以移除 `build/classes` 和 `build/resources` 的启动 classpath 加载 Mod；dedicated 同样从构建 JAR 加载。两端均需安装 Fabric API；开发 classpath 中可发现 API 不代表普通安装已包含该依赖。
- 客户端实际使用 Vulkan / NVIDIA RTX 5060 Laptop GPU。当前电脑原生 computer 捕获通道返回 `CaptureFailed: Wayland capture requires the wayland-pipewire feature`，因此用 XWayland 游戏窗口的 FFmpeg 捕获做视觉验收，不以截图失败当作功能通过。
- 本机 OpenGL 初始化失败后游戏自身成功选择 Vulkan；缺少 flite narrator 和离线测试账户的 Realms/账户服务 401 不影响本地世界。没有自动登录、接受 EULA 或修改授权设置。

### 8.2 真实 dedicated + MCP 行为

| 场景 | 实际结果 |
| --- | --- |
| 混合 batch（石头、玻璃、楼梯、台阶、安山岩、砖） | 6 个目标，先 processed=0 预览，25 Tick 终结，changed=6；完整状态回读一致 |
| 600 项末项非法 | failed，changed=0、succeeded=0、failed=1、processed=1，目标位置仍为空气 |
| 64 项 batch 中途取消 | cancelled，changed=1，读回恰好为第一个已写坐标，其余 63 个仍为空气 |
| expected_state 失配 | failed，changed=0、failed=1；目标仍为空气 |
| 同状态重复设置 | succeeded，changed=0、unchanged=1 |
| 区域/地形读取 | 6 体素区域完整解码，9 列地形正常分页读取 |
| 完整房屋 E2E | 9 个施工操作，施工约 113.393 秒；1540 坐标首次 0 差异，注入后精确检出 2 差异，修复后 0 差异 |
| typed error 回归 | negative.mjs 的越界、未加载、非法 ID/状态、超大查询、旧 session、未知工具、重复坐标、不存在 operation 全部符合预期 |

复现命令：

```bash
cd mcp-server
export MINECRAFT_MCP_CONFIG=/absolute/path/to/fabric-mod/run/config/minecraft-mcp.credentials.json
MINECRAFT_VISUAL_Y=190 node test/visualization.mjs
node test/e2e.mjs
node test/negative.mjs
```

可视化 E2E 会先拒绝非空气夹具，而不是清掉已有结构；重复运行需选空闲 Y 或使用重新准备的专用测试世界。一次与房屋 E2E 并行时，取消用例的旧 15 秒等待上限误将仍 queued 的任务当作失败；已改为 120 秒有界等待并在失败时取消遗留任务，随后在空闲 Y=190 完整通过（39.87 秒）。没有以该失败运行充当通过证据。

基线缺陷复现：旧实现的 600 项末项非法 batch 已提前 changed=12，且返回 processed=1、succeeded=12 的矛盾计数。该测试写入的夹具已恢复。修复后的上述用例零写入且计数不变量成立。

### 8.3 实际画面、生命周期与保存

- [`build-process.mp4`](media/build-process.mp4)：9 秒实机窗口录像，含 120 个石头/玻璃/完整朝向楼梯目标的半透明预览、0→120 真实进度、逐块实建和终态清除。该任务 139 Tick 成功、changed=120，随后 120 坐标完整状态重读 **0 差异**。
- 实际截图确认 `get_region` 青色选区与请求包含边界相同、短暂淡出；`get_terrain` 在下界也显示正确扫描框。在下界观察者执行主世界区域查询时没有出现主世界选框。换维度/重新连接后未出现旧世界模型。
- chest、water、ender_chest、barrier、structure_void 的特殊目标在客户端显示实际形状的半透明光体；预览期间取消后 changed=0，五个目标回读全部仍为空气。没有实际放置流体引入测试区外的流体更新。
- dedicated 正常保存/停止后换新 session 重启，从安装包读回 `(24,147,8)` 仍为正确石头；不会把预览模型保存成方块。
- 最终 JAR 在 `TestWorld` 真实集成服务端启动成功；地形查询 360 体素成功，12 个混合目标先显示 processed=0 的半透明预览，在 31 Tick 后 changed=12、完整状态逐坐标回读 **0 差异**。
- 集成世界的非法方块 ID 返回 `INVALID_BLOCK_ID`，实际客户端 HUD 显示红色 `failed INVALID_BLOCK_ID`；验证了 HTTP 线程只记录错误通知、下一服务端 Tick 再发送的路径。
- 集成测试创建的 12 个方块用完整 expected_state 条件批量恢复，changed=12，最后逐坐标回读全部空气；没有清空原有世界结构。

### 8.4 性能与仍未验证项

- 安装包 dedicated 调度器记录 200—2200 个有工作 Tick：平均工作时间从 0.17 ms 到 0.08 ms，记录的最大值 6.45 ms，执行阶段 `last_writes=1`。2 ms 仍是主动让出预算而非单次引擎调用的硬上限。
- 当前模式是为了逐块过程可见，不能沿用历史 96,000 方块 / 18.98 秒的批量吞吐结论。未对当前模式重新执行 96,000/262,144 个候选的长时完整写入。
- 没有测量客户端帧率分位数、8192 项全部可见模型的极限负载、第三方 shader/资源包/模组渲染兼容、无 Mod 原版客户端的自定义包协商、长时断线/暂停竞态。普通客户端只会看到真实世界更新，不宣称有自定义预览。
- 集成世界本次完成了启动、真实画面、读写、错误提示和夹具恢复；没有另外进行集成存档保存后再启动的持久化验收，dedicated 持久化已单独验证。

## 9. 1.1.0 发布包安装验收 — 2026-11-06

- Mod `gradle.properties`、npm package/lockfile、MCP 握手版本与安装脚本同步为 **1.1.0**；工具参数、协议和游戏依赖版本未改变。
- 版本更新后再次执行 `cd fabric-mod && ./gradlew test build`、`cd mcp-server && npm test`，均通过（14 个 Java / 5 个 MCP 测试）；`bash -n packaging/install.sh` 通过。
- 从独立暂存目录 `npm pack`，MCP tarball 包含构建输出、README、package.json 和原仓库 LICENSE，不含凭据、存档、node_modules 或开发运行实例。独立 Skill ZIP 保留完整建造 Skill 与 references。
- 在隔离的 npm prefix、SKILLS_DIR、MODS_DIR 中实际运行完整包的 `./install.sh`：依赖安装成功，生成可执行 MCP 命令，Skill 和 `minecraft-mcp-1.1.0.jar` 安装成功；未修改用户真实全局安装、Skill 或游戏实例。
- 从安装脚本复制出的 **1.1.0 JAR** 启动真实 dedicated 世界；Fabric 日志显示 `minecraft-mcp 1.1.0`。JAR 元数据包含正确版本及服务端/客户端两个入口。
- 使用安装后的 `minecraft-mcp-server` 可执行文件，经官方 SDK stdio Client 握手取得 `{name:\"minecraft-mcp\",version:\"1.1.0\"}`，发现 14 个工具；真实世界返回每 Tick 一个写入候选。
- 在已加载的专用测试区域 `(24..29,210,8)` 提交 6 个石头/玻璃/完整楼梯状态目标：25 Tick 终态成功、changed=6、逐坐标完整状态 **0 差异**。随后以 expected_state 条件恢复这 6 个测试方块，终态成功，逐点回读全部空气。
- 本次版本化重新验收聚焦实际安装产物和协议/世界闭环；图形效果与 dedicated/integrated 的完整功能证据沿用第 8 节的同一实现，不把版本号更新称作新一轮图形负载或持久化测试。

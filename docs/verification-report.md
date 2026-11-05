# 验证报告

执行日期：2026-11-05（CST）。本报告只记录实际执行过的检查。未执行的场景在末尾单列，不能从其他通过项推断其通过。

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
```

`get_world_info` 的 `world_session_id` 每个世界生命周期都会变化；重启或切换世界后必须重新获取。所有限制以该工具返回的 `limits` 为准。

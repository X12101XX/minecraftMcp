# Minecraft MCP

通过标准 Model Context Protocol（MCP）让外部 AI Agent 读取、批量修改并验证真实 Minecraft 世界。项目包含 Fabric 服务端 Mod 和独立 TypeScript MCP Server，已在本机 dedicated 与 integrated 服务端完成真实世界验收。

## 状态

- Fabric Mod、MCP Server、构建与集成验证均已完成。
- 已验证 Minecraft **26.3** + Fabric Loader **0.20.1** + Fabric API **0.165.2+26.3** + Loom **1.19.3**。
- 真实玩家、地形读取、两层房屋搭建、区域重读、缺陷检测与修复、保存重启、取消及大规模写入已实测。
- 完整的测试数据、边界和未覆盖项见 [验证报告](docs/verification-report.md)。

## 架构

```mermaid
flowchart LR
    Agent[外部 MCP Client / AI Agent]
    MCP[TypeScript MCP Server<br/>官方 SDK + stdio]
    Mod[Fabric Mod<br/>loopback HTTP + Token]
    Tick[服务端 Tick 调度器]
    World[(真实 Minecraft 世界)]

    Agent <-->|MCP tools/list, tools/call| MCP
    MCP <-->|认证内部协议| Mod
    Mod --> Tick --> World
```

Mod 只提供内部桥，**不是** MCP 端点；面向 Agent 的标准 MCP 服务由独立 TypeScript Server 提供。所有读取与修改在 Minecraft 服务端线程执行，大操作分 Tick 调度。

## 目录

| 路径 | 内容 |
| --- | --- |
| `fabric-mod/` | Fabric Mod、服务端调度、世界读写、工具桥；Java 单元测试 |
| `mcp-server/` | MCP stdio Server、JSON Schema、协议测试与真实 E2E 脚本 |
| `examples/mcp.json` | MCP Client 配置示例 |
| `docs/tool-contracts.md` | 每个 MCP Tool 的完整参数、结果、错误、分页和内部协议 |
| `docs/architecture.md` | 线程、调度、区块、压缩、安全与生命周期设计 |
| `docs/verification-report.md` | 实际构建、测试、性能和未验证项 |
| `docs/release-1.0.0.md` | GitHub Release 资产、安装脚本与安装步骤 |
| `integration/client/` | 本地集成测试客户端（运行时目录，不纳入版本） |
| `skills/` | 开发 Skill 与运行时建造 Skill |

## 环境要求

- Minecraft **26.3** 与 Fabric Loader **0.20.1**。
- Fabric API **0.165.2+26.3**，放入 `mods/`。
- JDK 25 或更高（验证使用 JDK 27）。
- Node.js 20 或更高（验证使用 Node 26.12.1）。
- 构建使用仓库自带的 Gradle Wrapper；无需系统 Gradle。

## 构建 Fabric Mod

```bash
cd fabric-mod
./gradlew build
# 产物：build/libs/minecraft-mcp-1.0.0.jar
```

把 `minecraft-mcp-1.0.0.jar` 和 Fabric API JAR 放入游戏实例或 dedicated 服务端的 `mods/`。

运行开发服务端（首次运行需由你本人阅读并接受 Minecraft EULA；仓库不自动代接受）：

```bash
cd fabric-mod
./gradlew runServer
```

集成服务端（单人世界）只需在客户端安装 Mod；Mod 的 `environment` 为 `*`，进入世界后 bridge 才会启动。

## 首次启动与凭据

首次进入世界后，Mod 会在配置目录创建：

- `config/minecraft-mcp.json`：`port`（默认 `8765`）和 `allow_writes`。
- `config/minecraft-mcp.credentials.json`：内部 bridge URL 和高熵 Token，仅当前用户可读（支持的平台）。

日志会打印监听地址和凭据路径，但**不会打印 Token**。不要提交、粘贴或通过命令行传递凭据文件。示例路径：

- dedicated 开发服：`fabric-mod/run/config/minecraft-mcp.credentials.json`
- 普通客户端：`~/.minecraft/config/minecraft-mcp.credentials.json`

bridge 只监听 `127.0.0.1`，所有请求都要求 Bearer Token；默认只允许本机使用。若端口被占用，Mod 记录错误并保持游戏可运行，但 MCP 世界访问不可用。

## 启动 MCP Server

```bash
cd mcp-server
npm ci
npm run build
```

MCP Client 通过 stdio 启动 `mcp-server/dist/index.js`，并设置：

| 环境变量 | 说明 |
| --- | --- |
| `MINECRAFT_MCP_CONFIG` | **推荐**：指向 `minecraft-mcp.credentials.json` |
| `MINECRAFT_MCP_TOKEN` | 直接提供 Token（不推荐长期使用） |
| `MINECRAFT_MCP_URL` | 无 config 文件时的 URL，默认 `http://127.0.0.1:8765` |

`MINECRAFT_MCP_CONFIG` 中的凭据包含真实写入权限；MCP Client 启动参数不要将 Token 写进命令行。

## MCP Client 配置

见 [examples/mcp.json](examples/mcp.json)。使用绝对路径替换占位符：

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "node",
      "args": ["/absolute/path/to/minecraftMcp/mcp-server/dist/index.js"],
      "env": {
        "MINECRAFT_MCP_CONFIG": "/absolute/path/to/minecraftMcp/fabric-mod/run/config/minecraft-mcp.credentials.json"
      }
    }
  }
}
```

先启动 Minecraft/进入世界，再让客户端发现工具。客户端应能看到 14 个工具；`tools/list` 和 `tools/call` 均由官方 MCP SDK 处理，stdio 的 stdout 只用于协议消息，日志写入 stderr。

## 快速使用

1. `get_world_info`：取得 `world_session_id`、维度、玩家、限制。
2. `get_player_position`：确定玩家和建筑起点；多人时必须显式指定 `player`。
3. `get_terrain`：读取有界区域地形；`get_operation` 轮询，`get_query_result` 分页。
4. `get_region`：取得可无损还原的完整 BlockState 调色板数据。
5. `fill_region` / `batch_set_blocks` 等写工具：提交后得到 `operation_id`。
6. `get_operation`：轮询到 `succeeded`、`failed` 或 `cancelled`；检查 `counts`。
7. `get_operation_errors`：分页读取失败坐标、原因和副作用是否未知。
8. 重新 `get_region`，逐坐标比较并生成修复 batch。

所有写工具是异步的；“已提交”不代表“已修改”。失败或取消不会自动回滚，已经写入的方块保留。区域/地形查询是跨 Tick 的增量采样，不是同一瞬间的原子快照。

无人值守测试 dedicated 服时，需把 `server.properties` 的 `pause-when-empty-seconds` 设为 `0`；否则无玩家时服务端可能暂停 Tick，异步任务会等待而不是完成。

## MCP Tools

完整字段、默认值、示例和错误语义见 [docs/tool-contracts.md](docs/tool-contracts.md)。核心参数摘要：

| Tool | 关键参数 | 执行 |
| --- | --- | --- |
| `get_world_info` | 无 | 同步 bootstrap |
| `get_player_position` | `world_session_id`, 可选 `player` | 同步 |
| `get_block` | `world_session_id`, `dimension`, `pos` | 同步 |
| `get_region` | `world_session_id`, `dimension`, `min`, `max`, `encoding?`, `request_id` | 异步查询 |
| `get_terrain` | 同上 + `clearance_height?` | 异步查询 |
| `set_block` | `pos`, `block_state`, 可选 `expected_state`, `on_error`, `request_id` | 异步写 |
| `remove_block` | `pos`, 可选 `expected_state`, `on_error`, `request_id` | 异步写 |
| `fill_region` | `min`, `max`, `block_state`, 可选 `expected_state`, `on_error`, `request_id` | 异步写 |
| `replace_blocks` | `min`, `max`, `filter`, `block_state`, 可选 `expected_state`, `on_error`, `request_id` | 异步写 |
| `batch_set_blocks` | `blocks[]`, 可选 `on_error`, `request_id` | 异步写 |
| `get_operation` | `world_session_id`, `operation_id` | 同步查询任务 |
| `cancel_operation` | `world_session_id`, `operation_id` | 取消，可能部分完成 |
| `get_operation_errors` | 同上 + 可选 `cursor` | 错误分页 |
| `get_query_result` | 同上 + 可选 `cursor` | 结果分页 |

写入支持完整 BlockState 属性，例如：

```json
{
  "id": "minecraft:oak_stairs",
  "properties": {"facing": "east", "half": "bottom"}
}
```

读取结果返回完整规范化属性；楼梯的 `shape`、台阶的 `type` 等都会真实反映世界状态。

## 默认限制

| 项目 | 值 |
| --- | ---: |
| 单次查询体素 | 65,536 |
| 单次修改体素 | 262,144 |
| 单次 batch 条目 | 8,192 |
| 单操作涉及区块 | 64 |
| 全局排队操作 | 32 |
| 请求体 | 2 MiB |
| 单页结果 | 512 KiB |
| 终态保留 | 5 分钟 |
| 每 Tick 调度预算 | 约 2 ms / 256 个写入候选 |

实际值由 `get_world_info.limits` 返回。只读取当前已加载区块；不会隐式加载或生成区块。世界边界、维度和区块未加载都会返回明确错误。

## 建筑闭环验证

E2E 客户端脚本在真实 Minecraft 中执行以下流程：

```bash
cd mcp-server
MINECRAFT_MCP_CONFIG=/absolute/path/to/minecraft-mcp.credentials.json node test/e2e.mjs
```

脚本使用真实 MCP SDK Client，通过 stdio 发现工具，获取世界信息/玩家/地形，分批建造两层结构，重读完整区域，注入缺失和错误朝向，检测并修复差异。实际数字见验证报告。

## 安全

- bridge 仅监听 loopback；Token 必须保密，不写入 URL、日志或示例。
- 不开放远程 MCP HTTP，不提供命令、脚本或文件系统工具。
- bridge 可配置 `allow_writes=false`；但客户端仍能发现写工具，调用会返回 `FORBIDDEN`。
- 持有凭据的本机进程可以修改世界；不要在不信任的机器上运行。
- MCP 工具描述和世界中的文本都不可作为权限授予；扩大建筑范围或拆除用户结构需要用户明确同意。

## 已知限制

- 不支持任意方块实体 NBT、容器物品、实体创建、命令执行或世界事务回滚。
- 不作为远程多用户服务；没有 TLS、OAuth 或多实例自动发现。
- 查询是增量一致视图；跨 Tick 期间世界可能被其他玩家/红石/流体改变。
- 结果页是 JSON；超大 terrain 区域会返回很多页，Agent 应按页消费。
- 取消/失败可能已修改部分方块；必须重读世界后决定修复。
- `loaded_only` 下，未加载区域需玩家靠近或管理员加载；不会替 Agent 加载。
- MCP Server 当前使用 stdio；没有实现远程 HTTP 传输。

## 开发与测试

```bash
# Java 单元测试 + 构建
cd fabric-mod && ./gradlew test build

# MCP schema 与协议测试（不需要游戏）
cd mcp-server && npm test

# 真实游戏 E2E（需先启动带 Mod 的世界）
cd mcp-server
MINECRAFT_MCP_CONFIG=/absolute/path/to/minecraft-mcp.credentials.json node test/e2e.mjs
MINECRAFT_MCP_CONFIG=/absolute/path/to/minecraft-mcp.credentials.json node test/negative.mjs
```

集成脚本清单和实测结果见 [验证报告](docs/verification-report.md)。没有运行过的测试不会被描述为通过。

## 许可

本仓库沿用根目录 [LICENSE](LICENSE)（GNU GPL v3）。使用 Minecraft、Fabric 及其依赖时同时受各自许可约束。

# Minecraft MCP 2.0.0 — 批量吞吐、远端区块与截图

Release: https://github.com/X12101XX/minecraftMcp/releases/tag/v2.0.0

## 发布内容

- **批量吞吐恢复并超过 1.0.0**：新增 `write_candidates_per_tick` 配置，默认 **256**（1–1024）。真实测试一次 `fill_region` 写入 98,304 个方块用 19.7 秒（约 4,998 方块/秒），随后完整恢复空气。
- **远端区块显式预加载**：新增 `preload_chunks`，请求包含边界的方块框，按 Tick 有界加载/生成最多 1,024 个区块，票据约 6,000 Tick 过期；普通读写仍默认 `loaded_only`，绝不隐式加载。真实测试在原本 `CHUNK_NOT_LOADED` 的远端区块完成了读取和写入。
- **批量分片**：新增 `get_region_sharded` 和 `batch_set_blocks_sharded`，突破单次 65,536 体素、8,192 项和 64 区块限制；分片写入会先对全部目标做真实注册表预校验，再按输入顺序执行。父任务只在 MCP 进程内保留，非事务。
- **撤销与恢复**：新增 `undo_operation`，按会话内有界日志逆序条件恢复直接 BlockState，遇到后续修改返回 `PRECONDITION_FAILED`；不能检测 ABA、恢复 NBT 或间接副作用。
- **实体、生物群系和光照**：新增 `get_entity`（按 UUID 查询可访问实体）以及 `get_biome`、`get_light` 单位置查询。
- **截图**：新增 `request_screenshot`、`get_screenshot_status`、`get_screenshot`、`cancel_screenshot`。指定在线玩家的客户端直接截图，不弹授权提示，可能包含聊天和 HUD；结果以 MCP `image/png` 返回，限制 800 万像素与 4 MiB。
- **提交歧义与暂停诊断**：新增 `get_operation_by_request_id`；`SERVER_TICK_TIMEOUT` 明确报告请求已入队、Tick 停顿年龄与恢复提示，停顿超过 1 秒时最多等待约 2 秒。
- **修复**：分片写入的省略 `properties` 幂等键、分片读取取消响应、截图客户端的断线线程/超时/大图传输。

## 下载资产

| 文件 | 用途 |
| --- | --- |
| `minecraft-mcp-2.0.0.jar` | Minecraft 26.3 Fabric Mod，含服务端桥、客户端展示与截图端 |
| `minecraft-mcp-server-2.0.0.tgz` | Node.js MCP stdio Server，28 个工具 |
| `minecraft-mcp-skills-2.0.0.zip` | Agent 建造 Skill 和算法参考 |
| `minecraft-mcp-2.0.0-bundle.zip` | 上述组件、安装脚本、配置示例、说明与许可证 |
| `SHA256SUMS` | 四个分发包的 SHA-256 校验值 |

对应完整源码使用此 Release 自动附带的 Source code 归档，或仓库标签 `v2.0.0`。许可沿用仓库 GNU GPL v3；Fabric API 和游戏本体不随包分发。

## 环境与升级

固定兼容组合：Minecraft **26.3**、Fabric Loader **0.20.1**、Fabric API **0.165.2+26.3**；Java 25+、Node.js 20+。

1. 关闭目标游戏/服务端，并备份需要保留的存档。
2. 从 `mods/` 中移除旧版 Minecraft MCP JAR，避免相同 Mod ID 的多个版本共存。
3. 将 `minecraft-mcp-2.0.0.jar` 和匹配的 Fabric API 放入服务端与每个需要自定义展示/截图的客户端。
4. 更新 MCP Server 和建造 Skill，重新进入世界并调用 `get_world_info` 获取新 session。
5. 可选：在 `<game-dir>/config/minecraft-mcp.json` 调整 `write_candidates_per_tick`（默认 256，范围 1–1024），重开世界生效。

## 安装 MCP Server

```bash
npm install -g ./minecraft-mcp-server-2.0.0.tgz
# 或直接安装当前 Release 的包：
npm install -g https://github.com/X12101XX/minecraftMcp/releases/download/v2.0.0/minecraft-mcp-server-2.0.0.tgz
```

MCP Client 配置示例：

```json
{
  "mcpServers": {
    "minecraft": {
      "type": "stdio",
      "command": "minecraft-mcp-server",
      "env": {
        "MINECRAFT_MCP_CONFIG": "/absolute/path/to/config/minecraft-mcp.credentials.json"
      },
      "timeout": 120000
    }
  }
}
```

`timeout` 是单次 MCP 调用超时，不是整个建造任务的期限；长任务通过 `get_operation` 或对应的 `*_by_request_id` 查询。凭据不要打印、分享或提交。

## 安全边界

- 桥仅监听 loopback，仍要求高熵 Token。截图为指定在线玩家的当前画面，**不弹授权提示**，可能包含聊天和 HUD；持有桥凭据的进程即可请求，必须限制凭据访问。
- `undo_operation` 不是事务回滚；部分失败/取消可能保留已做修改。
- 分片读写父任务只存于当前 MCP Server 进程，重启丢失；不确定子提交不会被自动重放。
- `preload_chunks` 会生成缺失区块并写入存档，需要写权限。

## 验证和边界

- Java 构建与单元测试通过；MCP schema/SDK 协议测试 18 项通过。
- 真实 dedicated 世界：72 区块分片写入、末项非法状态零写入、完整回读；98,304 方块写入约 19.7 秒；远端区块预加载后读写；实体 UUID 查询；截图 936×1018 PNG（真实客户端连接）。
- 未实测：integrated 单人世界、预加载票据自然过期、上千区块同时加载、截图中途断连与多人客户端、4 MiB PNG 上限。截图不用于防篡改或反作弊。
- 详细证据和未覆盖项见 [验证报告](https://github.com/X12101XX/minecraftMcp/blob/v2.0.0/docs/verification-report.md)。

**完整变更：** https://github.com/X12101XX/minecraftMcp/compare/v1.1.0...v2.0.0

# Minecraft MCP 1.1.0 — 建造过程可视化

Release: https://github.com/X12101XX/minecraftMcp/releases/tag/v1.1.0

## 发布内容

- Agent 查看单方块、区域和地形时，精确请求范围短暂亮起青色选框并淡出。
- batch 全部状态预校验后显示半透明待放目标，预览 20 个服务端 Tick 后，按输入顺序逐块真实落位。普通方块使用原模型和材质，特殊渲染目标使用真实形状的半透明光体；空气用删除框表示。
- 游戏内活动面板显示工具调用、真实写任务进度和错误码；预览在任务终态、世界/维度切换、断连或过期时清理。
- 修复超过一个预校验分片的 batch 可能在末项验证失败前提前写入、造成矛盾计数的问题。

**节奏变化：** 所有写工具每 Tick 最多处理 1 个执行候选。20 TPS 时最多约 20 个候选/秒，大区域比 1.0.0 的 256/Tick 模式慢。继续轮询已有 operation；不要因为变慢而重复提交。预览不等于任务已完成，最终仍需回读世界。

## 下载资产

| 文件 | 用途 |
| --- | --- |
| `minecraft-mcp-1.1.0.jar` | Minecraft 26.3 Fabric Mod，含服务端桥与客户端展示端 |
| `minecraft-mcp-server-1.1.0.tgz` | Node.js MCP stdio Server |
| `minecraft-mcp-skills-1.1.0.zip` | Agent 建造 Skill 和算法参考 |
| `minecraft-mcp-1.1.0-bundle.zip` | 上述组件、安装脚本、配置示例、说明与许可证 |
| `SHA256SUMS` | 四个分发包的 SHA-256 校验值 |

对应完整源码使用此 Release 自动附带的 Source code 归档，或仓库标签 `v1.1.0`。许可沿用仓库 GNU GPL v3；不修改原 LICENSE。Fabric API 和游戏本体不随包分发。

## 环境与升级

固定兼容组合：Minecraft **26.3**、Fabric Loader **0.20.1**、Fabric API **0.165.2+26.3**；Java 25+、Node.js 20+。

1. 关闭目标游戏/服务端，并备份需要保留的存档。
2. 从 `mods/` 中移除旧版 Minecraft MCP JAR，避免同时出现相同 Mod ID 的多个版本。保留现有配置、凭据和其他 Mod。
3. 将 `minecraft-mcp-1.1.0.jar` 和匹配的 Fabric API 放入服务端与**每个需要观看特效的客户端**的 `mods/`。单人世界安装一次即可；无需额外预览 Mod。
4. 更新 MCP Server 和建造 Skill。工具仍是原来的 14 个，参数没有新增要求。
5. 重新进入世界并重新调用 `get_world_info` 获取 session，再执行工具。

只在服务端装 Mod 时，普通客户端仍可看到真实逐块世界更新，但没有自定义选框、半透明目标和活动面板。

## 安装 MCP Server

```bash
npm install -g ./minecraft-mcp-server-1.1.0.tgz
# 或直接安装当前 Release 的包：
npm install -g https://github.com/X12101XX/minecraftMcp/releases/download/v1.1.0/minecraft-mcp-server-1.1.0.tgz
```

MCP Client 配置示例（使用目标游戏实例内的实际凭据路径）：

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

`timeout` 是单次 MCP 调用超时，不是整个建造任务的完成期限；长任务通过普通 `get_operation` 调用持续查询。凭据在进入世界后由 Mod 生成，不要打印、分享或提交 Token。

## 安装 Skill 与完整包

独立 Skill 压缩包解开后，将 `minecraft-mcp-building/` 放入 `~/.agents/skills/`，重启 Agent 客户端。

完整包解压后也可执行：

```bash
cd minecraft-mcp-1.1.0
sha256sum -c SHA256SUMS
# 先完成上面的旧 JAR 移除步骤；本脚本不会删除旧 JAR。
MODS_DIR=/absolute/path/to/instance/mods ./install.sh
```

安装脚本将 MCP Server 安装到当前 npm 全局 prefix、更新 `~/.agents/skills/minecraft-mcp-building`，并在指定 `MODS_DIR` 时复制本版 JAR。它不安装 Fabric API、不登录游戏、不接受 EULA、不替用户修改 MCP 配置；多台客户端须分别安装 Mod。

## 验证和边界

构建、14 个 Java 测试、5 个 MCP schema/协议测试、真实 dedicated/integrated 游戏显示与回读、取消/错误、完整房屋检测修复的证据见 [验证报告](verification-report.md#8-建造过程可视化回归--2026-11-06)；版本化安装产物的真实验收见该报告第 9 节。[实机过程录像](media/build-process.mp4)展示预览、真实施工与终态清理。

尚未测量 8192 项全部可见预览的极限负载、客户端帧率分位数、第三方 shader/资源包/Mod 兼容和长时断线/暂停竞态。只访问已加载区块；不支持任意 NBT、命令、实体创建或事务回滚。

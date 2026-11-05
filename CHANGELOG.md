# Changelog

## 1.0.0 — 2026-11-05

### Added

- Fabric 26.3 Mod：认证的 loopback bridge、世界会话、服务端线程调度、真实方块/区域/地形读取、异步分区写入、取消、错误分页与结果分页。
- 独立 TypeScript MCP Server（官方 SDK 1.32.2，stdio）：14 个标准 MCP 工具、JSON Schema、typed error、异步 operation 轮询。
- MCP Client 配置示例、完整工具契约、架构与 Skill 文档。
- 两层木屋端到端验收：建造、完整区域重读、缺陷注入、检测与修复。
- 真实 dedicated/integrated 服务端、真实客户端玩家、保存重启、取消、规模和错误路径验证；结果见 `docs/verification-report.md`。
- Java 10 个单元测试、MCP 5 个 schema/protocol 测试。

### Known limitations

- 仅 loopback、stdio；不支持远程 MCP/TLS/多用户授权。
- 仅已加载区块；不支持 NBT/容器/实体/命令/事务回滚。
- 仅在 Linux + Minecraft 26.3 + Fabric 0.20.1 环境完成运行验证。

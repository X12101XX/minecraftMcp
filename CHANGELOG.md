# Changelog

## Unreleased — 2026-11-06

### Added

- 同一 Mod 的客户端展示端：精确区域选框亮起/淡出、batch 半透明模型预览、Agent 工具活动与真实任务进度 HUD；无需 Fabric API 以外的前置 Mod。
- 有界、单向、按维度隔离的展示 payload；进度移除已处理目标，完成/失败/取消/session 或客户端世界切换清理临时模型。
- 真实 MCP 可视化 E2E 与展示载荷完整状态、负坐标、范围和包规模边界回归。

### Changed

- 所有写工具以每 Tick 最多 1 个执行候选逐块推进；batch 全部预校验成功后预览 20 Tick，再按输入顺序真实落位。大区域吞吐低于 1.0.0，原子性和取消语义不变。

### Fixed

- batch 超过一个预校验分片时，未完成全部校验便提前写入的缺陷；600 项末项非法的真实案例现在零写入且计数一致。

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

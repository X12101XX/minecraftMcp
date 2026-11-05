# 后续 Agent 实施入口

## 当前状态

核心实现已完成：Fabric Mod、独立 MCP Server、构建入口、单元/协议测试与真实 dedicated/integrated 世界 E2E 均已运行。结果与未覆盖边界见 [验证报告](docs/verification-report.md)。后续变更必须保持该报告与 README、契约、Skill 同步，不能用设计描述替代回归验证。

## 阅读顺序

1. [README](README.md)：状态和导航。
2. [架构](docs/architecture.md)：决策、模块边界、不变量。
3. [工具契约](docs/tool-contracts.md)：参数、结果、错误和内部通信。
4. [实施与验收](docs/implementation-plan.md)：里程碑和可观察完成标准。
5. [Skill 设计](docs/skills-design.md) 与 [开发 Skill](skills/minecraft-mcp-development/SKILL.md)。

本文件只给协作入口，不复制全部接口。字段以工具契约为准，架构不变量以架构文档为准。发现冲突先明确修订依据并同步相关文档，禁止选择容易的版本静默缩减需求。

## 实施约束

- 先做真实单方块读写 MCP 闭环，再扩展区域与批量；不交付空架构。
- 初始化时重新验证最新正式版兼容组合，固定版本并保留 wrapper/lockfiles。
- 所有世界读取和写入都在服务端线程；禁止同步加载区块、网络等待和无界循环。
- MCP SDK 与世界逻辑解耦；不把 HTTP endpoint 当作 MCP 兼容证明。
- 查询必须可还原全部方块和状态，空气不可省略，未加载不当空气。
- 跨 Tick、取消和错误的非原子性要明确；实际计数和完整失败明细不可丢失。
- 默认本地监听、认证、资源限制；Token 不入库，不打印。
- 修改默认使用正常世界更新与保存路径，不能绕过更新换取性能。
- LICENSE 保持不变；新增依赖注意许可证兼容，勿自行宣称仓库为 MIT 或修改授权版本。

## 验证与文档

执行真实受影响路径。单元测试不能替代游戏启动，GameTest 不能替代外部 MCP Client 调用。集成与独立服务端、保存重启、错误情况和性能须分别记录。未验证明确标记，不自动同意 EULA 或代用户完成授权。

新增/变更工具须同步契约、Schema、客户端示例、相关 Skill 与行为测试。完成里程碑后更新 README 的实际状态及 CHANGELOG。并行 Agent 分配独立文件所有权、先约定字段，不互相覆盖。

实际入口见 README：`fabric-mod/gradlew build`、`./gradlew test`、`mcp-server/npm test`，以及需先启动游戏的 `mcp-server/test/*.mjs` E2E 脚本。

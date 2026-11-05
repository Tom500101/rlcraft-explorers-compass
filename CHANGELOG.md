# 更新日志

本项目的所有重要变更都记录在这里，**倒序排列，最新在上**。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.1/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [1.1.0] — 2026-11-05

模组结构与超稀有结构扩展。

### 新增

- **冰与火龙巢 / 龙穴**：`ModdedStructureRegionScanner` 解析 1.12.2 的 12 位方块 ID
  （`Blocks` + `Add`），再用**宝藏深度**区分地面龙巢（Y 57+）与地下龙穴（Y ≤ 25）。
- **恐怖生物地牢**：优先反射 `ExtendedWorld.dungeons`（精确、带 schematic 变体），
  失效时退回 region 扫描；覆盖 7 个变体。
- **Shivaxi 天空之城**：确定性重放 + RC 放置试算，未生成区块也能算候选；
  `/rlcompass shivaxi` 支持百万级区块深度扫描。
- **缓存仓库**：搜索历史可增删改查、三态标记（未探索 / 已找到 / 未找到），
  已记录的点不会重复返回。
- **维度标注**：主界面每行标出生成维度，维度不符时直接提示「该结构不在当前维度」。
- **搜索半径滑条**：界面内 100–100000 格可调，松手写回 `config/rlcompass.cfg`。

### 改进

- **群系采样**：改用 `BiomeProvider.getBiomes` 瓦片批量读取，19,443 chunk/s（约 26 倍提速）。
- **调试探针**：`RcRandomProbe` 改为配置开关 `debugRandomProbe`（默认关闭），
  不再每次会话刷 48 行日志；也可用 `-Drlcompass.rcprobe=true`。

### 修复

- **RC 类别顺序**：`ArrayListMultimap` 的身份哈希导致类别迭代顺序逐会话不同，
  改为按注册名排序的 `LinkedHashMultimap`，重放结果跨会话稳定。
- **随机数公式**：用运行时探针逐位确认 `ChunkGeneratorOverworld.populate`
  的种子为 `x*k + z*l ^ worldSeed`。
- **龙巢 / 龙穴误判**：判据由「最小 Y」改为「宝藏深度」，真龙巢不再被地下龙穴顶部的装饰骗过。

## [1.0.0] — 2026-10-30

首个可用版本，阶段 8 的 8 项验收全部通过。

### 新增

- 分层定位框架 `IStructureLocator` / `StructureLocatorRegistry`。
- 适配器：原版 6 类结构 + Better Mineshafts、Roguelike Dungeons、Doomlike Dungeons、
  Ruins、Battle Towers、Recurrent Complex。
- 指南针物品 `rlcompass:explorers_compass`（32 帧表盘、模型、合成表、语言文件）。
- 结构选择界面 `GuiStructureSelect` 与 HUD `CompassHudRenderer`（文本 + 箭头）。
- 网络通道：`PacketStructureResult` / `PacketOpenCompassGui` / `PacketSearchRequest`。
- 生成缓存 `StructureCacheData`（`WorldSavedData`）与 `/rlcompass rebuildcache`。
- 配置文件：搜索半径、结构黑名单、慢适配器告警、精确坐标开关。

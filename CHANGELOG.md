# 更新日志

本项目的所有重要变更都记录在这里，**倒序排列，最新在上**。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.1/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [1.2.0] — 2026-11-06

### 新增

- **指南针直接驱动异步深扫（不需要作弊）**：选中 Recurrent Complex 的稀有 / 超稀有结构时，
  「搜索」不再走带 8 秒预算的同步查询——那条路径会把「没扫完」误报成「没找到」，
  100000 格半径实际只覆盖了很小一圈。现在界面直接启动 `/rlcompass scan` 使用的同一套
  tick 分片扫描器（作者大厅独立快扫 / `rare` 快扫 / 通用 tile 深扫），结果经新增的
  `PacketDeepScanResult` 异步回传：命中自动设为 HUD 目标，扫完无结果明确播报，
  搜索期间再次打开指南针时「搜索」按钮变为「取消深扫」。命令需要权限等级 2，界面不需要，
  普通生存玩家也能用。半径随请求一起发送，同时修掉专用服务器上客户端 `rlcompass.cfg`
  与服务端不一致导致半径被忽略的问题。
- **缓存仓库传送**：开启作弊（或 OP）后，每行可直接传送到该坐标**正上方的地表**。
  Y 由服务端重新计算，只落在「脚下实心、头脚两格为空」的位置；水面、岩浆、仙人掌、
  火焰、传送门与树冠都会被跳过，目标列不可用时向周围 16 格找替代落点，
  找不到则拒绝传送并提示原因。
- **超稀有结构扩展**：把 Shivaxi 的重放引擎参数化，新增**作者大厅**
  （`rlcompass:authorshall`，`RLCraft_2_9_Update_Build`，花林）和 **P_IceCity**
  （`rlcompass:icecity`，冰原）。三者共用记录、确定性重放和放置试算。
- **`/rlcompass scan <id> [半径]`**：通用超稀有深扫入口，支持 `shivaxi` / `authorshall` /
  `icecity` 或完整 id；`/rlcompass shivaxi` 保留为别名。
- **统一快扫 `RcFastScan`**：由原来的 `AuthorHallFastScan` 泛化而来，
  **现仅用于 `rare` 目标**（艾瑞维斯、绿宝石穹顶、末地污秽岛 / 小屋）。
  先用 chunk 随机数预筛类别掷点（每 chunk 128 次，阈值 `8e-4`），只有少数 chunk 去采群系；
  预筛用内联 LCG 复现 `java.util.Random`（位级等价，1.9 µs → 0.23 µs/chunk）。
  `ultrarare` 与 `adventure` 目标改走通用 tile 深扫，原因见下方修复与 §38。
- **指南针整合**：超稀有结构的仓库记录（未标记「未找到」）会被指南针直接返回；
  `StructureLocatorRegistry` 的硬编码半径上限从 20000 修正为 **100000**，与配置和滑条一致。
- **深扫命中即停**：`/rlcompass scan` 默认在找到**最近的一个**候选后立即结束（按环向外扫，
  第一个命中的环就是最近的一圈），只有加 `all` 才扫满整个方形。作者大厅快扫同样如此。
- **稀有 / 冒险结构接入重放**：`Target` 增加 `category` 与 RC 群系表达式，
  类别门 `hasUltraRare` 泛化为 `hasCategory(biome, category)`，群系判定直接反射
  RC 的 `BiomeExpression`。新增沙漠黄金殿 `rlcompass:aeriveth`（rare，`$HOT & $DRY & $SANDY`）、
  绿宝石穹顶 `rlcompass:sanctuary`（rare，`$JUNGLE`）、普通黄金殿 `rlcompass:erolith`
  （adventure，`$SANDY`）、城镇 `rlcompass:plainscity` / `rlcompass:meridor`、
  海港城市 `rlcompass:islandcity`、罪恶学院 `rlcompass:foreststronghold`
  （`RLCraft_ForestStronghold`，黑森林）。普通结构的放置试算最多只做 3 次，
  避免 335×307 的体积拖慢搜索。
- **末地结构接入**：末地解密 `rlcompass:crystalshrine`（adventure）、末地污秽岛
  `rlcompass:corruptedisland`（rare）、末地污秽小屋 `rlcompass:defiledhut`（rare）。
  重放按维度选择区块随机种子，末地使用 `x*341873128712 + z*132897987541`
  （`ChunkGeneratorEnd` 不含世界种子）。
- **末地传送落点**：候选点改为记录放置试算得到的**结构真实 Y**（`StructureBoundingBox.minY`），
  不再写死 64；传送时按「RC 记录 Y → 放置试算 Y → 岛面扫描」取锚点，落点必须
  「3×3 至少 7 格实心 + 下方 5 格至少 4 格实心」，找不到就拒绝，绝不丢进虚空。
- **热门结构快捷入口**：主界面新增「热门结构」按钮，弹出 **14 条**常用结构
  （龙穴、三个超稀有、稀有 / 冒险结构、末地三结构）；浮层支持滚轮滚动、名称截断与超稀有标签，
  点一条直接搜索，超稀有自动使用 100000 半径、其余使用 10000 半径。

### 改进

- **快扫预筛提速**：`RcFastScan` 的 RNG 预筛改用内联 LCG 复现 `java.util.Random`
  （含 `setSeed` 扰动与 `next(26)` / `next(27)` 两级移位），位级等价性在 12 万真实
  chunk 种子 / 1536 万个 double 上验证为零差异，单 chunk 从 1.9 µs 降到 0.23 µs；
  实测吞吐 52,000 → 245,000 chunk/s。tick 预算保持 8 ms/tick 不变。
  预筛阈值维持实机验证过的 `8e-4`（`rare`），见下方修复与 §37 / §38。

### 修复

- **P_IceCity / 作者大厅零候选**：RC 的 `GenerationInfo.spawnChance` Java 默认值是 `0`，而
  三个 `ultrarare` 的 `.rcst` 都没写这个字段；`spawnChance()` 在群系表达式**匹配**时使用该
  结构自己的 `spawnChance`（0），不匹配时才回退到类别默认 `1.5E-4`。结果 `ICE_FLATS` /
  `MUTATED_FOREST` 匹配上反而永远选不中。现在在 selector 上运行时把 `ultrarare` 类别里
  `spawnChance <= 0` 的项补成 `1.5E-4`，不改模组文件。另把 `P_IceCity` 的
  `sourceMatcher` 从 `ICE` 放宽为 `!air`（RLCraft 冰原表面多为雪/草，否则放置试算全部返回空）。
- **已记录结构短路**：搜索 RC 目标时先查 RC 自己的结构记录和缓存仓库，命中已知点直接返回，
  不再为存档里已有的结构重扫几分钟。`测试` 存档里的两个 P_IceCity 现在会秒回。
- **普通搜索也携带半径**：龙穴、村庄等同步结构以前仍读服务端 `searchRadius`，所以滑条设成
  100000 后搜龙穴会退回 10000 格（625 区块）。现在 `PacketSearchRequest` 同样带上滑条值，
  服务端优先使用它，只有命令路径才回落到配置。
- **最大半径提示**：深扫扫满 100000 格仍无结果时，聊天栏改为「半径内没有找到 %s。已经扫到
  最大半径（100000 格），请换一个远的地方再定位吧」，而不是普通「没找到」。
- **深扫诊断计数**：`deep scan finished/stopped` 现在输出 `target plans / planned`
  （例如 `0/152`），用来区分「RC 选择器从未选中目标」和「选中后被放置试算 / 生成后核实拒绝」。
- **半径立即生效并播报**：搜索请求改为携带滑条当前值，不再读 `CompassConfig.searchRadius`。
  `ConfigManager.sync` 会短暂把静态字段回读成磁盘旧值，这正是「设了 100000、实际按 625 区块
  （10000 格）搜」的原因；松手后聊天栏现在会播报「半径已更新成功：N 格（M 区块）」。
- **通用深扫提速**：`ultrarare` 的 tile 扫描加入 128 draws / `1e-3` 的保守 RNG 预筛
  （`1e-3` 约为目标概率 `2.25e-5` 的 44 倍，不会挡掉真实命中），`planChunk` 调用量降到约 1/8；
  `alreadyProcessed` 提前到重放之前；tick 预算 4 ms → 8 ms。实测 46 分钟只走到 231/391 环的
  作者故乡扫描，预计降到几分钟（待实机复测）。放行候选仍走完整选择器、放置试算与生成后核实。
- **龙穴/龙巢搜索提示**：Ice and Fire 不保存位置记录、随机流不可重放，只能从已生成的区块里
  读取特征方块；搜索无结果时改为明确提示原因，而不是让玩家以为扫描器坏了。
- **恢复作者大厅独立快扫 `AuthorHallFastScan`**（14:31，应用户要求）：把 11-05 深夜那个
  「专门 Java 文件」版本的行为重新落地 —— `64 draws` / 阈值 `1e-4` / `4 ms` 每 tick、
  固定只服务 `authorshall`；`RLCompassMod` 注册独立 Ticker，`/rlcompass scan cancel`
  现在同时停三个扫描器。该版本实测 `5600` 区块约 16 分钟找到过真的作者大厅。
  Shivaxi / P_IceCity / `adventure` 仍走通用 tile 深扫，`rare` 仍走 `RcFastScan`。
  1.2.0 的其余功能（稀有 / 冒险结构、末地传送、热门结构、缓存仓库传送）全部保留，
  见 `docs/HANDOFF.md` §39。
- **超稀有目标停用 `RcFastScan`**（14:01）：阈值回退到 `1e-4` 后，`icecity 1200`
  仍 41 秒扫完 0 候选（73,222 prefiltered / 1458 eligible / 1 rejected，`skipped=0`），
  说明根因不是阈值而是预筛模型本身：RC 类别顺序被钉死成
  `[adventure, decoration, frequent, rare, ultrarare]`，超稀有那次掷点排在最末尾，
  `PREFILTER_DRAWS=128` 根本看不到；「连续 128 次 `nextDouble`」是否与 RC 真实掷点序列
  对齐也从未验证。实测快扫的放行集合与真实命中集合几乎不重合，历史上唯一一次
  authorshall 快扫命中属运气。现把 `ultrarare`（含 `/rlcompass shivaxi` 别名）与
  `adventure` 统一路由到通用 tile 深扫（实机已多次找到真的 Shivaxi、P_IceCity、作者大厅），
  仅 `rare` 保留快扫。代价：1200 半径约 5 分钟，见 `docs/HANDOFF.md` §38。
- **回退「预筛阈值收紧」**（13:03 引入、13:46 撤销）：把 `ultrarare` 阈值从 `1e-4`
  收紧到 `3.5e-5` 后，2 次 `authorshall 5600` 与 4 次 `icecity`（1200/2400）扫描
  全部零候选，且日志里 `skipped=0`、`rejected` 仅 2~5 —— 说明预筛放行的 chunk 与
  真实命中几乎不重合；同一会话的 rare 目标 `aeriveth` 仍 18 秒命中。
  离线「集合对比 + 抽样」只证明了同一组 128 次掷点上的阈值单调性（同义反复），
  没有验证 RC 真实每结构阈值，更没有验证目标那次掷点是否落在 128 次之内。
  阈值恢复为 `1e-4` / `8e-4`，内联 LCG 提速保留，见 `docs/HANDOFF.md` §37。
- **候选点在已生成区块**：重放只回答「RC 会选中这里」，不回答「RC 当年接受了这里」。
  已生成区块的结构布局早已固定，命中 `WorldStructureGenerationData.checkedChunks` 的区块
  一律跳过预测，避免把玩家传送到空地。
- **候选改为「生成后核实」**：放置试算会读方块并触发区块生成，RC 在这时就完成了真正的
  判定（含 placer 之外的 `structureOverlap` 重叠检查）。现在试算之后会查
  `WorldStructureGenerationData.getEntriesByID(结构名)`，只有真的出现在存档里才播报；
  被相邻结构挤掉的候选直接跳过并继续扫。
- **末地浮岛报「没有安全落点」**：结构记录的 Y 与实际岛面可能相差几十格
  （实测结构 Y 132、岛面约 180）。锚点窗口 `[y-8, y+16]` 找不到时，改为回退扫描
  `2 ~ worldHeight-1` 全高，仍要求「3×3 实心 + 下方 5 格实心」才接受，
  不会因此把玩家丢进虚空。
- **难找结构扫不到**：`authorshall` / `icecity` 从通用 tile 扫描切到 `RcFastScan`，
  配合默认「命中最近一个即停」，大半径搜索不再因耗时过长而放弃。
- **文档**：补上「建筑可能生成在空中，请抬头看看」的提示（结果说明、FAQ、超稀有章节），
  并同步快扫、热门结构、命令列表与下载版本的说明。

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

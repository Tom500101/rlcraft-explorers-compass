# HANDOFF — 结构适配器状态

> 最后更新：2026-11-03
> 列定义：**实现** = 代码存在且在 `StructureLocatorRegistry` 中注册；**自动验证** = 集成测试 / clienttest / 日志中有可复现的调用记录；**人工确认** = 有人进游戏目视确认过结果真实存在。

## 1. 适配器总表

| 结构 ID | Locator | 实现 | 自动验证 | 人工确认 | 证据 |
| --- | --- | --- | --- | --- | --- |
| `minecraft:stronghold` | `MapGenLocator` | 是 | 是 | 未做 | `(-712,32,-1320)` 1838 格 / 0 ms（`docs/integration-test.md`） |
| `minecraft:village` | `MapGenLocator` | 是 | 是 | 未做 | `(664,64,600)` 557 格 / 2 ms；另做过黑名单过滤测试 |
| `minecraft:mineshaft` | `MineshaftLocator` | 是 | 是 | 未做 | `(1464,64,2056)` 2181 格 / 14–22 ms（替代原版 9 s 阻塞） |
| `minecraft:temple` | `MapGenLocator` | 是 | 是 | 未做 | `(728,64,-312)` 768 格 / 0 ms |
| `minecraft:monument` | `MapGenLocator` | 是 | 是 | 未做 | `(2312,64,1336)` 2351 格 / 13 ms |
| `minecraft:mansion` | `MapGenLocator` | 是 | 是（返回 null） | 未做 | 真实位置 20641 格，超半径返回 null / 161 ms |
| `rlcompass:battletower` | `BattleTowersLocator` | 是 | 是 | **已确认**（2026-11-02） | 无数据时列表隐藏 + `find` 回 `is not available`；放置 `BattletowerPositionsFile.txt` 后命中 `(200,64,200)` 7224 格 / 1 ms `via BattleTowersFile` |
| `rlcompass:ruin` | `RuinsLocator` | 是 | 是 | 未做 | `(172,69,184)` 93 格 / 2 ms |
| `reccomplex:structure` | `RecurrentComplexLocator` | 是 | 是 | 未做 | `(220,84,265)` 1 格 / 3 ms |
| `roguelike:dungeon` | `RoguelikeLocator` | 是 | 是 | **已确认**（2026-11-02） | 见 §2 |
| `doomlike:dungeon` | `DoomlikeLocator` | 是 | 是 | 未做 | `(520,64,712)` 539 格 / 3 ms；变种搜索 `(5272,64,5400)` 105 格 / 9 ms 标 `unconfirmed` |
| `rlcompass:battletower`、`rlcompass:ruin` | `CacheLocator`（镜像） | 是 | 间接 | 未做 | 缓存重建/持久化验证过，但日志中 `via Cache` 命中 0 次（优先级最低，活适配器正常时轮不到它） |

> 2026-11-03：`lostcities:city` 已移除。Lost Cities 是**独立维度**（id 111），指南针的语义是「在同一维度里找结构」，把一个维度当作可搜索结果既不符合直觉，也让「最近」失去意义。

## 2. Roguelike 人工确认记录（2026-11-02）

- 起点：玩家 `(5176, 5436)`，维度 0。
- 首次搜索：`/rlcompass find roguelike:dungeon`
  - 聊天：`Roguelike Dungeon at (4904, 64, 5720), 393 blocks away, found by Roguelike (2642 ms)`
  - 日志：`[15:09:27] [REGISTRY] findNearest(roguelike:dungeon) from (5176, 5436) -> roguelike:dungeon @ (4904, 64, 5720) via Roguelike dist=393 (2641 ms)`
- 传送：`/tp 4904 100 5720` → 目视确认有石砖塔状地牢入口（截图 `2026-11-02_15.17.14.png`）。
- 二次搜索（原地）：
  - `[15:16:12] ... from (4905, 5705) -> @ (4904, 64, 5720) via Roguelike dist=15 (0 ms)`
  - `[15:16:42] ... from (4906, 5713) -> @ (4904, 64, 5720) via Roguelike dist=7 (0 ms)`
- 结论：**父项定位可用**。坐标固定为 chunk 中心 `(chunkX<<4)+8, y=64, (chunkZ<<4)+8`，误差在半个 chunk 内（本例目视约十几格，与 y 固定为 64 的估算一致）。
- 遗留观察：首次搜索 **2641 ms** 偏慢（393 格 ≈ 25 个 chunk 环，折算约 1 ms/chunk），与"判定是纯哈希、应当很便宜"的假设不符，待查。
- **变体**（2026-11-02 新增）：`RoguelikeLocator.getVariants` 反射 `greymerk.roguelike.theme.Themes` 枚举，得到 **39 个主题**（`BLING`…`TOWER`），来源标 `RUNTIME_API`，显示名形如 `Nether Fortress Dungeon ≈`。生成器用 `Themes.random(Random)` 在建塔时随机选主题、且不落盘，因此按主题搜索只是"父结构 + 标签"，结果一律标记 `variantConfirmed=false`（HUD/聊天显示 `Theme: unknown (approx.)`）。
- **为什么不能精确**（2026-11-02 查证）：`SettingsResolver.chooseRandomValid` 先按维度/生物群系筛出合法设置，再用 `WeightedRandomizer.get(editor.getRandom())` 加权随机抽一个；`WorldEditor1_12` 的随机源是 `new Random(Objects.hash(world.getSeed()))`，实际生成时已被方块放置消耗过，事后重放无法复现；模组也不把主题写入区块或存档。**"哪个 chunk 是哪个主题"在游戏内不存在**，只能到现场看材质。
- **UI 说明**：Doomlike 的子项仍带 `≈`（它的主题无法重放，只能当标签）；Roguelike 的 `≈` 已于 2026-11-02 **去掉**——主题可重放，变种搜索是精确的。GUI 侧栏提示 `gui.rlcompass.hint.variant` 对应 `≈` 行。
- **主题可重放**（2026-11-02 二次查证，已实现）：生成器 `DungeonGenerator1_12.generate` **每个 chunk 都 new 一个 `WorldEditor1_12`**，其 Random 由 `Objects.hash(world.getSeed())` 派生；`canSpawnInChunk` 只用坐标哈希、不消耗该 Random，因此 `SettingsResolver.chooseDungeonSetting` 是第一个消费者。用同种子重建 editor 重放该调用，即可得到生成时会用的 `DungeonSettings` → `LevelSettings.getTheme()`。
  - 实现：`RoguelikeLocator.themeNameAt()`（`:~380`）反射 `chooseDungeonSetting(editor, Coord)`，再 `getLevelSettings()` → `getTheme()` → 用 `Themes.getThemeBase()` 反查枚举名。
  - 变种搜索（`findNearest(..., variantId)`）现在逐候选重放主题，命中才返回，并标 `variantConfirmed=true`；重放不可用时退回"父结构 + 未确认"。
  - 普通搜索每次会话打一条 `[ROGUELIKE] nearest dungeon at chunk (x,z) uses theme NAME`，用于和实地材质比对。
  - **待实机核对**：该主题是否与玩家看到的方块一致；若一致，可去掉 `≈` 与侧栏提示。
  - **实机核对结果（2026-11-02，通过）**：chunk `(306, 357)`（即地牢 `(4904, 64, 5720)`）重放得到 `PYRAMID`；`ThemePyramid` 的方块构成为 `SANDSTONE_SMOOTH(100) + SANDSTONE(10) + CHISELED_SANDSTONE(5)`，与玩家目视的"砂岩 + 磨制砂岩"一致。日志：`[19:00:52] [ROGUELIKE] nearest dungeon at chunk (306, 357) uses theme PYRAMID`。
  - **变种扩容（2026-11-02 晚）**：除 39 个枚举主题外，还会扫描 `settings/builtin` 与 `settings/fnar` 下可被选中的地牢 setting（如 `fnar:dungeon_ocean_temple`），变种 id 形如 `roguelike:dungeon/setting/<settingId>`。这类地牢的主题由配置内联定义（海晶石/石英），不在枚举里，只能靠 settings id 精确匹配——重放结果里 `getId()` 与之一致即命中。
  - **搜索无结果反馈**：`PacketStructureResult.notFound` 现在携带 variantId，客户端在聊天栏提示"搜索范围内没有找到 X"。
  - **贴图**：32 张 PNG 与 32 个模型一直在，缺的是物品模型注册；新增 `client/ModelRegistration`（`ModelRegistryEvent` → `ModelLoader.setCustomModelResourceLocation`）。

### 2.1 定位原点修复（2026-11-02 晚）

- **现象**：`Ocean Temple Dungeon` 返回 `(4088, 64, 4904)`，玩家 `/tp` 过去后从 y=0 到 y=255 逐层找过，**一无所有**。
- **根因**（本地反编译 `RoguelikeDungeonsFnarEdition-1.12.2-2.4.4.jar`）：
  - `Dungeon.canSpawnInChunk` 只做两道纯哈希判定：`chunkX % f == 0 && chunkZ % f == 0`，以及 `spawnChance`。
    RLCraft 的 `config/roguelike_dungeons/roguelike.cfg` 里 `spawnFrequency=17` → `getSpawnFrequency() = 3 * max(2, 17) = 51`，
    `spawnChance=1.0`。也就是说**每 51×51 chunk 网格上的候选点全部通过**，它不检查地形。
  - 真实坐标来自 `Dungeon.spawnInChunk` → `selectLocation(random, chunkX*16, chunkZ*16)`：
    最多 `spawnAttempts=10` 次，每次在 `radius = random.nextInt(freq*16/2) = nextInt(408)` 的圆内取点，
    再用 `canGenerateDungeonHere` 校验（y=100 是空气、向下能到地面且不穿水、头顶 9³ 无地面、脚下 9×3×9 实心），
    第一个通过的点即地牢原点；**10 次全失败则该 chunk 根本不生成**。
  - 因此「chunk 中心」既可能偏 0–408 格，也可能对应一座从未生成的地牢。
    `(4088, 4904)` 属后者：chunk `(255, 306)` 的 10 个重放点全部落在海洋，`canFindStartingCoord` 从 y=100 下探时先遇到水 → 全否决。
    存档实测该 chunk 只有石头/水/泥土（无海晶石、无石英）。
- **修复**（`RoguelikeLocator`）：
  - 反射调用 `Dungeon.selectLocation(Random, int, int)`，随机源按 Forge `GameRegistry.generateWorld` 重建：
    `new Random(worldSeed)` → 两次 `nextLong() >> 3` → `chunkSeed = (xSeed*chunkX + zSeed*chunkZ) ^ worldSeed`。
    这就是生成时该 generator 收到的 `Random`，所以取点与生成完全一致。
  - `selectLocation` 内部的 `canGenerateDungeonHere` 会调 `hasStructureTooCloseBy` → `ChunkProviderServer.getNearestStructurePos`（扫描上千 chunk）。
    实测代价不可接受，故在搜索期间把 `RogueConfig.VANILLA_STRUCTURES_TO_CHECK_MINIMUM_DISTANCE_FROM` 临时置空（`setString("")`，结束时恢复）。
    该检查只在距村庄/神殿 <8 格时否决，跳过它带来的假阳性概率极低。
  - 返回原点坐标，`y` 取 `RogueConfig.UPPERLIMIT`（100，`canGenerateDungeonHere` 保证该点是空气，tp 安全）；
    `Dungeon.generate` 内部把 origin 的 y 固定为 50，塔从 50 向上建到地表。
  - 变种搜索改为**两段确认**：先用 chunk 中心做廉价主题预筛，拿到 origin 后再用 origin 坐标重放一次（偏移可能跨生物群系）。
  - 兜底：单个搜索最多重放 16 个候选 chunk；若全部被地形否决，返回 `null` 并打
    `[ROGUELIKE] N candidate chunks had no valid spawn origin within R blocks; nothing to report`。
    反射不可用时退回旧的「chunk 中心 + 未校验」行为，不会更差。
- **待实机验证**：
  1. `/rlcompass find roguelike:dungeon` → `/tp` 到返回坐标，应直接看到地牢（而不是空旷地形）。
  2. 变种 `Ocean Temple Dungeon`：应命中真实生成在沙滩（`biomeTypes: [OCEAN, BEACH]`）上的那座；纯海洋候选会被跳过。
  3. 日志若出现 `no valid spawn origin`，说明附近确实没有可生成的地牢，属正确结果。

### 2.2 原点重放的四处修正（2026-11-02 深夜 ~ 11-03）

- **候选枚举改网格**：`canSpawnInChunk` 只接受 `chunkX % 51 == 0 && chunkZ % 51 == 0`，沿 ring 遍历等于拿 20 万次哈希换约 600 个真候选（实测 2.3 s）。现在反射 `Dungeon.getSpawnFrequency()`，直接枚举网格点并按距离排序，去掉无效遍历。
- **删掉 chunk 中心主题预筛**：预筛用 chunk 中心的生物群系猜主题，而原点可偏移 408 格。
  实例：一座真实的 Ocean Temple 在 `(3110, 5821)`，其 origin chunk 中心在约 190 格外、生物群系不同，预筛直接把整座地牢丢掉 → 变种搜索「无命中」。
  现在改为对每个候选直接重放原点，再用原点坐标确认主题。
  **2026-11-03 更新**：去掉「最近 40 个候选」的上限，改为覆盖整个半径。为控制成本加了 5 点 biome 预筛
  （chunk 中心 + 四角内缩 5 格，只读 `getBiome`、不加载区块），以及 30 秒墙钟预算，超时返回未找到并打 WARN。
- **不再调用 `selectLocation`，改为自己遍历 10 个候选点**：`Dungeon.selectLocation` 只返回**第一个通过地形校验**的点，而该校验读取的邻居区块在 population 阶段可能还没成型。
  在「已完成的世界」上重放，它会停在某个从未建塔的 attempt 上——`(4980, 7137)` 就是这么来的（重放通过，存档只有 40 块苔石）。
  现在 `originFor` 用 Forge 的 chunk random 自己算出全部 `spawnAttempts`（=10）个点，逐个做方块指纹，取第一个真正有地牢的。
  实测 chunk `(204, 357)`：attempt 0/1 指纹 0，attempt 2 指纹 1183 且坐标 `(3110, 5821)`，与玩家 tp 看到的塔一致。
- **方块指纹两级检查**：核心区 `±2` 格、`y=46..62` 先要 ≥8；通过后再查 `±4` 格、`y=42..70`，≥24 才算数。
  方块集合：石砖/圆石/苔石/海晶石/石英/铁栏杆/铁门/spawner/箱子/火把。空地只花 375 次读就放弃，避免 10 个 attempt × 2025 格的开销。
- **验证工具的一个坑**（记录备查）：Python 复刻 Java `Random` 时 `next(32)` 必须按**有符号 int** 返回，否则 `nextLong()` 得负数后 `>> 3` 的结果与 Java 不同，会算出完全另一组候选点；mod 本身用的是真实 `java.util.Random`，不受影响。
- 日志：`[ROGUELIKE] spawn grid every 51 chunks, 10 attempts per chunk, first air at y=100`；
  `[ROGUELIKE] N candidate chunks ... had no dungeon after replay + block check`。




## 3. HUD 行为（2026-11-02 调整）

1. **到达不再清空**（已改）：删除了 `CompassHudRenderer.activePlayer()` 里 `< ARRIVAL_RADIUS(16)` 的 `clear()` 分支。现在目标只会在三种情况下消失：切换维度、发起新的搜索且未找到、测试/退出重置（`ClientHudSelfTest:253`、`PacketStructureResult:135`、`CompassHudRenderer:184`）。
2. **目标行改绘制层**（已改）：从 `RenderGameOverlayEvent.Text`（Forge 左上角文字层，会被其他模组的药水/饰品面板覆盖）移到 `Post(ALL)`，画在箭头正下方 `screenHeight/2 + 54`，带半透明底色。`XYZ:` 行仍留在文字层。
3. **clienttest 同步**：`state.arrivalClears` → `state.arrivalKeeps`（断言 `hasTarget() == true`）。
4. **手持才显示**（已改）：`onRenderPost` 增加 `holdingCompass(player)` 门控——指南针在主手或副手才画箭头与文字；切到其他快捷栏格子立即隐藏，切回恢复（目标状态不清）。clienttest 的 `hud`/`arrow` 模式通过 `testOverlayActive()` 强制显示，因为测试假玩家手上没有物品。
5. **待实机确认**：重启客户端后确认 ① 目标行在箭头下方；② 距离 0 不消失；③ **切到其他格子隐藏、切回恢复**；④ 换维度清空。

## 4. 战斗高塔：region 特征定位（2026-11-02 深夜）

- **背景**：RLTweaker 的 `Consistent Tower Generation` 同时切断 `BattletowerPositionsFile.txt` 导入与生成记账，`worldMap` 永远为空，`canAnswer` 只能返回 false。
- **思路**：塔还在世界里，且方块指纹极强——12×12 塔身，`cobblestone` 与 `mossy_cobblestone` 约 1:1 混合，高 40+ 格，全部落在 1–4 个 chunk 内。
- **实现**：`cache/BattleTowersRegionScanner` 直接读 `saves/<world>/region/*.mca`：
  - 用 `RegionFile.getChunkDataInputStream` 拿解压后的 NBT 字节；
  - 不解析整棵树，只按字节找 `Blocks`（tag 7 / name len 6 / "Blocks" / length 4096）数组，统计 id 4 与 48；
  - 阈值 `cobble ≥ 600 && mossy ≥ 250`，命中后取两类方块的平均 x/z 作塔心，按 24 格去重；
  - 缓存按 region 文件名 + mtime，只有变化的文件重扫。
- **阈值依据**：全存档（64 region / 5878 chunk）用 200/200 跑出 7 个命中，其中 3 个是出生点村庄（cobble 286–404、mossy 210–294）；4 座真塔的 cobble 均 ≥700。取 600/250 后正好保留 4 座，含已确认的 `(4854, 64, 5850)`（842 + 830、12×12、y 68–113）。
- **预热**：`FMLServerStartedEvent` 调 `BattleTowersRegionScanner.prewarm(overworld)`，后台线程延迟 2 s 扫描，主线程不参与；`canAnswer`/`findNearest` 直接读缓存。
- **来源标记**：命中时 `source` 为 `BattleTowersScan`；三路来源（worldMap / positions file / scan）合并去重。
- **限制**：只处理主世界（dim 0）；只认已保存的 chunk（未生成的区域本来也没有塔）；塔跨 chunk 时塔心取方块平均，误差约 1–2 格。

### 4.1 按材质识别全部塔型 + 三个变体（2026-11-03）

- **漏检一**：`TowerTypes.CobbleStone` 只用圆石、**没有苔石**。旧阈值要求 `mossy ≥ 250`，直接漏掉纯圆石塔。
  实例：`(2311, 5031)`，cobble 820 / mossy 69，y 69–127（高 58 格的真塔）。
- **漏检二**：`TowerTypes.Netherrack` 是 netherrack 墙 + **soul sand 地板**（不是 nether brick；后者只是装饰），旧扫描完全不认这两个 id。
  实例：`(2247, 5063)`，y 65–123，每层 44–50 块 netherrack，每 7 层一道 84–103 块的 soul sand 地板，合计 netherrack 2783 / soul sand 866。
- **修复**：
  - 圆石类：`cobble ≥ 700`；苔石只用于区分 `cobblestonemossy`（≥200）与 `cobblestone`；
  - 地狱岩类：`netherrack ≥ 500 && soul_sand ≥ 300`。只数 netherrack 不可行——自然岩浆区一个 chunk 就有 2783 块；
    加上 soul sand 门槛后，同存档的自然地形最高 149、地下残段 166，只有真塔（866）能过；
  - 去重从 24 格收到 16 格，且**不同材质不合并**：相邻的两座不同类型塔此前会被并成一座。
- **变体**：`Mega` / `Cobblestone` / `Mossy Cobblestone` / `Netherrack` / `Sandstone` / `Ice` 六个，变体搜索只认扫描结果（worldMap 与 positions 文件没有类型，不能硬猜）。
- **Mega（2026-11-03 补）**：Mega 是 3–4 座塔拼成的复合体，内部被黑曜石包裹。
  存档实证：`(2278–2326, 5012–5060)`、y 69–125、跨 10 个 chunk、黑曜石合计约 11,000 块（单 chunk 644–1689）。
  判定 `obsidian ≥ 500` 且优先于其他材质；同存档自然黑曜石单 chunk 最高 213，不会误报。
  两个配套修正：命中点改用 **chunk 中心**（原先用方块重心，48×48 的 Mega 重心在相邻 chunk 间漂移，
  会把一座拆成四座）；合并半径按类型区分（Mega 64 格，其余 16 格）。
- **砂砂岩与冰（2026-11-03 补）**：单纯数量判定会误报（全存档砂岩命中 262 处，全是沙丘）。
  加入「真实 y 跨度」校验后收敛到 0：从 `Blocks` 标签往前解析 section 的 `Y`（`01 00 01 'Y' <byte>`），
  要求最高与最低命中层相差 ≥40。已知塔跨度 58，漏过的沙丘只有 29。
  - 副产物：`Stats.height()`，后续做形状校验可直接复用。
- **Jungle / SmoothStone 暂未暴露**：Jungle 的墙就是 mossy cobblestone（与 `cobblestonemossy` 完全同材质），
  SmoothStone 的墙是普通石头（全世界都是）。两者在「方块计数」层面不可区分，必须先做形状校验（四角柱 + 中空 + 层距），
  否则要么重复、要么把整片地下判成塔。
- **全存档复扫**（64 region）：9 座，其中 cobblestone 4 座、cobblestonemossy 4 座、netherrack 1 座（`(2247, 5063)`，旁边 70 格即 `(2311, 5031)` 的纯圆石高塔）。

## 5. Doomlike：主题可重放（2026-11-03）

- **旧结论作废**：此前认为「Doomlike 在生成时随机选主题、不落盘、无法检索」，并给变体加了 `≈`。反编译 `dldungeonsjbg-1.14.17` 后确认可以精确重放。
- **证据链**：
  - `GenerationHandler.buildDungeonHere` → `Builder.placeDungeon(chunkX, chunkZ, world)`；
  - `placeDungeon` 构造 `planner.Dungeon`，构造器里先算
    `this.random = SpatialHash.randomFromSeed(world.getSeed(), chunkX, chunkZ, dimension)`，
    紧接着 `this.theme = BiomeSets.getTheme(biome, this.random, dimension)`；
  - `SpatialHash.randomFromSeed(long, int, int, int)` 是纯哈希（四组常量多项式 + 旋转混合），不读任何静态状态；
  - `BiomeSets.getTheme(Biome, Random, int)` 按 `BiomeDictionary` 类型收集候选主题，再用传入的 `Random.nextInt` 抽一个。
  → 主题 = f(世界种子, chunkX, chunkZ, 维度, 生物群系)，完全确定。
- **实现**：`DoomlikeLocator` 反射 `SpatialHash.randomFromSeed` 与 `BiomeSets.getTheme`，按生成顺序重放；变体名与 `Theme.name`（即配置文件 `xxx.cfg`）归一化后比对，命中标 `variantConfirmed=true`。
- **UI**：Doomlike 变体的 `≈` 已去掉（与 Roguelike 一致），现在是真过滤。
- **限制**：`isDungeonCenter` 每个候选都要 `getBiome`，预算仍是 20000 chunk；主题只在 `isDungeonCenter` 命中后才重放。

## 6. 原版结构变体（2026-11-03）

- **`minecraft:temple` 四类**：`MapGenScatteredFeature.Start` 的构造器按 biome 分支——
  `DESERT`/`DESERT_HILLS` → 沙漠神殿，`JUNGLE`/`JUNGLE_HILLS` → 丛林神庙，`SWAMPLAND` → 女巫小屋，
  `ICE_PLAINS`/`COLD_TAIGA` → 雪屋（第一版漏了这一类）。
  生成时用的就是这个 biome，事后读同一个 biome 不可能判错，不需要重放随机或方块指纹。
- **`minecraft:village` 五类**：村庄没有类型字段，`StructureVillagePieces.getBiomeSpecificBlockState`
  按 biome 替换建材，所以样式同样是 biome 的纯函数。按 biome 名归为
  plains / desert / savanna / taiga / snowy 五档，其余落到 plains（与原版一致）。
- **实现**：`TempleLocator` / `VillageLocator` 继承 `MapGenLocator`，只覆写 `getVariants` 与五参 `findNearest`。
- **第一版的 hop 逻辑无效（2026-11-03 修正）**：`getNearestStructurePos` 会先把起点换算成
  spacing 单元格再「吸附」回网格（`cell * spacing + rand`），同一格内怎么挪都返回同一个结构；
  单元格宽 `spacing` 个 chunk（默认 32 = 512 格），所以「外推 48 / 64 格」永远跳不出去。
  实测表现：在沙漠神殿里搜丛林神庙/女巫小屋，12 次 hop 全部命中同一座，`0 ms` 返回空。
- **正解**：`MapGenLocator.enumerateCandidates` 直接枚举单元格——对每个 cell 复刻生成器的候选
  （`world.setRandomSeed(cellX, cellZ, salt)` + 两次 `nextInt(spacing-8)`）。
  salt 由子类给出（神殿 14357617、村庄 10387312）。
- **第二次踩坑（2026-11-03 晚）**：第一版用反射的 `canSpawnStructureAtCoords`（SRG `func_75047_a`）确认候选，
  实机日志是 `[TEMPLE] no jungle among 0 structures`——反射调用全部返回 false，一个候选都没留下。
  改为**自己按生物群系白名单确认**（`acceptsBiome`）：原版 `canSpawnStructureAtCoords` 本来就只是
  「chunk 是该 cell 的候选」＋「biome 在白名单里」两件事，后者可以直接做。
  神殿白名单 = `BIOMELIST` 的 7 个（desert / desert_hills / jungle / jungle_hills / swampland /
  ice_plains / cold_taiga），村庄 = `VILLAGE_SPAWN_BIOMES` 的 4 个（plains / desert / savanna / taiga，排除 mutated_）。
- **spacing 自校验**：反射读到的 spacing 不直接信任——先用 `getNearestStructurePos` 拿一座真实结构，
  重放它所属 cell，能复现才采用；否则 9..128 暴力试，仍失败就打 WARN。
  这样反射字段名失效时会退化成「慢但对」，而不是「静默返回空」。
- **验证**：用世界种子离线复刻本算法，神殿候选 `(2328, 3960)`、村庄候选 `(2312, 4136)`
  与实机 `getNearestStructurePos` 的结果逐字一致。
- **无变体的原版结构**：`stronghold` / `monument` / `mansion` / `mineshaft` 都只有一种形态，保持父项即可。
- **变体 id**：`minecraft:temple/desert|jungle|witch_hut|igloo`；
  `minecraft:village/plains|desert|savanna|taiga|snowy`。命中一律标 `variantConfirmed=true`。
- **成本**：半径 10000 时约 1200 个候选，每个一次 `canSpawnStructureAtCoords`（哈希 + biome 查询），
  实测个位数毫秒。

## 7. Recurrent Complex：按结构 ID 精确检索（2026-11-03）

- **数据结构**：`WorldStructureGenerationData` 是**按维度**的 `WorldSavedData`
  （`saves/<世界>/data/reccomplex-structuredata.dat`，下界在 `DIM-1/data/` 同名文件），
  条目字段：`structureID`、`boundingBox`、`blocking`、`firstTime`、`seed`、`rotation`。
  → `entry.getStructureID()` 就是生成时用的结构名，按它过滤是**精确**的，不需要任何重放。
- **变体来源**：结构 id 列表来自两侧——`<gameDir>/structures/active/**.rcst`（RLCraft 自带 202 个）
  与 mod jar 内 `assets/reccomplex/structures/active/**.rcst`（RC 内置 187 个），
  合并去重后 386 个。`.rcig` 不参与：`structures/active` 里的 73 个全是 `inventoryGeneratorID`（战利品表），
  不会生成到世界上。
- **为什么走文件而不是 API**：变体列表在**客户端**取（`GuiStructureSelect` 传 `mc.world`），
  而 RC 的结构注册表 `StructureRegistry.INSTANCE` 只在服务端加载；客户端调
  `WorldStructureGenerationData.get(WorldClient)` 也拿不到存档数据。文件扫描两侧都能跑。
- **匹配**：`structureID` 可能是裸文件名，也可能是包内相对路径，故同时接受「全等」与「路径后缀」两种形态。
- **RLTweaker 会清空记录（2026-11-03 晚，关键）**：`RecurrentHandler.RCCleanup.onWorldLoad` 对每个
  服务端世界调用 `wipeRecurrentData`，直接从 NBT 里删掉 `entries` / `checkedChunks` /
  `checkedChunksFinal` 三个键再写回。所以 RC 自己的 `entryMap` 在 RLCraft 里**永远是空的**，
  实测日志即 `[RC] RC has not generated any structures in dimension 0 yet`。
- **绕法**：清空只动内存，存档文件要到下一次保存才被覆盖。新增 `cache/RecurrentComplexData`，
  在 `WorldEvent.Load` 时直接用 `CompressedStreamTools.read` 解析
  `saves/<世界>/[DIM-x/]data/reccomplex-structuredata.dat`（`data.entries[]`，每项
  `structureID` + `boundingBox` 为 int[6]：minX/minY/minZ/maxX/maxY/maxZ），按文件绝对路径缓存。
  搜索时把「快照」与「本会话 RC 实时 map」合并：快照覆盖历史，实时 map 覆盖本次新生成的。
- **代价**：当日 20:55 那次加载时存档里的 16 条已被 RLTweaker 抹掉且随保存落盘，属既成事实。
- **只读存档还不够（2026-11-03 晚二次修正）**：RC 保存时写回的是**它自己内存里的那张表**，
  而这张表在加载时已被清空——所以存档文件每个会话只剩「本会话新生成的结构」，读它等于每轮丢一次历史。
  因此新增自持存储：`<世界>/rlcompass/rc_structures_<维度>.tsv`（`id \t x \t y \t z`，只追加，按 key 去重）。
  `WorldEvent.Load` 时把「TSV ∪ 存档 dat」合并并回写新增项；`WorldEvent.Unload` 清缓存以便重进世界时重读。
  这样历史结构只丢一次（本次），之后只增不减。
- **方案 A（预测）已证伪（2026-11-03 深夜）**：自然结构在 `RCBiomeDecorator.decorate(world, rand, pos, type)`
  里决定，`rand` 是 `DecorateBiomeEvent.Decorate.getRand()`，也就是**区块装饰阶段共享的 world.rand**；
  `tryGenerate` 用 `rand.nextFloat()` 与 base/total 权重比较。复刻位置等于复刻整个 BiomeDecorator
  的抽取序列（草、树、花、矿石…每次抽取都影响它），只要有一个装饰 mod 插入就全偏——原理上不可靠，放弃。
- **改为在源头记录（2026-11-03 深夜）**：RC 每次放置完成会发
  `ivorius.reccomplex.events.StructureGenerationEventLite$Post`（`getStructureName()` / `getBoundingBox()`，
  继承 `WorldEvent` 带 world）。新增 `cache/RcGenerationRecorder`：以 `WorldEvent` 为参数类型注册监听
  （Forge 会把子类派发给父类监听器），按类名过滤后反射取值，交给
  `RecurrentComplexData.record(...)` 追加到 TSV。
  这样记录**不依赖 RC 的存档文件**：RLTweaker 清空、只保留上一次会话、时间窗——全都不再相关。
  读 dat 的逻辑保留为历史回填；事件反射失败时自动退回该路径。
- **限制**：只有**已生成**的结构能被找到（RC 的定位语义）。未生成的返回「没找到」。
  多人游戏下客户端读的是本地结构包，若服务端包不同步，变体列表可能对不上。
  验证可用 `/rc gen <结构>` 现场生成一座，本会话的实时 map 会立即收录。
- **已知规模**：386 个子项，靠 GUI 筛选框检索（例如输 `Ship` → `P_LargeShip`）。

### 7.2 搜索记忆与建议点（2026-11-03 深夜）

- **动机**：稀有结构靠「扫一片区域」找，不靠一次精确命中。只会返回"最近一个"的定位器，
  第二次搜索毫无意义——除非记住已经查过哪里。
- **`cache/SearchMemory`**：按世界+维度存 `<世界>/rlcompass/search_memory_<dim>.tsv`，
  行格式 `id \t x \t y \t z \t state`，状态 `NEW` / `FOUND` / `MISSING`，同一 key 以最后一行为准（只追加）。
- **状态怎么变**：只有 `NEW → FOUND/MISSING` 是自动的，触发条件是「玩家站在该点 48 格内又搜了一次」——
  此时看 64 格内有没有该结构的**真实记录**：有 → FOUND，没有 → MISSING。
  不猜、不推测，只用可确认的数据。搜过的点之后不再被返回。
- **`RecurrentComplexLocator.suggest`**：当某个结构 id 没有任何记录时，按 8 chunk（128 格）网格从近到远扫，
  跳过搜索记忆里已有的点，用结构自身 `generationBiomes` 判断该生物群系是否**有资格**生成它
  （支持 `$TAG`、`&`、`|`、`!`），返回第一个候选，来源标 `(suggested)`。
- **它不是预测**：RC 的真实落点由区块装饰随机决定（见 7.1），无法复现；能复现的只有资格（生物群系/维度）。
  所以措辞是「这片区域可以生成、你还没查过」，不是「这里有一个」。

## 8. 选择界面：半透明扁平列表（2026-11-03 深夜）

> 参考实现：`https://github.com/MattCzyr/ExplorersCompass`（对照过 `forge-1.16.5` 与 `main` 的 `gui/`、
> `util/RenderUtils`）。下方是最终确认的视觉规格；实现是本项目自己的 1.12.2 代码（`GuiScreen` +
> `GuiSlot` + `drawRect`），不复制上游类。

### 8.1 规格（提取值）

| 元素 | 参数 |
| --- | --- |
| 列表区底色 | 纯黑 `0x000000`，alpha ≈ `0x7F`（半透明），整块列表区一层 |
| 选中行 | **同一层半透明黑再叠一次**（`0x7F` over `0x7F` ≈ `0xBF`），行区 `left-4 → right+4`，上下各多 4px |
| 行宽 | 参考实现是 `默认宽度 + 50`；左右各 4px 内边距 |
| 滚动条 | 位置 `rowRight - 2`，宽 6px；轨道 alpha ≈ `0x2B`，滑块 alpha ≈ `0xF2`；滑块高度按可视比例、最小 32px，两端留 8px |
| 文字 | 左对齐，起始 `rowLeft`；可用文字高 = 行高 − 4 |
| 分隔线 | **没有**，完全靠选中底色区分 |

关键不是颜色，而是**去掉所有行边框**：一层半透明底 + 选中态叠深，仅此而已。

### 8.2 落地映射

- **屏幕背景**：`drawDefaultBackground()`（1.12.2 在位面世界下画 `0xC0101010 → 0xD0101010` 渐变，
  与上游 `renderBackground()` 同一对常量）。列表区再叠 0x7F，实际约 `0xE0` —— 与上游观感一致。
- **列表区**：`StructureSearchList.drawScreen` 先填 `LIST_BACKGROUND = 0x7F000000`，再画行。
- **选中**：`SELECTION_LAYER = 0x7F000000`（与底色同值），矩形 `left-4, y-4 → right+4, y+height+4`，
  由 scissor 裁进视口，所以贴边行不会画到列表外。
- **行宽 +50 的处理**：上游把列表画成 `width + 110`（左右各溢 55px）、`super.getRowWidth()` = 220，
  再 +50 = 270，行因此在屏幕外延展。我们的列表不越界，按它的**可见效果**实现：行贴满面板，
  文字列 14px（`+`/`-` 占 3..9px 的 gutter），子项再缩进 10px。真按 +50 会让左右各溢出 25px。
- **滚动条**：`right-8 .. right-2`（6px，距边 2px），轨道 `0x2B000000`，滑块 `0xF2000000`；
  滑块高 `max(32, viewport²/total)`，上限 `viewport-16`，行程两端各留 8px。
- **文字**：行高 50，可用 46，三行 10px 行距，偏移 `y+11 / +21 / +31`（名称白、`Source`/`ID` 为 `0xFF808080`）。
- **零边框**：行、按钮、输入框都不画 border/shadow；按钮 hover 用 `0xB8`、禁用 `0x2B`，文字恒白。

### 8.3 已废弃的旧口径

- 上一轮记录的「整屏 0x7F 面板 + 选中叠 `0x3F000000`」**作废**：`0x3F` 在上游仓库里不存在，
  且层级也不对——面板是**列表区**，不是整屏；选中是**同值再叠一层**，不是 25% 的第三种透明度。
- `GuiTheme` 现在只有 `LIST_BACKGROUND` / `SELECTION_LAYER` / `SCROLLBAR_WIDTH` / `SCROLL_TRACK` /
  `SCROLL_THUMB` 与文本色；`CONTROL*` 是按钮状态。

**未做**：`GuiSuggestionWarehouse` 仍是原版 `drawDefaultBackground` + `GuiButton`，没有套这套列表规格。

## 9. 待办

1. **`UltraRarePredictor` 的 vanilla 锚点还没接枚举器**（`locate/impl/UltraRarePredictor.java` 类注释的 tier 2）。
   目前 `hasVanillaAnchor` 只能判断「这个结构声明了 vanilla 生成信息」，候选仍然全部退回生物群系层，
   给的是「有资格」而不是「原版结构所在位置」。
   下一步：把 §6 的 `MapGenLocator.enumerateCandidates` 接进来，按 `vanilla` 段的结构类型映射
   （stronghold / village / temple / monument / mansion / mineshaft）复用对应 salt 与 spacing，
   命中点直接作为候选，来源标 `vanilla anchor`。
2. **BattleTowers 的预测公式已回滚**：上一轮按 `BattletowerPositionsFile` 的网格公式预测塔位，
   实机核对发现与真实塔位的**奇偶**不符（预测点与实测塔成对错位），代码已撤，
   当前只剩 region 扫描（§4）这条记录型路径。
   下一步要**受控实验**：固定种子 + 已知塔位序列（逐个 `/tp` 验证），先证伪「偏移是常量还是按 chunk 奇偶」，
   再决定是否重写；实验结论出来前不要把公式放回主路径。
3. `GuiSuggestionWarehouse` 仍是原版 `drawDefaultBackground` + `GuiButton`，与 §8 的扁平主题不一致。

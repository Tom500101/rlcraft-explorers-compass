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
> 上游 `LICENSE.md` 是 **CC BY-NC-SA 4.0**——上一轮说「仓库里没有 LICENSE」是错的（当时只看了
> 文件树顶层，漏了 `LICENSE.md`）。贴图沿用 `NOTICE` 里已有的署名与同协议，代码只借鉴数值、不搬实现。

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

### 8.4 建议点仓库（2026-11-03 深夜）

- 同一套扁平列表：`drawDefaultBackground` + 列表区 0x7F、选中叠 0x7F、6px 滚动条，行高 20。
- 状态不再循环点击：点行尾的状态格弹出三项菜单（未探索 / 已找到 / 未找到），菜单是近不透明底
  (`0xE6000000`) + 1px 描边，避免落在行上看不清；点空白处只关菜单，不会误触下面的行。
- 底部按钮换成 `TransparentButton`；标题居中。

**未做**：仓库左上角没有列头（id / 坐标 / 状态），上游同样没有；条目多时靠 id 前缀辨识。

## 9. 待办

1. **BattleTowers 的预测公式已回滚**：上一轮按 `BattletowerPositionsFile` 的网格公式预测塔位，
   实机核对发现与真实塔位的**奇偶**不符（预测点与实测塔成对错位），代码已撤，
   当前只剩 region 扫描（§4）这条记录型路径。
   下一步要**受控实验**：固定种子 + 已知塔位序列（逐个 `/tp` 验证），先证伪「偏移是常量还是按 chunk 奇偶」，
   再决定是否重写；实验结论出来前不要把公式放回主路径。
2. **实机验收**：三处实机 bug 的代码修复已完成并重新编译部署（见 §12），但都还没进游戏看过表现：
   ① `VillageSavannaChandlery` 应能搜到并给出 vanilla anchor 村庄中心；
   ② 仓库 `Done` 按钮应能关闭界面；③ 战斗高塔落点应更准（质心）且沙/冰塔检测恢复。
   验收清单见 §11 第 3、4 项补充。
3. 多人生存：仓库只读单机存档路径，联机下为空；需要的话要走服务端包。

## 10. RC 的 vanilla 锚点（2026-11-03 深夜）

- **机制（反汇编确认）**：`ivorius.reccomplex.world.gen.feature.structure.generic.generation.VanillaGeneration`
  只被 `GenericVillageCreationHandler` 消费，后者实现 `VillagerRegistry.IVillageCreationHandler`。
  也就是说，声明 `generationInfos[].type="vanilla"` 的结构会被注册成**村庄部件**，只能长在原版村庄里。
  `id` 是 `randomID(VanillaGeneration.class)` 生成的随机串（如 `vanilla_c948bef2`），**不代表原版生成器类型**，
  不要按 id 去映射 village/temple/mineshaft。
- **规模**：398 个 `.rcst`（jar 187 + 包 211）里 30 条带锚点，全部是 `Village*` 部件
  （Chandlery / Glassworks / Kitchen / Library / Stonemason / Tannery / Church / ForgeLarge /
  GuardTower / Inn…），`spawnShiftY` 多为 -2/-3，与「村庄地面下的地基」一致。
- **实现**（`UltraRarePredictor`）：
  - `ANCHORS` 缓存每条结构的 `biomeExpression` 列表（空列表 = 不限生物群系，与「没有锚点」区分开）；
  - 有锚点时先走 `VillageLocator.enumerateVillages`（复用 §6 的 cell 枚举 + spacing 自校验），
    再用条目自己的表达式过滤，最近且未查过的村庄中心返回 `Suggestion(pos, vanillaAnchor=true)`；
  - 没有村庄命中才退回生物群系网格（并打一条 `falling back to biomes`）；
  - 来源串区分 `Recurrent Complex (vanilla anchor)` 与 `(suggested)`。
- **顺带修掉的解析 bug**：RC 用 Gson 的 HTML-safe 模式写包，`&` 存成 `\u0026`、`=` 存成 `\u003d`。
  旧的手写扫描直接拿原始文本当表达式，含 `&` 的连接式（jar 内 21 条，如 `$THEMED_TREES & !$BIRCH_TREES`）
  永远匹配不上。现在两个扫描器都先 `unescapeJson`。
- **表达式求值**：换成递归下降解析器，支持 `!`、`&`、`|`、括号、`$TAG`、`type=TAG`（兼容 `type=$TAG`）、
  `id=`、裸生物群系名。旧实现按字符 split，`!($HOT | $DRY)` 会被拆坏。
- **离线验证**：用同一套扫描逻辑复刻成 Python，对 jar 内 187 个结构比对「手写扫描 vs `json.loads`」，
  锚点表达式 187/187 一致。
- **根因（实测 bug，2026-11-04）**：`filters()` 原本只查磁盘 `structures/active`，但 `VillageSavannaChandlery.rcst`
  之类和所有 `Village*` 部件一样**只活在 RC 的 jar 内**（`assets/reccomplex/structures/active/structures/village/savanna/...`），
  磁盘永远读不到 → `filters()` 返回空、`ANCHORS` 永不写入、`hasVanillaAnchor` 恒 false、锚点静默失效，
  日志里 `-> null`。已加 jar 回退（`Loader.getIndexedModList().get("reccomplex")` 取 jar，`findRcstEntry` 按 `<id>.rcst`
  名匹配，`extractStructureJson` 取 `structure.json`），磁盘优先、jar 兜底。
- **验收**：搜 `VillageSavannaChandlery` 应出现日志
  `[RC] vanilla anchor for 'VillageSavannaChandlery': village at (x, z) matches [type=savanna]`，
  tp 过去落在村庄里；再搜一次换到下一座村庄。

## 11. 实机验收清单（本轮改动）

1. **排版**：标题在左栏上方居中；搜索框在列表列顶部；四个按钮 110×20 竖排在左侧，Cancel 贴底；
   列表从 x=130 铺到右边距 10。没有副标题、计数行、@mod 提示。
2. **列表**：行高 50、三行文字（名称 / Source / ID）；选中行比列表底明显更深且四周各外扩 4px；
   无分隔线；滚动条 6px，滑块接近不透明。
3. **仓库**：从选择界面 `Warehouse` 进入；点行尾状态格弹三项菜单；选择后行状态即时更新；
   `Search again` 只在选中行后可用。
4. **锚点**：搜 `VillageSavannaChandlery`，日志应出现
   `[RC] vanilla anchor for 'VillageSavannaChandlery': village at (x, z) matches [type=savanna]`，
   tp 过去应落在村庄里；再搜一次应换到下一座村庄。


## 12. 三处实机 bug 修复（2026-11-04）

基于 `latest.log` 三条反馈（搜不到 `VillageSavannaChandlery`、仓库 `Done` 按钮无反应、战斗高塔传送无塔），
定位并修复了三个独立 bug，已重新编译部署（BUILD SUCCESSFUL，备份 `build/deploy-backup/20261104-005637`）。

### 12.1 问题 A：搜不到 `VillageSavannaChandlery`（锚点静默失效）
- **根因**：`UltraRarePredictor.filters()` 只查磁盘 `structures/active`。RC 内置的 `Village*` 部件
  （含 `VillageSavannaChandlery`）只存在于 RC 的 jar 内，磁盘无此 `.rcst` → 过滤为空 → `ANCHORS` 不写入 →
  `hasVanillaAnchor` 返回 false → `RecurrentComplexLocator.suggest` 直接 `-> null`，玩家收到"周围没有该结构"。
- **修复**（`UltraRarePredictor`）：`readGenerationJson` 改为磁盘优先、jar 回退。
  - `readRcstFromJar`：`Loader.instance().getIndexedModList().get("reccomplex").getSource()` 取 jar，
    `findRcstEntry` 按 `<id>.rcst` 文件名匹配（忽略路径），`extractStructureJson` 从 rcst（zip）取 `structure.json`。
  - 离线已确认 `VillageSavannaChandlery.rcst` 的 `generationInfos` 含 1 条 `type=vanilla`，
    `biomeExpression=type=savanna`，`spawnShiftY=-2`。
- **验收**：日志出现 `[RC] vanilla anchor for 'VillageSavannaChandlery': village at (x, z) matches [type=savanna]`。

### 12.2 问题 B：仓库 `Done` 按钮无反应
- **根因**：被改写的 `GuiSuggestionWarehouse.handleMouseInput()` 在 `wheel == 0`（即普通点击）时直接 `return`，
  跳过了 `super.handleMouseInput()`；而基类正是在点击时调 `mouseClicked`（按钮和列表点击都靠它），
  于是所有点击被吞掉，`Done` 按钮永不触发 `actionPerformed`。
- **修复**：恢复 `super.handleMouseInput()` 调用——滚轮事件仍先处理（关菜单 + 滚列表），然后照常派发点击。
- **验收**：仓库界面点 `Done` 应关闭界面回到父界面。

### 12.3 问题 C：战斗高塔传送过去没有塔
两个独立根因，都已修：
- **坐标偏移（落点不准）**：原报告坐标为 chunk 中心 `chunkX*16+8`，改为方块质心 `chunkX*16+stats.centerX()`
  （`centerX()/centerZ()` 为该 chunk 内被识别材质方块的 x/z 平均），落点更贴近真实塔身。
- **致命：高度检测恒为 0（沙/冰塔检测失效）**：旧 `readSectionY` 只回扫 40 字节，但该 Anvil 格式里 `Y` 标签
  位于 `Blocks` + `Data`（2048 字节）之后，Java 永远扫不到 → 高度恒 0，原 `height()>=MIN_TOWER_HEIGHT`
  的沙/冰塔分支从未生效。修复为**双向查找**：先向前跳过 `Data` 数组再找 `Y`，再向后回退找 `Y`，
  均校验值 ∈ [0,15]。新增 `scanForwardY` / `matchY` helper。
- **撤销的误改**：曾给圆石家族加高度门槛（`MIN_COBBLE_TOWER_HEIGHT`），但绝对 Y 跨度区分不了"扁平误报"
  与"真塔"（验证 chunk (6,6) 真塔高度仅 24，村庄/平台也占 1-2 区块层），且会误删矮塔——已撤销，
  cobble 家族恢复为纯块数判断（`MIN_COBBLE` / `MIN_MOSSY`）；`MIN_TOWER_HEIGHT` 仅沙/冰塔仍用。
- **诚实边界**：基于方块计数的 region 扫描本就是启发式，村庄或玩家地面建筑仍可能误报；
  本次通过质心修正 + `readSectionY` 修复提升精度，但不保证 100% 命中。
- **验收**：沙/冰塔应能重新被检出（高度不再恒 0）；cobble/mega/netherrack 家族落点更接近塔身。

### 12.4 部署
- 构建：`python mc_gradle.py --project . --task build`（BUILD SUCCESSFUL，约 11s）。
- 部署：`deployToRlcraft` 已替换
  `C:/Users/ww137/AppData/Roaming/.minecraft/versions/RLCraft/mods/rlcraft-explorers-compass.jar`，
  旧 jar 备份于 `build/deploy-backup/20261104-005637`。

## 13. 新增两个模组的结构：冰与火龙巢 + Lycanites 地牢（2026-11-04）

用户要求把「冰与火」龙巢与「恐怖生物」地牢纳入指南针；同时 minecraft 目录已迁移到
`D:/MinecraftData/versions/RLCraft`，`gradle.properties` 的 `rlcraft_mods_dir` 已同步改到新路径
（`deployToRlcraft` 实测输出到 `D:/MinecraftData/versions/RLCraft/mods`）。

### 13.1 版本核实（先看 jar，不采信通用资料）
- 实际 jar：`iceandfire-1.7.1-1.12.2.jar`（`modid=iceandfire`，version 1.7.1）、
  `lycanitesmobs-1.12.2-2.0.8.9.jar`。另有 `ice-rlcraft-optimizer-0.10.0.jar`，不影响生成记录。
- **这个版本没有电龙**：`IceAndFireConfig` 无任何 lightning 字段，全 jar 0 个 class 提到 lightning；
  `StructureGenerator` 只有 `FIRE_DRAGON_ROOST` / `ICE_DRAGON_ROOST` 与对应的 cave。
  所以只提供火龙巢 / 冰龙巢两种变体，电龙巢不是本整合包的内容（那是更新版本 I&F 的东西）。
- 龙巢生成：`event.StructureGenerator.generate` 按 chunk `Random.nextInt(400)` 判定
  （`Generate Dragon Roost Chance=400`），**没有** MapGenStructure、没有位置列表、没有 WorldSavedData，
  随机流与同 chunk 其它 feature 共用，无法像 §6 的 MapGen 网格那样重放 → 只能做记录型定位。
- Lycanites：`ExtendedWorld`（WorldSavedData）的 `public Map<UUID,DungeonInstance> dungeons` 是权威记录。
  `DungeonInstance` 有 `originPos` / `schematic` / `chunksBuilt`；`readFromNBT` 会用
  `DungeonManager.getSchematic(NBT 的 Schematic)` 恢复 schematic，重启后 `schematic.name` 仍可读。
  存档 `data/LycanitesMobs.dat` 实测有 6 条 `Dungeons`，例如
  `streamshrine @ (4423,69,5415)`、`desertcrypts @ (2183,69,4999)`、`lushtomb @ (711,67,71)`。

### 13.2 关键工程点：1.12.2 的 12 位方块 ID
- `level.dat` 的 `FML.Registries["minecraft:blocks"].ids` 给出存档里的 ID→名字映射：
  IAF 977–1017、Lycanites 1318–1460，**全部 > 255**。
- 1.12.2 Anvil 用 `Blocks`(4096 低 8 位) + `Add`(2048 字节 nibble，第 8–11 位)。
  不读 `Add` 会把每个模组方块误认成某个原版方块，一个都找不到。
- 实现不硬编码 ID：扫描时从 `Block.REGISTRY` 反查名字→`Block.getIdFromBlock`，
  `int[4096] categoryById` 直接按 12 位 id 索引；整合包增删模组导致 ID 漂移时仍正确。
- 先检查 `Add` 是否存在、再检查 nibble 里有没有 0x3/0x5 段作为快速排除，避免全量遍历空 section。

### 13.3 阈值来自存档实测，不是猜
对 `测试` 存档 94 个 region / 9982 个 chunk 离线扫描（Python 复刻同一套规则）：
- 火龙巢：`chared_*` 成片 40–621 个/chunk，`goldpile` 只在龙巢中心 1–10 个；
  → `surface >= 100` 或 (`treasure >= 1` 且 `surface >= 40`)，有 treasure 标 confirmed。
- 冰龙巢：`frozen_*` / `dragon_ice*` + `silverpile`，对称处理；本存档未出现（玩家没去过雪原龙巢）。
- Lycanites 主题方块成片 154–499 个/chunk，零星散落仅 1–13 个 → 阈值 80。
- 同 family+kind 且距离 <= 64 的 hit 合并成结构质心。
- 复刻结果：**3 个火龙巢**（`(214,38)` confirmed、`(2469,5237)`、`(3080,5515)` confirmed）
  + 10 处主题聚集（stream / desert / lush / ashen），与 `LycanitesMobs.dat` 的记录区域吻合。

### 13.4 实现与接线
- 新增 `cache/ModdedStructureRegionScanner.java`：一次扫描同时产出龙巢（fire/ice）与
  7 种 Lycanites 主题 hit；按 mtime 缓存 + 后台预热；支持所有维度（下界地牢需要）。
- 新增 `locate/impl/DragonRoostLocator.java`：id `rlcompass:dragonroost`，变体 `/fire` `/ice`。
- 新增 `locate/impl/LycanitesDungeonLocator.java`：id `rlcompass:lycanitesdungeon`，
  **优先反射 `ExtendedWorld.getForWorld(world).dungeons`**（精确、带 schematic 名、confirmed），
  反射不可用或为空才降级 region 扫描（只带主题、unconfirmed）。
  变体从 `DungeonManager.getInstance().schematics` 动态读取，`*_random` 折叠到主名。
  9 个内置 schematic → 7 个变体：Aberrant Station / Demonic Temple / Ashen Mausoleum /
  Desert Crypts / Lush Tomb / Shadow Labyrinth / Stream Shrine。
- 接线：`ModLocatorRegistrar` 注册两个 locator；`StructureNameUtil` 加显示名；
  `RLCompassMod.serverStarted` 预热主世界 / 下界 / 末地。
- **地下龙穴**：已在 §14 用形状检测补上（球腔的空 Y 层 + 焦黑/冰封石材质）。

### 13.5 诚实边界
- 记录型路径：只能找到**已经写进 region 文件**的结构，没探索过的区块搜不到。
- Lycanites 的 6 条记录里 5 条 `ChunksBuilt=0`（已规划、逐块建造未完成），tp 过去会触发
  chunk 生成把地牢建出来；若暂时是空地，等区块加载完再看。
- 龙巢的 `chared_*` 也可能由龙息烧毁地形产生（无金堆佐证时只是候选，不标 confirmed）；
  Lycanites 主题方块也可能是玩家搬运的散块（阈值 80 已排除零星 1–13 的情况）。
- 扫描是启发式；精确路径（Lycanites 自己的 DungeonInstance）优先级更高。

### 13.6 待实机验收
1. 选择界面出现 `Dragon Roost` 与 `Lycanites Dungeon` 两行，展开后分别有 2 / 7 个变体。
2. 搜 `Dragon Roost` 应指向上述 3 个火龙巢之一；`/fire` 能过滤，日志出现
   `[MODSCAN] region scan found N structures under ...` 与 `[MODSCAN] resolved ... block ids`。
3. 搜 `Lycanites Dungeon` 应返回 `LycanitesMobs.dat` 里的 6 个 `OriginPos` 之一，
   source 为 `LycanitesMobs`（而非 `LycanitesScan`）；按变体搜索能按 schematic 过滤。
4. 进下界搜 `Demonic Temple` / `Ashen Mausoleum`。

### 13.7 部署
`python mc_gradle.py --project . --task build` → BUILD SUCCESSFUL（14s），
jar 303366 B，已确认含 `ModdedStructureRegionScanner` / `DragonRoostLocator` /
`LycanitesDungeonLocator` 的 class；`deployToRlcraft` 替换到
`D:/MinecraftData/versions/RLCraft/mods/rlcraft-explorers-compass.jar`，
旧 jar 备份 `build/deploy-backup/20261104-153833`。

## 14. 地下龙穴：球形空腔的形状检测（2026-11-04）

用户要求补上地下龙穴（Dragon Caves），并明确"最核心的特征是巨大的、近似球形的空腔，
火龙穴由焦黑石、冰龙穴由冰封石构成"。

### 14.1 生成器实际行为（反编译 IAF 1.7.1 确认）
`WorldGenFireDragonCave.func_180709_b` / `WorldGenIceDragonCave`：
- `size = 75 + rand(50)`，`half = size/4`（18–31），`inner = half - 2`（16–29）。
- 球心 **`Y = 20 + rand(20)`（20–39）**，纯地下；调用前还要求该位置不是空气。
- 四层球体：
  1. 用 `half` 半径把球内填成 **stone**；
  2. 用 `inner` 半径、垂直半轴 `inner/2` 挖成 **air**（扁椭球空腔）；
  3. 用 `inner` 作三轴半径的 **球**，对球内非空气方块调用 `setOres`：
     40–50 区间由 `silverOre`/`charedStone`（冰版 `sapphireOre`/`frozenStone`）与
     `charedCobblestone`（冰版 `frozenCobblestone`）各半填充；
  4. 在空腔底部放 `goldPile` / `silverPile`，并生成一条 `EntityFireDragon` / `EntityIceDragon`
     （`growDragon(size)` + `setAgingDisabled(true)`）。
- 所以龙穴 = **球形矿壳 + 内部扁空腔**；龙巢 = 从地表往下烧实的球冠。两者材质相同。

### 14.2 为什么不能用 Y 跨度 / 方块数
实测：龙巢 (13,1) 的焦黑石 Y 9–45（跨度 36），龙穴 (154,327) 的 Y 8–66（跨度 58）——
数量与跨度都重叠。真正的区别在**内部的空层**。

### 14.3 判据（形状检测）
在 `ModdedStructureRegionScanner` 中，每个 chunk 为 fire / ice 各维护一个 256 层的 Y 直方图：
- 找出该 chunk 内该类方块的最小/最大 Y；
- 统计两者之间**完全没有该方块**的连续层数 `maxGap`；
- `span >= 24` 且 `maxGap >= 16` → 该 chunk 有"空腔证据"；
- 同一簇（同材质、距离 ≤64）内**任一 chunk** 有证据 → 整簇判为 `dragoncave`，否则 `dragonroost`。

阈值 16 同时是几何下界：矿壳垂直半轴 `inner`(16–29) 减去空腔半轴 `inner/2`(8–15)，
空洞约 `inner` 层。实测：真龙穴中心 chunk `maxGap=42`；龙巢最大的 chunk 只有 9–12
（地表起伏造成的假空洞），16 正好落在两者之间。

**必须逐 chunk 判定**：最初版本把整簇直方图并起来再找空洞，结果 3 个龙巢全被误判成龙穴——
龙巢的 8 个 chunk 分别覆盖 Y 15–23 和 37–46，并集里 24–36 天然为空。这个坑已写进代码注释。

### 14.4 实测结果（`测试` 存档，94 region / 9982 chunk，Python 复刻同一套规则）
```
ROOST fire at (  214,   38) chunks= 8 maxGap= 9 confirmed=True
ROOST fire at ( 3080, 5515) chunks= 6 maxGap=12 confirmed=True
CAVE  fire at ( 2469, 5237) chunks=11 maxGap=42 confirmed=False
```
真值：`chunk(154,327)` 的实体列表里有 `iceandfire:firedragon @ (2472.5, 37.5, 5240.5)`，
与判出的龙穴 (2469, 5237) 相差 3 格，**方向与位置都对上**。该 chunk 的直方图为
Y 8–21 与 53–66 两段、22–52 全空，正是球腔的上下壳。

### 14.5 代码变更
- `ModdedStructureRegionScanner`：
  - `Hit` 增加 `yOccupied` / `minY` / `maxY` / `caveEvidence`；
  - `Stats` 增加逐层直方图与 `hasCavity(category)`；
  - `scanSections` 解析 section 的 `Y`（双向查找，Anvil 可能把 Y 写在 Blocks+Data 之后）；
  - `merge` 改为 `Cluster`，`caveEvidence` 按簇 OR。
- `DragonRoostLocator`：支持两个 id —— `rlcompass:dragonroost`、`rlcompass:dragoncave`，
  各带 `/fire`、`/ice` 变体（共 4 个）。
- `StructureNameUtil`：新增 `rlcompass:dragoncave` → `Dragon Cave`。

### 14.6 部署
BUILD SUCCESSFUL（13s），`deployToRlcraft` 替换
`D:/MinecraftData/versions/RLCraft/mods/rlcraft-explorers-compass.jar`，
旧 jar 备份 `build/deploy-backup/20261104-155344`。

## 15. Bug 修复：Add 数组在 Blocks 之前，导致上壳丢失、龙穴被报成龙巢（2026-11-04 下午）

### 15.1 现象
实机搜索 `rlcompass:dragonroost`，第二次返回 `(2599, 64, 5335)`，tp 过去是**龙穴**：
日志 `findNearest(rlcompass:dragonroost variant=.../fire) from (2452, 5234) -> (2599,64,5335) dist=178`。
同时每次搜索都有 `IceAndFireScan took 3370 ms` 警告。

### 15.2 根因：`findAddTag` 向前优先拿错了 section 的 Add
这个存档的 Anvil 布局是 **`Add` 紧贴在 `Blocks` 之前**（payload 结束正好早 2048 字节），
而 `Y` 在 `Blocks + Data` 之后（+6172）。原实现先向前找 `Add`，于是拿到的是**下一个
section 的 Add**，`possible` 校验失败，整个 section 被丢弃：

```
chunk(162,333)
Blocks@3180  addFwd=-1     addBack=1132  used=1132  possible=True   chared=2201  ✓
Blocks@25842 addFwd=36154                possible=False            chared=0     ✗ 丢了 1833 个
```

上壳（Y 33–42，1833 个焦黑石）整段丢失，直方图只剩 Y 2–11 的下壳：
`maxY` 从 42 掉到 11，`span` 从 40 掉到 9，低于 `CAVE_SPAN_MIN=24` → 判 ROOST。
**这才是"龙巢坐标实际是龙穴"的真正原因**，与判据阈值无关。

### 15.3 修复
- `findAddTag` 改为**向后优先**（`blocksPayload - 2600` 起），找不到再向前（兼容原版顺序）。
- `Cache` 改为**按文件增量**：只重读 mtime 变化的 region 文件。日志里 3.3 s 的全量重扫
  （94 个文件、每次搜索都发生，因为游戏持续写 region）降到只重读 1–2 个文件。

### 15.4 修复后的分类（真实存档，Python 复刻同一套规则）
```
CAVE  fire ( 215,   39) chunks=9  minY= 8 maxY=46 maxGap=19 confirmed=True
ROOST fire (2263, 5175) chunks=5  minY=51 maxY=67 maxGap= 0 confirmed=True
CAVE  fire (2599, 5335) chunks=9  minY= 2 maxY=42 maxGap=21 confirmed=True
CAVE  fire (3079, 5511) chunks=9  minY= 8 maxY=42 maxGap=19 confirmed=True
CAVE  fire (2471, 5239) chunks=21 minY= 8 maxY=66 maxGap=31 confirmed=False
```

### 15.5 实体仲裁（关键）
扫描各 chunk 的 `Entities`，带 `AgingDisabled=1`（只有龙穴生成时设置）的龙：

| chunk | 实体 | Y | AgingDisabled |
|---|---|---|---|
| (13,2) | iceandfire:firedragon | 18.9 | 1 |
| (154,327) | iceandfire:firedragon | 22.0 | 1 |
| (162,333) | iceandfire:firedragon | 12.9 | 1 |

三条都在地下 Y 12–22，**证明这三个"CAVE"判定都是真的龙穴**，不是误判。
其中 (215,39) 与 (3079,5511) 的地表同时被龙巢烧过（其余 8 个 chunk 直方图连续、无空层），
属于**龙巢与龙穴重叠**：地下是龙穴，地表是龙巢，两种结构共用同一片焦黑石。
因此这两个位置会出现在 Dragon Cave 列表里；搜 Dragon Cave 得到同一坐标，tp 到地表仍是龙巢。

### 15.6 已知限制
- 纯龙穴与"龙巢+龙穴重叠"在方块层面无法区分：龙穴的上壳本身就是一层地表焦黑石，
  与龙巢的烧焦球冠在直方图上完全同形。只能靠实体 `AgingDisabled` 仲裁，但实体可能游荡/被杀。
- 当前策略是"有空洞证据即归 Dragon Cave"，宁可少报龙巢，不再把龙穴错报成龙巢。

## 16. 改用 Y 最低点判定，取代空洞形状检测（2026-11-04 傍晚）

### 16.1 用户的判断是对的
用户指出"龙巢在地面，龙穴在地下，用 Y 值判定就行"。全量扫描 53 个含焦黑石的 chunk 后确认：
**龙穴与龙巢的最低点完全不重叠**。

| 类型 | chunk | minY |
|---|---|---|
| 龙穴 | (162,333) | 2 |
| 龙穴 | (161,333)/(163,333) | 3 |
| 龙穴 | (154,327)/(13,2)/(192,344) | 8 |
| 龙巢 | (13,1)/(13,3)/(14,2) | 9 |
| 龙巢 | (12,2) | 10 |
| 龙巢（高海拔） | (141,323) | 51 |

判据：**`minY <= 8` → Dragon Cave，否则 Dragon Roost**（`CAVE_DEEP_FLOOR_MAX = 8`）。
几何依据：龙穴球心固定 `Y = 20 + rand(20)`（20–39），半径 16–29，壳底必然落到 Y 0–8；
龙巢从地表往下烧，最低也只到 Y 9。

### 16.2 为什么之前用"Y 范围"看起来重叠
龙穴半径大，**maxY 会顶到地表甚至更高**（(154,327) maxY=66、(162,333) maxY=42），
与龙巢的 maxY（41–67）交叠。但**最低点**是干净的。之前用 span/gap 是绕远了。

### 16.3 分类粒度改为 chunk 级
`emitRoost` 里逐 chunk 判定 family（cave / roost），`merge` 按 family 分簇，
所以"地表龙巢 + 地下龙穴"重叠的区域会产出**两个 hit**（坐标相差 1–2 格），
而不是被合并成一个猜测。`Cluster.toHit` 不再改写 family。

### 16.4 结果（真实存档）
```
CAVE  fire (  216,   40) chunks= 1 minY= 8 maxY=46 confirmed=True
CAVE  fire ( 2471, 5240) chunks= 1 minY= 8 maxY=66 confirmed=False
CAVE  fire ( 3080, 5511) chunks= 1 minY= 8 maxY=42 confirmed=True
CAVE  fire ( 2599, 5335) chunks= 9 minY= 2 maxY=42 confirmed=True
ROOST fire (  215,   39) chunks= 8 minY= 9 maxY=45 confirmed=True
ROOST fire ( 2263, 5175) chunks= 5 minY=51 maxY=67 confirmed=True
ROOST fire ( 3079, 5511) chunks= 8 minY= 9 maxY=41 confirmed=True
ROOST fire ( 2471, 5239) chunks=20 minY= 9 maxY=65 confirmed=False
```
实体验证：四个 CAVE 分别对应 chunk (13,2) Y=18.9、(154,327) Y=22.0、
(192,344) Y=16.9、(162,333) Y=12.9，均 `AgingDisabled=1`（龙穴专属）。
`(2599,5335)` 没有对应 ROOST，说明那片只有龙穴、没有地表龙巢。

### 16.5 边界
- 冰龙穴在雪原高海拔，球心仍在 Y 20–39，壳底同样 ≤8，判据同样成立（本存档未出现冰龙穴）。
- 重叠区域会同时列出 roost 与 cave，坐标相差 1–2 格；tp 到地表是龙巢，原地向下是龙穴。

## 17. 用宝藏深度判定龙穴；缓存仓库改造（2026-11-04 晚）

### 17.1 判据改为"宝藏 Y"（用户指出）
用户指出：龙穴顶部有迷惑性，但**箱子和金堆只在底部**。实测每 chunk 的 goldpile Y：

| chunk | 归属 | gold 数 | gold Y |
|---|---|---|---|
| (162,333) | 龙穴 | 54 | 12 |
| (161,333)/(163,333)/(162,332)/(162,334) | 同一龙穴 | 23-35 | 12-21 |
| (13,2) | 龙穴 | 59 | 18 |
| (13,1)/(12,2)/(14,2) | **同一龙穴的侧壁** | 18-27 | 18-25 |
| (192,344) | 龙穴 | 46 | 16 |
| (191,344) | 同一龙穴侧壁 | 16 | 16-23 |
| (141,323) | 真龙巢 | 23 | **57-64** |

结论：**龙穴宝藏 Y 12–25，真龙巢 Y 57–64**，阈值取 **30**（`CAVE_TREASURE_MAX_Y`）。
这也证明 (13,1) 一带不是"龙巢+龙穴重叠"，而是**整片都是一个龙穴**——用户第一次 tp 看到的地表
焦黑是它的上壳，不是龙巢。

判定顺序（`Cluster.toHit`）：
1. 簇内有宝藏 → `treasureMinY <= 30` 即 CAVE，否则 ROOST；
2. 无宝藏（如 (154,327) 一带 setGoldPile 未触发）→ 退回壳底 `minY <= 8`；
3. 再退回空层 `gap >= 13`。

修复后（真实存档）：
```
CAVE  fire (2599,5335) treasureY=12
CAVE  fire ( 215,  39) treasureY=18
CAVE  fire (3079,5511) treasureY=16
CAVE  fire (2471,5239) treasureY=— (minY=8 兜底)
ROOST fire (2263,5175) treasureY=57   ← 全局唯一真龙巢
```

### 17.2 缓存仓库（增删改查）
- **增**：`ItemExplorersCompass.search` 每成功一次就 `SearchMemory.remember(id, pos)`。
- **查**：界面顶部筛选框，按结构名或坐标过滤，位置/尺寸与主 GUI 完全一致
  （`LIST_LEFT=130, y=10, 150x20`，占位符同款）。
- **改**：点状态格弹三态菜单（未探索/已找到/未找到）；id 与坐标不可改，它们是世界事实。
- **删**：每行最右侧 `x` 键，`SearchMemory.remove` 从 TSV 中真正删掉该行（重写文件），
  该位置重新可被搜到。
- **已记录则跳过**：
  - `IStructureLocator` 新增带 `SearchMemory` 的重载；
  - `DragonRoostLocator` / `LycanitesDungeonLocator` 在候选循环里跳过已记录项，**自动前进到下一个**；
  - `StructureLocatorRegistry` 统一兜底：结果已在仓库则丢弃并记日志，删除该行后恢复。
- **布局防重叠**：行高 28，固定四列 —— 名称（左上）、坐标（左下）、状态按钮（右侧 66px）、
  删除键（最右 16px）。名称按 `statusLeft - 6 - textLeft` 截断，不再与坐标右对齐文字相撞。

### 17.3 部署
BUILD SUCCESSFUL（12s），部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261104-190231`。

## 18. 结构维度标注 + "不在当前维度"返回（2026-11-04 深夜）

用户要求：主界面每个结构标注会刷新在哪个维度；搜索提示新增一种返回「该结构不在当前维度」。

### 18.1 维度表的来源（不是猜的）
新增 `util/StructureDimensions.java`，手工维护 id → 维度，来源逐条核实：

| 结构 | 维度 | 依据 |
|---|---|---|
| vanilla 六种（stronghold / village / mineshaft / temple / monument / mansion） | 主世界 | 都注册在主世界的 MapGen |
| `rlcompass:ruin`、`roguelike:dungeon`、`doomlike:dungeon` | 主世界 | 生成器绑定主世界 |
| `rlcompass:dragonroost`、`rlcompass:dragoncave` | 主世界 | IAF `StructureGenerator` 入口只在主世界 |
| `rlcompass:battletower` | 主世界 + 下界 | 模组有 netherrack 塔；scanner 按 world 取 region 目录，两界都能扫 |
| `rlcompass:lycanitesdungeon` | 主世界 + 下界 + 末地 | 各 schematic conditions 的并集 |
| `reccomplex:structure` | **未知（不入表）** | 结构包含下界/末地体，同一 id 无法断言 |

Lycanites 变体的维度直接取自 jar 内每个 schematic 的 `conditions.dimensionIds`：

```
aberrantstation      [0]      aberrantstation_random   [1, -1]   → 合并 {0,-1,1}
demonictemple        [-1]     ashenmausoleum           [-1]
desertcrypts         [0]      lushtomb                 [0]
streamshrine         [0]      shadowlabyrinth          [0]
shadowlabyrinth_random [1]                              → 合并 {0,1}
```

关键设计：**表里没有的 id 返回空数组 = 未知**，不显示标签、也**永不拦截**搜索。
只有"明确列在别的维度"才会拒绝，避免因为表漏项而误挡一个本可搜到的结构。

### 18.2 主界面标注
`StructureSearchList.drawSlot` 在名称行右端画维度标签（暗色 `TEXT_INFO`），
名称可用宽度减去标签宽度 + 10px 间隔，因此再长的名字也不会与标签重叠。
变体行优先用变体自己的维度（如 `Demonic Temple` 显示"下界"），父行用父级并集。
`gui.rlcompass.dim.overworld|nether|end` 三条词条本地化。

### 18.3 新增搜索结果：wrongDimension
`PacketStructureResult` 增加 `wrongDimension` + `expectedDimensions[]`（协议同步改）。
`ItemExplorersCompass.search` 在**搜索之前**判断：

```java
if (!StructureDimensions.allows(structureId, variantId, dimension)) {
    send(PacketStructureResult.wrongDimension(id, dimension, variantId, dims));
    return null;   // 没有搜索，所以不是 "not found"
}
```

客户端收到后清空目标并提示
`rlcompass.search.wrongDimension=%s 只会在 %s 生成，你当前在 %s`
（例：「Demonic Temple 只会在 下界 生成，你当前在 主世界」）。

### 18.4 选择列表必须跨维度（否则玩家选不到）
`StructureLocatorRegistry.getSelectableIds(World)` 原先只保留 `canAnswer(world, id)` 的 id，
在别的维度打开界面时主世界结构会整体消失，玩家根本触发不到上面的提示。改为：

- 当前维度可答 → 列出；
- 否则若 `StructureDimensions` 明确该 id 属于别的维度 → **仍然列出**（带维度标签）；
- 模组没装（IAF / Lycanites 未安装，canAnswer 为 false 且不属于别的维度）→ 仍隐藏。

### 18.5 命令
- `/rlcompass list` 每个 id 后附 `overworld / nether` 等维度名。
- `/rlcompass find <id>` 与 `/rlcompass search <id>` 在维度不符时明确回复
  `X only generates in nether; you are in overworld`，不再是笼统的 "nothing within N blocks"。

### 18.6 离线验证
用编译产物直接跑断言（26 条，全通过）：父级并集、变体覆盖、未知 id 不拦截、
village 在下界/末地被拦、demonictemple 在主世界被拦但在下界放行、
aberrantstation 在末地放行、`village` / `MINECRAFT:VILLAGE` 归一化一致。

### 18.7 部署
BUILD SUCCESSFUL（11s），jar 314761 B，已确认含 `StructureDimensions` 与两份 lang 词条，
部署 `D:/MinecraftData/versions/RLCraft/mods`，备份 `build/deploy-backup/20261104-222428`。

### 18.8 边界
- 维度表是**静态**的：整合包若改了某结构的生成维度，需要同步这张表；
  表里没有的 id 只显示不出标签，不会误拦，属于安全失败。
- 部分结构的维度是"并集"粒度（父行），变体行才精确到下界/末地；未单独列出的变体继承父级。
- 冰与火龙巢/龙穴虽在主世界，但只存在于雪原/沙漠等群系，维度标注不涉及群系过滤。

## 19. 超稀有结构：Shivaxi（天空之城）定位（2026-11-04 深夜）

### 19.1 结构本体（先找到文件，不靠资料）
- 首选搜索路径错了三处（`config/recurrentcomplex`、jar 内 187 个 `.rcst`、Ruins 的 `ruins_config`），
  最后在**实例根目录**找到：`D:/MinecraftData/versions/RLCraft/structures/active/Shivaxi.rcst`。
- 同目录另有 `inactive/ShivaxiDragon.rcst`、`inactive/ShivaxiTwitchSpawn.rcst`，均未启用。
- `structure.json` 关键字段：
  ```json
  {"type":"natural","generationInfo":{
     "generationCategory":"ultrarare",
     "generationBiomes":[{"biomes":"$SNOWY"}],
     "dimensionWeightsPreset":"overworld",
     "id":"natural_3f345653",
     "placer":{"factors":[...FactorLimit/FactorMatch, requiredConformity:0.99141634...]}}}
  ```
  → 自然生成、`ultrarare` 类别、**只认 `$SNOWY` 生物群系标签**、主世界；
  放置要求 99.14% 方块一致性——这就是"失败率很高"的来源。

### 19.2 真实概率（比工具提示稀有得多）
`assets/reccomplex/structures/active/categories/ultrarare.rcnc`：
```json
{"defaultSpawnChance":1.5E-4,"spawnDistanceMultiplier":0.0,"spawnDistanceMultiplierCap":1.0}
```
`SimpleCategory.structuresInBiome` 的实际判定（反编译）：
```
nextDouble() < spawnChance * amountMultiplier(totalWeight) * distanceMultiplier * RCConfig.structureSpawnChanceModifier
amountMultiplier(w) = min(w / getActiveStructureMinCap(), 1)      // STRUCTURE_MIN_CAP_DEFAULT = 20.0
distanceMultiplier  = 1.0（spawnDistanceMultiplier=0）
structureSpawnChanceModifier = 1.0（reccomplex.cfg: structureSpawnChance=1.0）
```
`ultrarare` 只有 **3 个**结构（`Shivaxi`、`P_IceCity`、`RLCraft_2_9_Update_Build`），
每个 weight 默认 1.0 → totalWeight=3.0 → amountMultiplier= **0.15**。

所以：
- 每 chunk 触发 ultrarare：`1.5e-4 × 0.15 = 2.25e-5`
- 其中选中 Shivaxi：`÷3 = 7.5e-6`
- 再乘雪原比例（约 5%）与放置成功率（<20%）→ **约 1/1300 万 chunk**

"每 6666 区块"只是 `defaultSpawnChance` 的倒数值，没有算 `amountMultiplier` 与 1/3 的份额。

### 19.3 为什么理论上可精确重放
逐层反编译确认：
1. `ChunkGeneratorOverworld.populate`：`rand.setSeed(worldSeed)` → 取两个 `nextLong` 得 k/l →
   `rand.setSeed(x*k + z*l ^ worldSeed)` → **紧接着** `ForgeEventFactory.onChunkPopulate(true,…)`。
   即 `PopulateChunkEvent.Pre` 拿到的是**刚播种、尚未消耗**的 random。
2. RC 的 `RCForgeEventHandler.onPreChunkDecoration` 把它交给 `WorldGenStructures.decorate`。
3. `planStructuresInChunk` → `MixingStructureSelector.generatedStructures(random, biome, provider, distance)`：
   对每个类别做 `nextDouble` 判定，命中后 `WeightedSelector.select` 再消耗 1 次 `nextDouble`。
4. `planStructureInChunk` 用 `random.nextLong()` 作为结构 seed；
   位置 = `randomSurfacePos(chunkPos, seed)` = `new Random(seed ^ 1298319823120938102L)`，
   `chunk*16 + nextInt(16) + 8`（chunk 内偏移 ≤8 格）。

**决定性细节**：RLTweaker 也监听 `PopulateChunkEvent.Pre`，并在 `RecurrentHandler$RCHandler`
里完整复制了 RC 的 decorate 流程（`planStaticStructuresInChunk` + `planStructuresInChunk`），
用的是**同一个 random**；RC 自己的 handler 随后也会跑一遍（predicate 为 null 时不查
`checkedChunksFinal`），所以 random 可能被第二次消耗。

### 19.4 实现（`locate/impl/ShivaxiLocator.java`，独立文件）
两级：
1. **记录型（精确）**：`RecurrentComplexData.snapshot(world)` 里的 `Shivaxi` 记录。
   注意 RLTweaker 的 `RCCleanup` 会清空 RC 自己的 entries，所以必须依赖本项目已积累的
   `<world>/rlcompass/rc_structures_<dim>.tsv`（`RecurrentComplexData` 早已实现）。
2. **重放型（候选）**：遍历半径内 `defaultTemperature <= 0.3` 的 chunk，用**两个随机源**分别调用
   RC 自己的 `generatedStructures`（反射，不重写算法）：
   - 原版 populate random（`x*k + z*l ^ worldSeed`）
   - RC 的 `chunkSeed`（`x*341873128712 + z*132897987541 + worldSeed`）

   命中 `Shivaxi` 时用 `nextLong` 与 RC 的盐算 chunk 内偏移，得到候选坐标。
   4 秒预算、结果缓存 10 分钟、按玩家 chunk 偏移复用。
   记录型优先；16 格内的重复候选被丢弃。

### 19.5 诚实边界（重要）
用存档里两条真实记录做了端到端校验：
- `RLCraft_UndergroundRuin3` @ bbox (2007,7,5025)、seed `-1632320231900912850`
- `RLCraft_DecentWitch` @ bbox (2009,10,5028)、seed `8560641301804599608`

对 chunk (125,314) 及 12×12 邻域，枚举了「类别顺序 120 × 出现类别 32 × 命中组合 32」
共十余万种消耗序列，**没有任何一种能复现这两个 seed**。说明：
- 这两个结构很可能来自旧 session（RLTweaker 每次加载清空 entries，dat 里只剩最近一次的新增），
  或来自跨 chunk 的 `partially` 生成，其 seed 不对应"本 chunk 的一次 populate"；
- 因此**重放路径未获得真值验证**，只能作为候选。记录型路径是唯一可担保精确的结果。

若实机日志里 `[SHIVAXI]` 的候选经 tp 验证为假，优先怀疑 RLTweaker 与 RC 双份 handler 造成的
random 二次消耗；届时应改为读取 `checkedChunks` 排除已判定区块，而不是继续调阈值。

### 19.6 部署与验收
BUILD SUCCESSFUL（9s），已部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261104-224220`。

验收：
1. 选择界面出现 `Shivaxi (Sky City)`，维度标注「主世界」。
2. 搜索后日志出现 `[SHIVAXI] replay scanned N snowy chunks, predicted M candidate(s) (X ms)`。
3. 若 M=0，说明半径内没有候选（该结构期望约 1300 万 chunk 一个，属正常）。
4. 若有候选，tp 到坐标后向下/向上找天空之城；**记录型**结果 source 为
   `RecurrentComplexRecord`，**候选**为 `RecurrentComplexReplay`，两者可信度不同。

### 19.7 未生成区块可用性与自检（2026-11-04 23:00）

用户质疑："能不能在未生成区块搜索，否则就是空谈。"

**技术上可以**：重放路径不读 region 文件、不加载 chunk。`World.getBiome(pos)` 对未加载区块走
`WorldProvider.getBiomeProvider().getBiome(...)`（GenLayer 计算），不会生成地形；
`generatedStructures` 只依赖 `StructureRegistry`（启动时已加载）、biome 和 provider。
所以候选点可以在**从未生成过的区块**上算出来。

**但准确性必须证明**，否则候选没有价值。新增 `/rlcompass rccheck`：
- 遍历 `RecurrentComplexData.snapshot(world)` 里所有真实记录（跳过 vanilla 村庄部件）；
- 对每条记录重放它所在 chunk；
- 统计"重放能否再次规划出同名结构"，输出 `reproduced X/Y` 与 miss 列表。

判定标准：
- `X/Y` 接近 Y → 重放的 random 状态正确，未生成区块的候选可信；
- `X/Y` 接近 0 → random 状态假设错误，**不要相信预测**，需改为读取 `checkedChunks` 并用
  实测偏移校准。

命令实现于 `CommandRLCompass` 的 `rccheck` 分支，逻辑在 `ShivaxiLocator.diagnose(World)`。
BUILD SUCCESSFUL（11s），部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261104-225840`。

## 20. 根因：RC 的类别迭代顺序是身份哈希，每次启动都不同（2026-11-05 凌晨）

### 20.1 上一轮结果的解读
`rccheck` 扩到 3×3 邻域后是 `3/24`，且两条命中都来自相邻 chunk（offset 0）：
`P_LargeShip`、`BlazeSpawnerCrystal`。说明"规划 chunk ≠ 记录中心 chunk"确实存在，
但只能解释少数记录。

### 20.2 决定性发现：`ArrayListMultimap.create()` + 未重写 hashCode
反编译 `StructureSelector.<init>`：`weightedStructureInfos = ArrayListMultimap.create()`，
即 `HashMap<Category, ArrayList>` 支撑的 Multimap。而 `NaturalStructureSelector.Category`
是接口，`SimpleCategory` **没有重写 `hashCode`/`equals`** → 使用 `Object` 的身份哈希。

`MixingStructureSelector.generatedStructures` 的实际字节码：
```java
weightedStructureInfos.keySet().stream()
    .flatMap(cat -> IntStream.range(0, structuresInBiome(cat, provider, biome, distance, random))
                             .mapToObj(i -> selectOne(random, cat)))
    .map(...)      // 每个计划项消耗一次 nextLong 作为 seed
    .collect(toList());
```
顺序流，但 **keySet 的迭代顺序 = HashMap 桶顺序 = 身份哈希顺序**，每次 JVM 启动都不同。

`SimpleCategory.structuresInBiome` 是
`Arrays.stream(spawnChance(biome, provider)).filter(chance -> random.nextDouble() < chance * amountMultiplier * distanceMultiplier * modifier).count()`，
`spawnChance` 默认长度为 1 → **每个类别消耗 1 次 `nextDouble`**。

于是同一个 chunk 在不同会话里，第 i 次掷点会被拿去和**不同类别**的概率比较。
RC 自身在旧区块上不会重算，所以玩家察觉不到；但这意味着 **RC 的 worldgen 跨会话不可复现**，
也解释了为什么 24 条记录（跨越多个会话生成）只有 3 条能在当前会话的顺序下复现。

### 20.3 修复：把顺序钉死
`ShivaxiLocator.Replay.pinnedSelector` 在第一次拿到某个 (biome, dimension) 的 selector 后，
把 `weightedStructureInfos` 换成一个 `LinkedHashMultimap`，键按 `CATEGORY_REGISTRY.id()` 排序
（`adventure, decoration, frequent, rare, ultrarare`）。日志会打印：
```
[SHIVAXI] pinned RC category order: was [frequent decoration rare adventure ultrarare] now [...]
```
`CachedStructureSelectors.get` 是 `HashMap<Pair<dimension, biome>, selector>` 且 `isValid` 通过即复用，
所以**RC 自己生成区块时用的就是这个被钉死的实例** —— 预测与实际生成从此一致，且跨会话稳定。

顺带删除：`planChunk` 里"RC chunkSeed"那条备用随机源。`[RCPROBE]` 已用 6 个真实 chunk 证明
`x*k + z*l ^ worldSeed` 与 RC 实际拿到的 `Random` 内部状态 **6/6 完全一致**，第二条源只会产生假阳性。

### 20.4 rccheck 升级（用来证明上面的结论）
`/rlcompass rccheck [count]`（默认最近 10 条）：
1. **固定顺序**下在 ±4 chunk 窗口内找，命中则输出偏移 `(dx,dz)`；
2. 仍未命中时，**枚举该 biome 的全部类别排列**（≤5 个类别 → ≤120 种），
   逐个重放（临时替换 selector 的 map，`finally` 还原），看是否存在某种顺序能复现。

输出：
```
[SHIVAXI] rccheck: pinned order reproduced X/10, Y more under another category order ...
```
判读：
- `Y` 明显大于 0 → 旧记录来自钉死前，顺序假设成立，链路正确，预测可用；
- `X≈10` → 钉死后的记录已能完全复现，直接可信；
- `X≈0 且 Y≈0` → 链路仍有错，需要逐字节对 `selectOne` 的权重消耗。

### 20.5 部署
BUILD SUCCESSFUL（11s），jar 337918 B，已部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261105-000700` / `20261105-000900`。

### 20.6 待办
- 用 `rccheck` 拿到 X/Y，确认链路；
- 若确认，再实现"大范围深度扫描"：瓶颈是逐区块 biome 查询，需要 tick 分片 + 只对雪原 chunk
  调 selector；按 `7.5e-6/雪原 chunk` 的期望，需要数百万 chunk 才有较大概率命中。

## 21. 钉死顺序的实现 bug + 深度扫描（2026-11-05 下午）

### 21.1 上一轮 `rccheck` 的结果是无效的
```
[SHIVAXI] rccheck: pinned order reproduced 2/10, 0 more under another category order
             offsets: [(0,-1) (-2,-2)]      order indexes: []
```
`order indexes: []` 说明**排列验证一次都没跑**，而且日志里没有 `pinned RC category order`
那一行——钉死根本没执行。根因是我自己写的：
```java
Reflect.findField(selectorCache.getClass(), "weightedStructureInfos")   // 错
```
`selectorCache` 的类型是 `CachedStructureSelectors`，该字段声明在 `StructureSelector` 上，
所以 `findField` 返回 null，`pin()` 直接 return，`categoryOrders()` 返回空表。
`2/10` 只是"邻域放宽到 ±4"带来的改进，与顺序假设无关。

修复：改为对 `StructureSelector` 取字段；并在字段缺失时打一次性 WARN，避免再次静默失效。

### 21.2 其余参数已逐条核对（`WorldGenStructures.planStructuresInChunk` 字节码）
```java
distance = distance(new ChunkPos(world.getSpawnPoint()), chunkPos)   // √(dx²+dz²)，区块单位
selector.generatedStructures(random,
        world.getBiome(chunkPos.getBlockPos(0,0,0)),                 // chunk 原点，y=0
        world.provider, distance)
list.stream().filter(predicate).forEach(pair -> planStructureInChunk(...))  // 每个元素一次 nextLong
```
本项目的实现与之一致：参考点 = 世界出生点所在 chunk、距离用区块单位、biome 取 chunk 原点。

### 21.3 深度扫描 `/rlcompass shivaxi [radiusChunks|cancel]`
一般搜索只有 200 格半径，而 Shivaxi 的期望约 `7.5e-6 / 雪原chunk`，必须扫几百万 chunk。
新增 tick 分片扫描：
- 从玩家所在 chunk 起，**由近到远按环**遍历（ring 0,1,2…，先近后远）；
- 每 tick 只花约 2 ms（`TICK_BUDGET_NANOS`），游戏不卡；
- 先查 biome（`BiomeDictionary.Type.SNOWY` 或 `temperature <= 0.3`），非雪原直接跳过；
- 只对雪原 chunk 调 `planChunk`（反射 RC 自己的选择器，顺序已钉死）；
- 命中 Shivaxi 时用 `seed ^ 1298319823120938102` 算 chunk 内偏移（RC 的 `randomSurfacePos`），
  打印候选坐标并计数；
- 每 25 万 chunk 打一条进度；结束/取消时输出汇总。
`Ticker` 注册在 `MinecraftForge.EVENT_BUS`——已用字节码确认
`FMLCommonHandler.eventBus = MinecraftForge.EVENT_BUS`，`ServerTickEvent` 就发在这里。

### 21.4 必须说清的边界
- 扫描预测的是**"RC 会在这里选中 Shivaxi"**，不是"这里一定有城"。
  `Shivaxi.rcst` 的 `placer.requiredConformity = 0.9914`，实际放置还要通过方块一致性检查，
  未生成区块无法预判，因此候选点可能去了只有空地。
- 默认半径 1200 chunk（约 5.76M chunk、6 分钟量级），期望约 2 个候选；
  半径 3000 chunk 约 3700 万 chunk、半小时量级。
- 观察 `[SHIVAXI] deep scan progress` 里的吞吐量，若每 tick 过重可下调 `TICK_BUDGET_NANOS`。

### 21.5 部署
BUILD SUCCESSFUL（12–14s），已部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261105-132849` / `133038` / `133108`。

## 22. 第一次有效测量后的四项修复（2026-11-05 下午）

上一轮 `rccheck` 终于有效：`pinned order reproduced 3/10, 6 more under another category order`，
`order indexes: [2 1 1 1 2 1]`。9/10 在某种顺序下可复现，说明随机链路正确，
差别只在类别顺序——而顺序逐会话不同已由 §20 证明。用户同时指出四个问题，逐条修：

### 22.1 `CATEGORY_REGISTRY` 又取错了类（钉死顺序仍不稳定）
日志里 `was [...SimpleCategory@30c5e122 ...]` 说明 `categoryName()` 回退到了身份哈希字符串。
原因：`CATEGORY_REGISTRY` 声明在 `NaturalStructureSelector` 上，而代码从
`NaturalGeneration` 读 → 拿到 null → 名字退化为 `Object.toString()`。
排序键是身份哈希串，等于**每次启动顺序仍然不同**（同会话内一致，所以预测仍能用，
但跨会话不稳定，`was/now` 也无法阅读）。
修复：改从 `NaturalStructureSelector` 读；名字恢复为 `adventure / decoration / frequent /
rare / ultrarare`，排序真正稳定。

### 22.2 pin 日志刷屏
每个 (biome, dimension) 都有一个 selector，各自钉死一次并打一行，本次会话共 26 行。
改为按"排序后签名"去重（`LOGGED_ORDERS`），同一顺序只打一次。

### 22.3 深度扫描太慢：54 µs/chunk
实测 250154 chunk / 339 s。瓶颈是 `world.getBiome`：`BiomeCache` 只有 256 项，
环形遍历几乎每次都是 miss，每次都要重算 16×16 的整条 GenLayer 链。
改为**瓦片批量采样**：每 16×16 chunk（256×256 方块）调一次
`BiomeProvider.getBiomes(buf, x, z, 256, 256, false)`，把 GenLayer 链摊到 256 个 chunk 上；
同时按 biome 缓存 selector（`selectorByBiome`）与 snow 判定（`SNOWY_CACHE`），
把热路径上的反射调用从每 chunk 一次降到每 biome 一次。
tick 预算 2 ms → 4 ms。

### 22.4 `structuredata.dat` 的 EOFException
文件本身没坏：gzip 有效、解压 359 B、`data.entries` 是空列表（RLTweaker 清空 RC 记录）。
那次 EOF 是**上个会话崩溃留下的半写文件**，本会话保存后已恢复。
真正持久的数据是本项目的 `rlcompass/rc_structures_<dim>.tsv`（39 条都在里面）。
日志改为明确说明"从 TSV 继续、没有记录丢失"，并把 `structures known` 的措辞改成
"from the on-disk TSV cache + from RC's save file"，避免误读成"内存缓存、随时会丢"。

### 22.5 关于"多顺序尝试"和"±3 chunk 容差"
- **不需要多顺序**：钉死的是 RC 缓存的**同一个 selector 实例**，RC 生成新区块时用的就是它，
  所以钉死顺序 = 实际顺序，是构造上保证的，不是概率。
  `6 more under another order` 全部来自钉死之前生成的旧记录。
- **偏移**：记录的是结构包围盒中心，规划它的是另一个 chunk，实测最多差 3 chunk（48 格）。
  深度扫描的候选日志已注明"look within ~48 blocks"。

### 22.6 部署
BUILD SUCCESSFUL（10–13s），已部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261105-143411` / `143505`。

## 23. 深度扫描改为聊天框播报 + 有效样本口径（2026-11-05 下午）

### 23.1 实测确认（本轮日志）
- 钉死顺序已用真实类别名：`was [adventure decoration rare frequent] → now [adventure decoration frequent rare]`。
- `rccheck`：`pinned 4/10, 5 more under another order`，**9/9 自然记录全部可复现**，
  仅 `StonePlantMound3@{100,256}` 仍 miss。
- 深度扫描 `radius 400`：641601 chunk / 33 s = **19,443 chunk/s**，比修复前的 738 chunk/s 快 **26 倍**。

### 23.2 聊天框播报（用户要求，免去翻日志）
新增 `Tell` 通道，`startDeepScan` 现在接收发起命令的 `EntityPlayerMP`：
- 开始：`rlcompass.shivaxi.started`
- 进度：每 **100 万** chunk 一条（日志仍是 25 万）
- 候选：命中即播报 `区块 (x,z)，约 (X,Z)，48 格范围内找`
- 结束/取消：汇总（区块数 / 有效数 / 候选数 / 耗时）
- 启动时用 `lookupStructure(registry, "Shivaxi")` 检查注册表，缺失则直接警告
  `rlcompass.shivaxi.notRegistered`——避免白等五分钟。
发送失败（玩家已退出）静默忽略。

### 23.3 分母口径修正：从"温度雪原"改成"真的有 ultrarare 类别"
原来用 `temperature <= 0.3` 统计 snowy，把针叶林也算进去，radius 400 得到 83110/641601 = 13%，
明显偏高，会让期望值虚高。改为：
```java
if (!replay.hasUltraRare(biome)) continue;   // selector 的 keySet 里有 ultrarare 才算
```
RC 只有当某类别的至少一个结构匹配该 biome 时才会把类别放进 selector，所以
`hasUltraRare` 精确等于"这里有可能刷 Shivaxi"，这才是有效分母（按 biome 缓存，顺带省掉无谓的
`planChunk` 调用）。

### 23.4 关于"候选判定是否太严"
代码里**没有任何额外的雪层高度、最小间距、群系组合阈值**。唯一的判定就是
RC 自己的 `generatedStructures`：类别概率 + 权重选择。所以 0 候选只可能是概率，
不是我们加的条件。期望值：
```
候选数 ≈ 有效chunk × 1.5e-4 × min(totalWeight/20, 1) / 类别条目数
       = 有效chunk × 1.5e-4 × 0.15 / 3 = 有效chunk × 7.5e-6
```
radius 400 若有效 chunk 只有 4 万，期望 0.3，出现 0 的概率约 74%——完全正常。
radius 1200 约 577 万 chunk，若有效率 8% → 46 万有效 → 期望 3.4 个候选。

### 23.5 部署
BUILD SUCCESSFUL（11s），已部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261105-150523` / `150620`。

## 24. Shivaxi 实测成功：4 个候选，第 4 个找到（2026-11-05 15:20）

### 24.1 实际结果
```
[SHIVAXI] deep scan finished: 5764801 chunks, 345509 snowy, 4 candidate(s) in 294 s
   (17962,14208)  (18388,5739)  (9371,-12568)  (10737,-14264)
```
玩家逐个传送验证：**前 3 个是空地，第 4 个 `(10737,-14264)` 是真城**。
扫描半径 1200 chunk（19200 格），耗时 294 秒 ≈ 19,600 chunk/s。

### 24.2 这一步验证了什么
| 项目 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 候选数量 | 345509 × 7.5e-6 = **2.59** | **4** | Poisson `P(X≥4)=0.26`，正常 |
| 放置成功率 | 资料称"失败率约 80%" | **1/4 成功 = 25%** | 与 `requiredConformity=0.9914` 吻合 |
| 定位精度 | 预测点附近 | 第 4 个命中 | 选择层完全正确 |

**选择层（worldSeed → chunk random → 类别顺序 → 权重选择 → seed → 坐标）已经端到端验证通过**，
包括之前修的：
- `PopulateChunkEvent.Pre` 的 random 公式（探针 6/6 逐位比对）；
- 类别顺序钉死（真实类别名 + 跨会话稳定）；
- 1.12.2 的 12 位方块 ID（`Add` 数组）；
- 记录中心 vs 规划 chunk 的 ≤3 chunk 偏移（候选日志提示 ±48 格）。

剩下的失败**全部来自 RC 自己的放置检查**（`placer.requiredConformity = 0.9914` + 地形），
未生成区块无法预判，这是原理边界，不是实现缺陷。

### 24.3 候选距离（玩家当时在区块 (111,278)）
```
(18388,  5739)  16662 格
(17962, 14208)  18901 格
( 9371,-12568)  18634 格
(10737,-14264)  20747 格   ← 找到的那座
```
即：**半径 1200 chunk 的一圈大约产出 1 座真城**，最近也要飞 1.6 万格。
这与"约 1300 万 chunk 一座"的量级一致（半径 1200 的圆面积约 450 万 chunk）。

### 24.4 新增：候选自动记入缓存仓库
三个失败候选不应在下次扫描时重复推荐，所以命中的同时写进 `SearchMemory`：
```java
SearchMemory.get(deepWorld).remember(STRUCTURE_ID, candidate);
```
仓库里会以 `rlcompass:shivaxi` + 坐标出现，玩家可把失败点标成"未找到"、成功的标成"已找到"，
之后 `ItemExplorersCompass` / 再次扫描都会跳过已记录点。
聊天提示同步改为"48 格范围内找；已记入缓存仓库"。

### 24.5 部署
BUILD SUCCESSFUL（15s），已部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261105-152615`。

## 25. 提高精度：用 RC 自己的 placer 做"纯试算"（2026-11-05 下午）

### 25.1 精度还差在哪
第一次实测 4 个候选只有 1 个是真城。选择层（worldSeed → random → 类别顺序 → 权重 → seed → 坐标）
已经逐位验证，剩下的 3 个失败全部来自 RC 的放置检查：
`Shivaxi.rcst` 的 `generationInfo.placer` 里 `FactorMatch.requiredConformity = 0.9914`，
RC 会在放置前比对结构方块与目标地形的一致率，不达标就整座放弃。

**要判断这一点必须知道地形**，而未生成区块没有地形数据——所以"更精准"无法靠纯算术完成，
必须让 RC 自己去算一次。

### 25.2 找到的入口：`StructureGenerator.boundingBox()` 是纯试算
`ivorius.reccomplex.world.gen.feature.StructureGenerator`：
```java
public Optional<StructureBoundingBox> boundingBox();      // -> boundingBox(true)
protected Optional<StructureBoundingBox> boundingBox(boolean);
```
`boundingBox(boolean)` 的内部（字节码）：
```java
bb = Structures.boundingBox(surfacePos.blockPos(0), structureSize())
if (placer != null) {
    int result = placer.place(place(), structure().blockCollection());
    if (result < 0) return Optional.empty();      // DONT_GENERATE
    bb.minY += result;
}
```
- `place()` 只用 `seed() ^ 1923419028309182309L` 造 Random，再取 `environment()` /
  `transform()` / `surfaceBoundingBox()`；三者都是惰性派生（`transform()` 由
  `seed ^ 1283901823092812394L` 决定），**不需要外部状态**。
- `environment()` 惰性调 `Environment.inNature(world, surfaceBoundingBox(), generationType)`。
- placer 读方块走 `IvToolkit WorldCache.getBlockState → world.getBlockState(...)`，
  未加载的区块会被正常生成，**但不会放置任何方块**。

构造方式直接照抄 `WorldGenStructures.planStructureInChunk`：
```java
new StructureGenerator(structure).world(worldServer).generationInfo(naturalGeneration)
    .seed(seed).maturity(SUGGEST)
    .randomPosition(new BlockSurfacePos(x, z), naturalGeneration.placer)
    .fromCenter(true).partially(true, chunkPos)
```
本实现只需要 `boundingBox()`，因此省略 `maturity` / `partially`。

### 25.3 实现
`ShivaxiLocator.Replay.wouldPlace(Planned, x, z)`：
1. 从 `Planned` 拿到 `structure` 与 `naturalGeneration`（在 `planChunkWith` 里为命中项保留）；
2. 反射构造 `StructureGenerator`，依次设置 structure / world / generationInfo / seed /
   randomPosition / fromCenter；
3. 调 `boundingBox()`；返回 `Optional.empty()` 即判定"放不下"；
4. **任何异常或反射失败都返回 true**（不可验证时宁可保留候选，绝不误杀）。

扫描里命中后先试算，被拒的计入 `deepRejected`、坐标收进 `DEEP_REJECTED`，
**并在结束时把坐标一起发到聊天框**（"已排除 N 个地形不符的位置：…"），
这样即使试算本身有偏差，坐标也不会丢。

### 25.4 代价
试算会读取结构包围盒范围内的方块。Shivaxi 尺寸 68×129×85，footprint 约 5×6 个区块，
每个候选会把这些区块生成出来（约 1–2 秒）。对 4–10 个候选来说可以接受，
但它确实**会提前生成那片地形的区块**——只是不放方块、不记录结构。

### 25.5 待实测
需要一次 `/rlcompass shivaxi 1200` 验证：期望结果是
`候选 1`（真城）+ `已排除 3 个地形不符的位置`。
若出现"全部被排除"或"排除数为 0"，说明试算参数不对，需要对照
`planStructureInChunk` 补齐 `maturity`/`partially`。

### 25.6 部署
BUILD SUCCESSFUL（12–17s），已部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261105-153523` / `153628`。

## 26. 试算精度接入指南针 + GUI 搜索半径（2026-11-05 下午）

实机确认：`shivaxi 1200` 出 2 个候选，**两个都是真城**；被排除的坐标玩家逐个去看过，确实没有。
放置试算（§25）成立，于是把它接进指南针。

### 26.1 指南针搜索也做放置试算
`Replay.scanChunk` 命中后先调 `wouldPlace(planned, x, z)`，被拒的只记日志、不进结果。
指南针不会再指向 RC 拒绝生成的位置。

### 26.2 普通搜索改成"瓦片批量 + 由近到远"
原来 `scan` 是逐 chunk `world.getBiome`（54 µs/chunk），大半径下根本扫不动。
改为与深度扫描共用瓦片采样：
- 每 16×16 chunk 调一次 `BiomeProvider.getBiomes(..., 256, 256, false)`；
- 以玩家所在 chunk 为圆心**按 tile 环向外**走，预算用完时覆盖的是离玩家最近的地面，
  而不是方形区域的某个角；
- 预算 `MAX_MILLIS` 4 s → **8 s**（试算会生成结构范围内的区块，需要余量）。
实测吞吐约 245k chunk/CPU-秒 → 8 s 可覆盖约 190 万 chunk（半径约 690 chunk / 11000 格）。

### 26.3 深度扫描结果直接供指南针使用
`hits()` 在 RC 记录之后、重放之前，先把 `DEEP_HITS` 加进候选。
所以跑过一次 `/rlcompass shivaxi 1200` 之后，指南针搜 `Shivaxi` 是**瞬时**返回
已验证候选，不会把同样六百万 chunk 再扫一遍。去重逻辑（16 格内视为同一处）保证不会重复。

### 26.4 GUI：搜索半径滑条
新增 `client/gui/RadiusSlider`（自绘扁平控件，非 `GuiButton`：
拖动需要 `mouseClickMove`/`mouseReleased`，由 `GuiStructureSelect` 转发）。
- 位置：左侧动作列 y=115，宽 110、高 20，范围 **100–20000 格，步长 100**；
- 条内显示当前值（`gui.rlcompass.radiusValue`），已填充部分用半透明白表示比例；
- 松开鼠标才写配置：`CompassConfig.searchRadius = value` 然后
  `ConfigManager.sync(RLCompassMod.MODID, Config.Type.INSTANCE)`，
  即**自动改写 `config/rlcompass.cfg`**（拖动过程中不写盘，避免每像素一次 IO）。
- 单机下客户端与服务端共用同一静态字段，改完立刻对搜索生效。

### 26.5 GUI：特殊建筑提示
- 滑条下方常驻一行：`半径越大，搜索越久`；
- 当选中 `rlcompass:shivaxi`（新增 `StructureNameUtil.isUltraRare`）时追加：
  `超稀有结构：建议半径 20000，搜索可能需要数分钟`。
文案用 `listFormattedStringToWidth` 按 110 px 折行，不会溢出动作列。

### 26.6 已知边界
- 8 秒预算只能覆盖约 11000 格半径；设成 20000 时搜索是**由近到远的部分覆盖**，
  日志会写 `search stopped at the 8000 ms budget`。要完整覆盖用 `/rlcompass shivaxi`。
- 半径滑条写的是客户端配置；**专用服务器**上客户端改不到服务端的 `rlcompass.cfg`，
  需要服务端自行修改（单机不受影响）。

### 26.7 部署
BUILD SUCCESSFUL（11–13s），已部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261105-155901` / `155951`。

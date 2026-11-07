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

## 27. 超稀有引擎参数化：作者大厅 / P_IceCity（2026-11-05 晚）

### 27.1 结论先行
`structures/active` 里的 `ultrarare` 只有 3 个结构，全部接入了同一套重放引擎：

| 结构 | 指南针 id | RC 名 | 群系 | 备注 |
| --- | --- | --- | --- | --- |
| Shivaxi 天空之城 | `rlcompass:shivaxi` | `Shivaxi` | `$SNOWY` | 原有 |
| 作者大厅 | `rlcompass:authorshall` | `RLCraft_2_9_Update_Build` | `MUTATED_FOREST` | 199×62×208，`requiredConformity=0.6609` |
| P_IceCity | `rlcompass:icecity` | `P_IceCity` | `ICE_FLATS` | 同类别、同权重 |

三者每 chunk 被选中的概率都是 `1.5e-4 × amountMultiplier ÷ 匹配结构数`：
`amountMultiplier = min(匹配数/20, 1)` 与「按匹配结构数均分」相消，结果恒为
`1.5e-4 / 20 = 7.5e-6`。所以「每个目标自己的群系 chunk」的成功率都是 7.5e-6，和 Shivaxi 同源。

### 27.2 改法
- `ShivaxiLocator` → **`RcUltraRareLocator`**，新增 `Target{id, rcName, biomePath, snowy}`；
  三个常量 `SHIVAXI` / `AUTHOR_HALL` / `ICE_CITY`，`targetFor()` 同时接受完整 id、裸 id 和 RC 名。
- Replay 完全复用；`planChunkWith` 现在为**所有**计划项读取 `Pair.getRight()`，
  这样任何 target 的 `wouldPlace` 都有结构对象可用。
- `hits()` 的缓存 key 加上 target id；`DEEP_HITS` 从单个 List 改成 `Map<targetId, List<Hit>>`。
- 深扫状态加 `deepTarget`，`scanTile` 先判 `target.matchesBiome(biome)` 再判 `hasUltraRare`；
  候选写仓库时用 `target.id`。
- 命令：`/rlcompass scan <id|shivaxi|authorshall|icecity> [半径|cancel]`，
  `/rlcompass shivaxi [半径]` 保留为别名；默认半径 1200。
- GUI：`isUltraRare` 覆盖三个 id，滑条提示追加一行
  `深扫命令：/rlcompass scan <id> 1200（约 5 分钟）`。

### 27.3 验证要点
- `/rlcompass list` 应出现 `rlcompass:authorshall`（作者大厅）与 `rlcompass:icecity`（Ice City），
  维度均为主世界。
- `/rlcompass rccheck 20` 的 Shivaxi 复现率不应低于参数化之前（同一引擎，回归检查）。
- 作者大厅：`/rlcompass scan authorshall 1200`，花林占比较低，期望候选 <1；
  没有结果改 2000–3000。候选落地后用仓库「传送」到地表再核对 199×208 的大厅。
- 深扫日志前缀统一为 `[ULTRARARE]`，行内会带 RC 名。

### 27.4 实机测量（2026-11-05 22:00）
- 作者大厅 1200 区块：5,764,801 chunk，11,770 eligible，0 候选，302 s。
- 作者大厅 2400 区块：23,049,601 chunk，49,700 eligible，0 候选，1,183 s。
- P_IceCity 1200 区块：5,764,801 chunk，219,597 eligible，2 候选，310 s（两个传送后都为真）。
- `rccheck 20`：18/20 直接复现，另 1 条在别的类别顺序下复现，1 条 miss。
- 结论：花林约占 0.216%，按 7.5e-6/chunk 算，1200 期望 0.09、2400 期望 0.37，
  0 候选是正常波动，不是引擎问题；冰原 219,597 × 7.5e-6 = 1.65，实测 2，吻合。

### 27.5 部署
BUILD SUCCESSFUL（14s），已部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261105-203737`。版本号仍为 1.1.0（按用户要求攒够改动再 bump）。

## 28. 作者大厅快速扫描：RNG 预筛（2026-11-05 深夜）

### 28.1 为什么单独写
通用深扫每 16×16 chunk 先采 65,536 个群系点，再重放少数目标群系 chunk。作者大厅只在花林
（实测 0.216%），99.8% 的采样是浪费：2400 区块跑了 1,183 s。用户要求不改动 Shivaxi / P_IceCity，
所以新增 `AuthorHallFastScan.java`，只在 `/rlcompass scan authorshall` 时启用。

### 28.2 做法
`RcUltraRareLocator` 只加了一个 additive 的 `Session` 桥：
`openSession(world)`、`maySelect(x,z,draws,chance)`、`hasUltraRare`、`candidates(target,...)`。
`Session` 复用私有 `Replay`，但不碰 locator 的缓存和深扫状态。

`AuthorHallFastScan` 的循环：
1. 逐 chunk 取 `chunkSeed = x*k + z*l ^ worldSeed`（与 RC populate 完全一致）；
2. 用复用的 `Random` 读前 **64** 个 `nextDouble()`，若都 ≥ **1e-4** 则直接跳过；
   （RC 每类别消耗 1 次，5 个类别；目标概率 7.5e-6，阈值留 13 倍余量，只会多放行、不会漏）
3. 只有通过预筛的 chunk 才 `world.getBiome`，再判 `matchesBiome` + `hasUltraRare`；
4. 通过者交给 `Session.candidates` 走 RC 自己的 `planChunk` + `wouldPlace`。
预筛放行率约 0.64%，群系采样从 6,000 万级降到几十万级。

### 28.3 边界
- 预筛只保证「目标自身概率 < 阈值」时不漏；如果将来给某个目标加自定义 `spawnChances`
  或把 `structureSpawnChance` 调到 13 倍以上，需要同步调大阈值。
- 快扫与通用深扫互斥：两者同时只能跑一个，`/rlcompass scan cancel` 会停掉当前那个。
- 候选仍写入 `SearchMemory` 与 `DEEP_HITS`，指南针后续搜索可直接复用。

### 28.4 部署
BUILD SUCCESSFUL，已部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261105-230014`。版本仍 1.1.0。

## 29. 把找到的作者大厅接回指南针（2026-11-05 23:39）

### 29.1 为什么指南针搜不到
实机找到的作者大厅在 `(-25299, 70583)`，距出生点约 75k 格。指南针链路有三处会把它挡住：
1. `StructureLocatorRegistry.MAX_RADIUS = 20000`：即使配置和滑条是 100000，注册表仍裁到 20000；
2. `findNearest` 的仓库过滤：`memory.isKnown(...)` 对任何状态都跳过，快扫候选是 `NEW`，直接丢弃；
3. `RcUltraRareLocator.hits()` 只读 RC 记录、`DEEP_HITS` 和重放，不读 `SearchMemory`；
   重启后 `DEEP_HITS` 清空，只剩仓库里的 TSV 记录。

本次修复（三处都很小）：
- `MAX_RADIUS` → **100000**，与 `CompassConfig.searchRadius` / `RadiusSlider` 对齐；
- 仓库过滤对 `StructureNameUtil.isUltraRare(id)` 放行 `NEW`/`FOUND`，只有 `MISSING` 继续隐藏；
  普通结构的「删行才能再搜」行为不变；
- `hits()` 增加 `SearchMemory.entries()`（跳过 `MISSING`，`FOUND` 记为 recorded），
  这样快扫结果跨重启仍可被指南针返回。

### 29.2 使用方式
选作者大厅 → 把半径滑条拉到 100000 → 搜索。指南针会先用仓库/RC 记录直接返回 75k 外的坐标，
8 秒重放只作为补充；不再需要重新跑几十分钟的深扫。

### 29.3 部署
BUILD SUCCESSFUL，已部署 `D:/MinecraftData/versions/RLCraft/mods`，
备份 `build/deploy-backup/20261105-233911`。版本仍 1.1.0。

## 30. 热门结构快捷入口（2026-11-06 凌晨）

### 30.1 交互
主界面左栏新增「热门结构」按钮（y=65，排序/仓库/滑条依次下移 25px）。点击弹出
160px 宽的浮层，四行：

| 顺序 | id | 显示名 | 推荐半径 |
| --- | --- | --- | --- |
| 1 | `rlcompass:dragoncave` | Dragon Cave | 10000 |
| 2 | `rlcompass:shivaxi` | Shivaxi (Sky City) | 100000 |
| 3 | `rlcompass:authorshall` | 作者大厅 | 100000 |
| 4 | `rlcompass:icecity` | Ice City | 100000 |

行右侧对超稀有结构标「超稀有」。点击任意一行 = 设推荐半径（写回 cfg）+ 立即发起搜索 + 关界面。
未安装对应模组/没有数据源的 id 不会出现在浮层里（`baseIds` 过滤）。

### 30.2 实现
- `GuiStructureSelect`：`hotButton`、`hotOpen`、`hotIds`，`drawHotMenu` / `hotIndexAt` /
  `searchHot`；浮层左边界做宽度钳制，窄窗口不会溢出。
- `RadiusSlider.setValue`：编程式设置，按同一 step 取整。
- 超稀有提示多了一行深扫命令；窗口高度不足时（`height - after <= 80`）自动不画，避免压住取消按钮。
- 左栏顺序变为：搜索 / 热门结构 / 排序 / 缓存仓库 / 半径滑条 / 提示 / 取消。

### 30.3 部署
BUILD SUCCESSFUL，已部署 `D:/MinecraftData/versions/RLCraft/mods`。版本仍 1.1.0。

## 31. 稀有 / 冒险结构接入 + 深扫命中即停（2026-11-06 00:35）

### 31.1 目标表（都用 /#gen 反查到 .rcst）

| 名称 | RC 名 | id | 类别 | 群系 | 尺寸 |
| --- | --- | --- | --- | --- | --- |
| 沙漠黄金殿（艾瑞维斯） | `P_Aeriveth` | `rlcompass:aeriveth` | rare | `$HOT & $DRY & $SANDY` | 136×160×85 |
| 绿宝石穹顶 | `P_SanctuaryOfAdmys` | `rlcompass:sanctuary` | rare | `$JUNGLE` | 55×55×72 |
| 普通黄金殿 | `P_TempleOfErolith` | `rlcompass:erolith` | adventure | `$SANDY` | 83×94×47 |
| 城镇（平原） | `P_PlainsCity` | `rlcompass:plainscity` | adventure | 任意 | 207×183×50 |
| 城镇（Meridor） | `P_TownMeridor` | `rlcompass:meridor` | adventure | `$HILLS` | 172×144×51 |
| 海港城市 | `P_IslandCity` | `rlcompass:islandcity` | adventure | `$OCEAN` | 335×307×57 |
| 罪恶学院 | `RLCraft_ForestStronghold` | `rlcompass:foreststronghold` | adventure | `$FOREST` | 124×123×110 |

识别依据是方块统计（正确解码是 **MSB-first** 的 5 bit 流；LSB 解码会读出越界索引，
之前 12k 绿宝石就是解码错的产物）：`P_Aeriveth` 金 3203 / 绿宝石 122，
`P_SanctuaryOfAdmys` 绿宝石 312，`P_TempleOfErolith` 金 407 / 绿宝石 19，
`P_IslandCity` 金 4 / 铁 48 / 绿宝石 14，`P_PlainsCity` 金 21 —— 与资料逐项吻合。
因此「绿宝石穹顶」是 `P_SanctuaryOfAdmys`，`P_TempleOfErolith` 是「普通黄金殿」。

### 31.2 代码改动

- `Target`：新增 `category`、`biomeExpr`；`matchesBiome` 反射 RC 的
  `BiomeExpression.setExpression/test`，失败时退化为「只认单一路径，其余一律放行」。
- `Replay.hasUltraRare` → `hasCategory(Biome, String)`，缓存改为按类别分组。
- 深扫 / 指南针搜索的群系门都改用 `target.category`。
- `Replay.chunkSeedAt`：末地走 `x*341873128712 + z*132897987541`（`ChunkGeneratorEnd`
  在 `provideChunk` 里写死，不含世界种子），主世界仍走 `x*k + z*l ^ worldSeed`。
- 普通结构的放置试算上限 3 次（按**尝试**计数），避免 335×307 的结构把 8 秒预算吃光。
- 深扫命中即停：`deepStopAfterFirst`（默认 true）、`deepFirstHit`；`scanTile` 命中后直接返回，
  下一 tick 播报 `firstHit` 并清状态。`AuthorHallFastScan` 同样处理。
- 命令：`/rlcompass scan <id> [半径] [all]`，`all` 才扫满整个方形。
- 热门结构扩到 10 条，浮层支持滚轮滚动 + 数量提示。

### 31.3 概率修正

反汇编 `SimpleCategory.structuresInBiome` 可见：类别里**每个结构各掷一次**
`nextDouble() < 类别概率 × amountMultiplier`，`amountMultiplier = min(类别总权重/20,1)`。
所以 ultrarare 单目标 = `1.5e-4 × 0.15 = 2.25e-5`/匹配 chunk，而不是「再除以 3」。
rare = `6e-4 × 0.65 = 3.9e-4`（约 17 倍），adventure = `3e-3 × 1 = 3e-3`（约 133 倍）。

### 31.4 部署

BUILD SUCCESSFUL，已部署 `D:/MinecraftData/versions/RLCraft/mods`
（`00:35:48`，376,645 B）。版本仍 1.1.0，未提交。

## 32. 末地结构与传送落点（2026-11-06 01:20，v1.2.0）

### 32.1 候选点带真实 Y
`Replay.wouldPlace` 拆成 `placementY`：返回 `StructureBoundingBox.minY`（`null` = 放置被拒，
`Y_UNKNOWN` = 无法判定）。深扫 / 指南针搜索 / 快扫都改存真实 Y，仓库不再显示 64。
旧仓库里的 `y=64` 在末地视为「未知」，走岛面扫描。

### 32.2 末地传送
`SurfaceTeleport.findNear(world, x, y, z)`：
- 维度 1 → `findOnEndIsland`：锚点 Y 可信时在 `Y-8..Y+16` 找，否则从世界顶往下扫；
- 落点判据：脚下实心、脚和头两格空气、**3×3 至少 7 格实心**、**下方 5 格至少 4 格实心**；
- 水平最多扩 16 格；找不到返回 null，包处理里提示 `unsafe` 并拒绝传送；
- 其他维度仍走原 `find(world, x, z)`。
`PacketTeleportRequest` 新增 `y` 字段（协议同步改，双端必须同版本）。

### 32.3 末地三个目标
| 名称 | RC 名 | id | 类别 | 群系 | 尺寸 |
| --- | --- | --- | --- | --- | --- |
| 末地解密 | `RLCraft_End_Crystal_Shrine` | `rlcompass:crystalshrine` | adventure | `$end` | 159×181×86 |
| 末地污秽岛 | `RLCraft_End_Corrupted_Island` | `rlcompass:corruptedisland` | rare | `$end` | 90×82×74 |
| 末地污秽小屋 | `RLCraft_End_Defiled_Island` | `rlcompass:defiledhut` | rare | `$end` | 15×13×16 |

`$end` 若被 RC 的表达式解析器拒绝，`matchesBiome` 走宽松兜底（一律放行）；末地只有一个群系，
结果仍然正确。维度拦截由 `StructureDimensions`（END）负责。

### 32.4 版本
`mod_version` / `RLCompassMod.VERSION` → **1.2.0**；README 更新日志表、CHANGELOG 同步。
BUILD SUCCESSFUL（1m24s），部署 `01:20:00`（378,968 B）。未提交，等实机验收。

## 33. 修复：预测落在已生成区块（2026-11-06 10:50，v1.2.0）

### 33.1 现网现象
`/rlcompass scan plainscity 400`、`foreststronghold 400`、`shivaxi 1200` 都命中并播报，
但传送过去是空地；三次扫描 `0 rejected by the placer`。

### 33.2 定位
- 三个候选区块在 `saves/测试/region` 里都已有数据（`r.-49.139`、`r.-45.144`、`r.-20.173`），
  即**扫描时区块已经生成**。
- `reccomplex-structuredata.dat` 里候选 400 格内的记录只有别的结构（PeacefulCrypt、
  RLCraft_DecentZombie 等），没有目标结构 —— RC 当年就把这些点拒了。
- 根因：重放只复现「选择」，不复现「接受」。已生成区块的布局是终局，RC 不会在之后补放；
  而放置试算只跑 placer（地形/一致性），不跑 RC 的 `structureOverlap` 检查，
  所以 0 rejected。

### 33.3 修法
`WorldStructureGenerationData.get(world).checkedChunks` 是 RC 自己的「这个区块处理过了」集合。
`Replay.alreadyProcessed(x,z)` 反射读取，命中则跳过：
- 深扫 `scanTile`、
- 指南针 `scanChunk`、
- 快扫 `Session.candidates`。

跳过的候选会打日志 `skipped: RC already processed that chunk`。
未生成区块不受影响（集合里没有它），预测仍然有效。

### 33.4 部署
BUILD SUCCESSFUL，`10:49:36`（379,374 B）。版本仍 1.2.0。
注意：旧仓库里的三个假候选要手动删掉，否则指南针还会返回它们。

## 34. 候选改为「生成后核实」（2026-11-06 11:05，v1.2.0）

### 34.1 二次定位
新世界（`测试-1`）里 `plainscity 200` 命中 `(-6633,1925)`，传送后仍是空地。
- 候选区块 `(-416,119)` 传送后已进入 `checkedChunks`，但存档里**没有任何 `P_PlainsCity` 记录**。
- 候选中心 19 格处有 `DinosaurSkeleton`，完全落在 207×183 的城镇足迹内。
- `config/reccomplex.cfg` 的 `avoidOverlappingGeneration=true`；五个目标 `blocking=true`。
- 关键：`placementY` 读方块时已经触发区块生成 + `Chunk.populate`，RC 在那一刻就做了
  真正的判定 —— 包括 placer 之外的 `structureOverlap`。也就是说，我们播报的是
  「RC 刚刚拒绝过的点」。

### 34.2 修法
`Replay.hasStructureNear(target, x, z)`：反射 `WorldStructureGenerationData.getEntriesByID(rcName)`，
只有存在一个中心落在 ±96 格内的记录才算数。深扫 / 指南针 / 快扫三处都在 `placementY`
之后调用；`Y_UNKNOWN`（试算没真正跑）时跳过核实，避免误杀。
被拒候选计入 `deepRejected`，日志：
`skipped: RC generated the chunk during the dry-run and did not place it`。

### 34.3 语义变化
- 候选从「预测」变成「已核实」，传送过去必定有建筑（前提是记录匹配窗口 96 格）。
- 代价：每次候选会生成 1 个区块（原本就会，因为试算要读方块）；被拒后继续扫，
  所以一次扫描可能生成多个候选区块。
- 已生成过的区块仍走 `checkedChunks` 快速跳过，不重复生成。

### 34.4 部署
BUILD SUCCESSFUL，`build/libs` `11:05:42`（380,122 B）。部署被运行中的 Minecraft 挡住，
关闭游戏后执行 `gradlew deployToRlcraft`。

## 35. 快扫泛化、末地全高兜底与「抬头看看」（2026-11-06 12:42，v1.2.0）

### 35.1 实机反馈
- 艾瑞维斯（`aeriveth`）确实找到了，但**整座建筑生成在空中**，落地后看不见，
  容易被误判为「找到空地」——需要在文档里明确提示抬头看。
- `authorshall` / `icecity` 在给定半径内没找到：两者都是 `ultrarare`，走通用 tile 扫描时
  大半径耗时过长，实际很少有人扫完；要求把它们接入快扫通道。
- `corruptedisland` 明明有 Y≈180 的天上岛，传送却提示「没有安全落点」：
  §32.2 的锚点窗口是 `Y-8..Y+16`，而记录到的结构 Y 与真实岛面能差几十格
  （实测结构 Y 132 / 岛面约 180），窗口内全是空气，直接返回 null。
- 其余结构的传送均正常，末地 2→3→1 策略本身无需改动（不加缓降、不加返回）。

### 35.2 代码改动
1. `AuthorHallFastScan.java` → **`RcFastScan.java`**：`start(Target, ...)` 参数化目标，
   字段 `target` 由 `start` 注入；`prefilterChance()` 按类别取
   `rare = 8e-4` / 其他 `1e-4`（均高于目标自身每 chunk 概率，只多放不漏放），
   `PREFILTER_DRAWS = 128`、`TICK_BUDGET_NANOS = 8_000_000`（8 ms/tick）。
   `RLCompassMod` 注册的 Ticker 同步改名，`CommandRLCompass.stopAllScans()` 同时停快扫与通用深扫。
2. `CommandRLCompass` 的 `scan` 分支按 `target.category` 路由：`ultrarare` / `rare` → `RcFastScan`，
   `adventure` → 通用 tile 扫描（掷点率高，预筛反而更慢）。`all` 参数控制 `firstOnly`，
   默认扫到最近一个候选就停；`/rlcompass shivaxi` 旧别名也改走 `RcFastScan`。
3. `SurfaceTeleport.findOnEndIsland`：锚点窗口 `searchEndRings(y-8, y+16)` 失败后，
   **回退到全高** `searchEndRings(MIN_Y, world.getActualHeight()-1)`，
   判据不变（3×3 ≥7 实心、下方 5 格 ≥4 实心），仍找不到才拒绝，不会丢进虚空。
4. 文档：README 结果说明 / FAQ / 超稀有章节补「建筑可能生成在空中，请抬头看看」；
   CHANGELOG、README 的命令表、热门结构列表、快扫说明与下载版本说明同步到当前实现。

### 35.3 验收命令（按顺序、一条一条执行）
```mcfunction
/rlcompass scan authorshall 4000      # 花林 0.22%，60% 命中率所需半径；走快扫
/rlcompass scan icecity 1200          # 冰原 3.8%，实测应出 1~2 个候选
/rlcompass scan aeriveth 400          # 到点后抬头看，建筑可能在高空
/rlcompass scan corruptedisland 400   # 在末地执行；命中后在缓存仓库点「传送」
/rlcompass scan cancel                # 需要中途停止时
```
验收标准：聊天栏播报 `firstHit` 后扫描自动结束；传送成功且脚下是实心岛面、
头顶两格为空；`corruptedisland` 不再误报「无安全落点」；空中建筑按抬头提示可目视确认。
注意：`authorshall 4000` 是 6400 万 chunk 级别，快扫也需数分钟，中途不要重复下命令。

### 35.4 部署与状态
BUILD SUCCESSFUL，`build/libs/rlcraft-explorers-compass.jar` `12:42`（380,297 B），
SHA256 `c53dd7833d96f885dcee39256bd855b9bfa90af372e5fd268a47f90dd58c4720`；
`D:/MinecraftData/versions/RLCraft/mods/rlcraft-explorers-compass.jar` 同哈希，
jar 内含 `RcFastScan.class`（旧 `AuthorHallFastScan.class` 已不存在）。
版本仍为 **1.2.0**；按用户要求**暂不提交、不推送**，等实机验收通过后再提交。

## 36. 快扫预筛提速：内联 LCG + 收紧阈值（2026-11-06 13:03，v1.2.0）

> ⚠️ **本章的 B 结论已被实机推翻，13:46 撤销并回退**。保留原文作为「验证方法错误」的
> 反面教材；最终状态以 §37 为准。A（内联 LCG）保留，C 始终未做。

### 36.1 决策与范围
用户要求分档：**A（内联 LCG）现在做；B（收紧阈值）须用集合对比 + 抽样验证拿「入场券」；
C（提高 tick 预算）留到有明确需求再说**。本节记录当时的验证证据与落地范围：
A、B 曾落地，**C 未动**（`TICK_BUDGET_NANOS` 仍是 `8_000_000L`）。

### 36.2 基线（真实日志，非估算）
`latest.log` 12:25:25 那次 `/rlcompass scan authorshall 4000`：
```
64016001 chunks, 407824 prefiltered, 856 eligible, 0 candidate(s), 2 rejected, 1235 s
```
- 吞吐 ≈ 52,000 chunk/s；4000 半径耗时 **20.6 分钟**。
- 8 ms/tick 占 50 ms tick 的 16%，**84% 时间在等 tick**，有效 CPU ≈ 198 s。
- 其中 RNG 预筛 ≈ 64M × 128 draws × ~15 ns ≈ 122 s；40.8 万次 `getBiome()` ≈ 72 s；
  真正走到重放的只有 856 次，可忽略。
- 0 候选不是 bug：花林 0.22%，4000 半径命中率约 60%、5600 约 80%。

### 36.3 A 的等价性证据（JDK 8 微基准 + 真实种子）
内联公式（含 `setSeed` 的 `^ 0x5DEECE66D` 扰动、`next(26)` / `next(27)` 的
`>>>22` / `>>>21`、`0x1.0p-53`）：
```java
long s = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1);
s = (s * 0x5DEECE66DL + 0xBL) & MASK; long hi = s >>> 22;
s = (s * 0x5DEECE66DL + 0xBL) & MASK; long lo = s >>> 21;
double d = ((hi << 27) + lo) * 0x1.0p-53;
```
- 5 个真实世界种子（从各存档 `level.dat` 的 `RandomSeed` 读出：`4463657139655681557`、
  `-4730594225791418030`、`-722236773594328075`、`4310834843830097659`、`1392971457497389007`）
  各 2 万 chunk + 末地公式 2 万 chunk，共 **120,000 chunk × 128 = 15,360,000 个 double**，
  用 `Double.doubleToRawLongBits` 逐位比对：**0 处不符**。
- 速度：`Random.nextDouble()` 1,972 ns/chunk → 内联 230 ns/chunk，**8.5×**（3 轮预热一致）。
- 派生参数与线上一致：`k = new Random(worldSeed).nextLong()/2*2+1`，`l` 同理；
  主世界 `x*k + z*l ^ worldSeed`，末地 `x*341873128712 + z*132897987541`。

### 36.4 B 的入场券：集合对比 + 抽样验证
阈值 `ultrarare: 1e-4 → 3.5e-5`（目标自身 `2.25e-5`，1.56× 余量）；
`rare: 8e-4 → 6e-4`（目标自身 `3.9e-4`，1.54× 余量）。
样本：5 个世界种子各 40 万 chunk + 末地 40 万，共 **2,400,000 个真实 chunk 种子**。
`S_target` = 前 128 次掷点中存在 `< 目标概率` 的 chunk（比真实命中更宽松的代理集合）。

| 类别 | \|S_old\| | \|S_new\| | \|S_target\| | S_target ⊄ S_new | S_new ⊄ S_old | 最大命中 draw index |
| --- | --- | --- | --- | --- | --- | --- |
| ultrarare | 30,360 | 10,534 | 6,804 | **0** | 0 | 127 (< 128) |
| rare | 233,734 | 177,407 | 116,795 | **0** | 0 | 127 (< 128) |

差集抽样（每个类别取前 5 个 `S_old \ S_new`，并把窗口扩到 256 draws 复查）：
ultrarare 五个样本的 `minDraw` 分别 `6.94e-5 / 6.81e-5 / 8.31e-5 / 9.69e-5 / 8.68e-5`，
全部高于 `2.25e-5` 且 256 draws 内也没有更小值；rare 五个样本 `minDraw` 在
`7.0e-4 ~ 7.9e-4`，全部高于 `3.9e-4`。即被收紧掉的 chunk 确实不含目标级掷点。

**真实候选点复核**：日志 `2026-11-05-5.log` 的 `candidate #1 at chunk(-1582,4410)`，
在 5 个世界种子下逐个复算；`worldSeed=1392971457497389007` 命中
（`minDraw=2.45e-06 @ draw 4`），新旧阈值**都放行**，其余 4 个种子两阈值都不放行。

### 36.5 需要如实记录的边界
差集中有少量 chunk 在 **第 128~255 次**掷点里出现 `< 目标概率` 的值
（ultrarare 70 / 19,826，rare 2,723 / 56,327）。这说明 `PREFILTER_DRAWS = 128`
这个上界本身可能存在漏判风险，但**两个阈值都只检查前 128 次**，因此是既有边界，
不是本次收紧引入的。若日后要提高召回，可评估把 draws 提到 256（A 之后 RNG 只剩 ~15 s CPU，
翻倍仍可接受），但那属于改变搜索语义，需单独验证，本次未动。

### 36.6 落地代码
1. `RcUltraRareLocator.Session.maySelect`：内联 LCG，删掉共享 `prefilterRandom` 字段
   （Java 8 `Random` 的 `AtomicLong` CAS 是主要开销），Javadoc 写明不得简化移位。
2. `RcFastScan.prefilterChance()`：改为 `6.0E-4` / `3.5E-5`，注释记录验证依据。
3. `RcFastScan.TICK_BUDGET_NANOS` **保持 `8_000_000L`**（C 未做）。
4. README 超稀有段、CHANGELOG 1.2.0 `### 改进` 同步数值与实测基线。

### 36.7 预期与部署
估算：RNG CPU 122 s → 14 s，`getBiome` 40.8 万 → 约 14 万次（72 s → 25 s），
有效 CPU 198 s → 约 45 s；占空比不变（16%）→ 真实耗时约 **5 分钟**（待实机复测）。
BUILD SUCCESSFUL，`build/libs/rlcraft-explorers-compass.jar` `13:03`（380,447 B），
SHA256 `f1161f07167c4ef8c823816125746ece5b53ba247c4cf1ddc9254a25be4821be`；
`mods/` 下 jar 同哈希，旧版备份在 `build/deploy-backup/20261106-130313`。
版本仍 **1.2.0**，**未提交、未推送**。

## 37. 事故：收紧预筛阈值导致超稀有结构零命中（2026-11-06 13:46，v1.2.0）

### 37.1 现象
用户重启游戏（13:14 载入 13:03 的提速 jar，世界 `测试-3`）后反馈「超稀有结构一个都找不到」。
`latest.log` + `2026-11-06-3.log.gz` 两个日志里的全部扫描：

| 时间 | 命令 | prefilter | 结果 |
| --- | --- | --- | --- |
| 13:17 | `authorshall 5600` | 128 draws < `3.5E-5` | 125,462,401 chunks / **512 s**，560,609 prefiltered，1172 eligible，**0 candidate**，3 rejected |
| 13:27 | `authorshall 5600`（换中心） | 同上 | 125,462,401 / **518 s**，559,991 prefiltered，1192 eligible，**0 candidate**，2 rejected |
| 13:37 | `icecity 1200` | 同上 | 5,764,801 / 23 s，541 eligible，**0 candidate**，2 rejected |
| 13:38 | `icecity 2400` | 同上 | 23,049,601 / 94 s，2874 eligible，**0 candidate**，3 rejected |
| 13:42 | `icecity 1200` | 同上 | 996 eligible，**0 candidate**，0 rejected |
| 13:43 | `icecity 2400` | 同上 | 2728 eligible，**0 candidate**，5 rejected |
| 13:39 | `aeriveth 400` | 128 draws < `6.0E-4` | 518,701 / **18 s**，`candidate #1 at (101003,~,105677)` **verified** |

对照历史（11-05 23:18，旧 jar）：`authorshall 5600` 用 `64 draws < 1.0E-4`，
在约 4900 万 chunk 处 `candidate #1 at (-25299,~,70583)`；11-05 22:32 的
`icecity 1200` 用通用深扫找到 2 个真城。

### 37.2 排查
- **不是「生成后核实」吞掉候选**：`latest.log` 里 `skipped` 计数为 **0**。
  §34 的 `hasStructureNear` 失败会打 `skipped` 日志，一条都没有，说明完整重放
  `planChunk()` 在这些 eligible chunk 里几乎没 plan 出目标（`rejected` 也只有 2~5）。
  即**预筛放行的集合与真实命中集合几乎不重合**。
- **不是 A 的 bug**：内联 LCG 在 1536 万个 double 上与 `Random` 位级一致；
  且同期 `aeriveth`（rare）能命中，说明扫描链路本身正常。
- **不是概率运气**：A+B 确实提速了（1.25 亿 chunk / 512 s ≈ 245,000 chunk/s，
  比基线 52,000 快 4.7×），两次 5600 + 四次 icecity 全部零命中，
  而唯一走 `6.0E-4` 阈值的 rare 目标 18 秒命中 —— 与阈值强相关。

### 37.3 根因：入场券验证是同义反复
§36.4 的「集合对比」在**同一组前 128 次掷点**上比较三个阈值。
因为 `2.25e-5 < 3.5e-5 < 1e-4`，`S_target ⊆ S_new ⊆ S_old` 由阈值单调性**必然成立**，
零漏判是算术恒等式，不是证据。它完全没有验证两件真正要紧的事：
1. RC 每结构的真实掷点阈值是否等于推导出的 `2.25e-5`（`categoryChance × amountMultiplier`）。
   实机表现说明真实阈值接近 `1e-4` 量级，于是 `draw ∈ [3.5e-5, 1e-4)` 的大量真实命中
   被新阈值挡在门外；旧的 `1e-4` 恰好能兜住它们（以误报形式放行，再由真实重放确认）。
2. 目标那次掷点是否落在 `PREFILTER_DRAWS = 128` 之内。类别顺序被钉死成
   `[adventure decoration frequent rare ultrarare]`（13:17 日志），`ultrarare` 排在最后，
   前置类别的匹配结构数会把它推到很靠后。§36.5 已经观察到 128~255 窗口有目标级掷点
   （ultrarare 70、rare 2723），却误判为「既有风险、非 B 引入」——
   事实是旧阈值靠宽松误报掩盖了它，收紧后风险被放大成系统性漏判。

**结论：预筛本质是靠「宽松阈值产生的误报」来兜住 RC 真实掷点的位置不确定性，
不是靠精确覆盖。收紧阈值等于撤掉这层兜底。**

### 37.4 处置
- `RcFastScan.prefilterChance()` 回退为 `8.0E-4`（rare）/ `1.0E-4`（其余），
  并在 Javadoc 明确「不要在没有实机 A/B 的情况下再次优化」。
- **A（内联 LCG）保留**：等价性证据独立且充分，实测吞吐 4.7× 也来自它。
- **C（tick 预算）仍未做**，`TICK_BUDGET_NANOS = 8_000_000L`。
- README 超稀有一段、CHANGELOG 1.2.0 的 `### 改进` / `### 修复`、§36 顶部警示同步。

### 37.5 教训（写给下一次优化）
1. 任何「保守预筛」的收紧，必须在**真实命中过的坐标**上做 A/B：
   固定一批日志里已 verified 的 chunk，验证新旧阈值都放行，而不是在随机种子上比集合大小。
2. 只验证「阈值单调」等于什么都没验证；要验证的是**模型与真实调用序列的对齐**。
3. `PREFILTER_DRAWS` 与阈值是一对：一旦要收紧阈值，先证明目标掷点的 index 上界。

### 37.6 复测与部署
用户需**重启游戏**加载新 jar。建议先跑 `icecity 1200`（历史 22:32 通用深扫在此半径找到过 2 个），
再跑 `authorshall 5600`。
BUILD SUCCESSFUL，`build/libs/rlcraft-explorers-compass.jar` `13:51`（380,442 B），
SHA256 `3d91ae20827bbc0ee51356c4a63a31746e02740de25bbb6fd4d796404cefa5ec`；
`mods/` 下 jar 同哈希，收紧版备份在 `build/deploy-backup/20261106-135159`。
版本仍 **1.2.0**，**未提交、未推送**。

## 38. 阈值回退仍零命中：超稀有停用快扫（2026-11-06 14:04，v1.2.0）

### 38.1 现象：回退生效了，但没救回召回
用户重启加载 13:51 的回退 jar 后执行 `/rlcompass scan icecity 1200`：
```
[13:59:01] fast scan for P_IceCity started ... prefilter 128 draws < 1.0E-4
[13:59:42] fast scan finished (P_IceCity): 5764801 chunks, 73222 prefiltered,
           1458 eligible, 0 candidate(s), 1 rejected by the placer in 41 s
```
- `1.0E-4` 证明**阈值确实回退了**；41 s 比收紧版的 23 s 慢，正是 `prefiltered`
  从 2.6 万涨回 7.3 万、`getBiome` 变多的预期结果。
- 但**依旧 0 候选**，`skipped` 仍为 0，`rejected` 只有 1。
  73,222 个放行 chunk / 1458 个 biome 匹配 chunk 里，完整重放只 plan 出 1 个 P_IceCity。
- 结论：**§37 把根因归给阈值是错的（或不完整的）**。阈值只是放大器，
  快扫的预筛模型本身对 `ultrarare` 就不成立。

### 38.2 根因
13:17 日志里钉死的类别顺序：
```
pinned RC category order: was [decoration rare ultrarare adventure frequent]
                          now [adventure decoration frequent rare ultrarare]
```
`ultrarare` 排在**最后**。前置的 `adventure` / `decoration` / `frequent` / `rare`
里匹配该群系的结构数很可能超过 `PREFILTER_DRAWS = 128`，于是目标那次掷点根本不在
预筛检查的 128 次里。此外，预筛假设 RC 会「连续消耗 128 次 `nextDouble()`」，
而 `planChunkWith` 里 `generatedStructures` 内部是否还消耗别的随机数从未验证过。

无论哪一个成立，效果都一样：**预筛放行集合与真实命中集合近似独立**，
等价于随机抽 1.27% 的 chunk。对命中数只有个位数的超稀有结构，成功率极低；
`rare` 目标命中数多得多，所以 `aeriveth 400` 能在 18 秒内撞上一个。
历史 11-05 23:18 那次 `authorshall 5600` 快扫命中属于运气，不能当作正确性证据。

对照：**通用 tile 深扫（无预筛）在实机里反复找到过超稀有结构**：
| 时间 | 目标 | 结果 |
| --- | --- | --- |
| 11-05 22:32 / 22:34 | `icecity 1200` | 2 个真城（约 100 万 / 289 万 chunk 处） |
| 11-06 10:30 | `shivaxi 1200` | 1 个（3,686,239 chunks / 187 s） |
| 11-05 23:34 | `authorshall 5600` | 1 个（快扫，运气） |

### 38.3 处置
`CommandRLCompass` 的路由改为：
```java
boolean fastCategory = "rare".equalsIgnoreCase(target.category);
```
- **`ultrarare`（shivaxi / authorshall / icecity）与 `adventure` → 通用 tile 深扫**；
  `/rlcompass shivaxi` 旧别名同步改走 `RcUltraRareLocator.startDeepScan`。
- **仅 `rare`（aeriveth / sanctuary / corruptedisland / defiledhut）保留 `RcFastScan`**，
  阈值 `8e-4`，内联 LCG 提速保留。
- `prefilterChance()` 的 `1e-4` 分支保留但不再被超稀有调用，作为历史兼容。

代价要说清楚：超稀有恢复可靠但变慢，1200 半径约 5 分钟（tile 深扫实测 296 s），
5600 半径可能超过一小时；默认「命中最近一个即停」，通常远早于扫满。

### 38.4 验收
仍需**重启游戏**加载 14:04 的 jar。第 1 条：
```mcfunction
/rlcompass scan icecity 1200
```
日志应以 `[ULTRARARE] deep scan for P_IceCity started` 开头（**不再是 `FASTHALL`**），
命中时打印 `deep scan candidate #1 ... verified by RC's placer` 并 `first hit` 自动结束。
若仍 0 候选，问题就与扫描器无关，需转向 RC 记录 / 群系表达式。

### 38.5 部署与状态
BUILD SUCCESSFUL，`build/libs/rlcraft-explorers-compass.jar` `14:04`（380,441 B），
SHA256 `a00d39e0d85dbed8a1e3bf134aad09fb03036c01450ea6605dd66152a2743bd3`；
`mods/` 下 jar 同哈希，上一版备份 `build/deploy-backup/20261106-140435`。
README 命令表 / 超稀有段 / 多人游戏表、CHANGELOG 1.2.0 新增·改进·修复三处已同步。
版本仍 **1.2.0**，**未提交、未推送**。

## 39. 恢复作者大厅独立快扫 `AuthorHallFastScan`（2026-11-06 14:31，v1.2.0）

### 39.1 用户诉求
用户指出 11-05 深夜有一版「作者大厅专门的 Java，优化过，5600 区块约 20 分钟能找到」，
要求回退到那个版本。核对日志属实：
```
[23:18:30] [FASTHALL] fast scan for RLCraft_2_9_Update_Build started
           at chunk(-1857,931) radius 5600 chunks (125462401 chunks to walk);
           prefilter 64 draws < 1.0E-4
[23:34:09] [FASTHALL] candidate #1 at (-25299, ~, 70583) chunk(-1582,4410) verified
```
即 **15 分 39 秒**命中。该版本见 §28：独立文件 `AuthorHallFastScan.java`，
「用户要求不改动 Shivaxi / P_IceCity」，那两个仍走通用 tile 深扫。

### 39.2 版本考据与恢复依据
- `AuthorHallFastScan.java` **从未提交**（`git log --all -- "*AuthorHallFastScan*"` 为空），
  git 里没有可 checkout 的快照；`build/deploy-backup/` 最早只到 2026-11-06 01:20。
- 但完整源码保存在会话记录 jsonl 的 line 4276（Write，11,670 字符）及后续
  4279 / 4287 / 4772（Edit 与 Python 补丁），足以复原行为。
- 原始参数：`PREFILTER_DRAWS = 64`、`PREFILTER_CHANCE = 1.0E-4`、
  `TICK_BUDGET_NANOS = 4_000_000L`、固定 `TARGET = AUTHOR_HALL`、
  原注释假设「RC 有 5 个类别、每类别一次 `nextDouble()`，64 远超足够」。
- 后来（§31 / §35）被泛化成 `RcFastScan`（128 draws、按类别阈值、8 ms/tick、参数化 Target），
  并在 §36 / §37 / §38 反复调整阈值与路由。**泛化后的版本在 11-06 的多次超稀有扫描中
  一次都没命中**，这是用户要求回退的直接原因。

### 39.3 必须修正 §38 的过度结论
§38 把「预筛模型对 ultrarare 失效」写成了定论，证据其实不充分：
- 11-05 的 icecity 命中在 `chunk(-1471,425)` / `(-1866,927)`，属于**另一个世界**；
- 11-06 13:59 的 `icecity 1200` 中心是 `chunk(591,192)`，覆盖范围
  `x∈[-609,1791]`，**根本不含那两个坐标**；13:38 的 2400 中心 `chunk(6247,6252)` 同样不含；
  13:43 的 2400 中心 `chunk(363,-20)` 虽覆盖 `(-1471,425)`，但世界种子不同，
  同一坐标的生成结果本来就不一样。
- 因此「找不到」至少部分是**世界 / 区域差异**，不能全归给扫描器。
  用户凭实机记忆要求回退是合理的；本轮按其要求执行，由下一次实机来判定。

### 39.4 恢复范围（重要）
**不是整体回退到 11-05 深夜的代码状态**。那样会丢掉用户已经验收过的 1.2.0 功能：
rare / adventure 结构、末地三结构与 2→3→1 传送、缓存仓库传送、热门结构 14 条、
§33 / §34 的「生成后核实」修复、1.2.0 版本号等。
本次只恢复**作者大厅那条扫描路径**，其余全部保留。若用户要的是整体回退，需另行确认。

### 39.5 实现
1. 新建 `src/main/java/com/tom500101/rlcompass/locate/impl/AuthorHallFastScan.java`：
   独立文件、`64 draws` / `1e-4` / `4 ms`/tick、固定 `AUTHOR_HALL`、
   5 参 `start(world, player, around, radius, firstOnly)`、`[FASTHALL]` 日志、独立 `Ticker`。
   复用当前 `Session` API（`openSession` / `maySelect` / `hasCategory` / `candidates`），
   因此保留 A 的内联 LCG 与 §33 / §34 的核实逻辑，但不改预筛参数。
2. `CommandRLCompass` 三路路由：
   `authorshall` → `AuthorHallFastScan`；`rare` → `RcFastScan`；
   其余（`shivaxi` / `icecity` / `adventure`，含 `/rlcompass shivaxi` 别名）→ 通用 tile 深扫。
   三条路径互相检查互斥；`stopAllScans()` 改为同时停三个。
3. `RLCompassMod` 注册 `AuthorHallFastScan.Ticker`（与 `RcFastScan.Ticker` 并存，空闲时零开销）。
4. README 超稀有段 / 命令表 / 多人游戏表、CHANGELOG 1.2.0 `### 修复` 同步。

### 39.6 验收
重启游戏加载 14:31 的 jar，第一条：
```mcfunction
/rlcompass scan authorshall 5600
```
日志开头应为 `[FASTHALL] fast scan for RLCraft_2_9_Update_Build started ... prefilter 64 draws < 1.0E-4`，
命中时 `candidate #1 ... verified` + `first hit`。若在 20 分钟内命中，说明该版本行为已恢复；
若仍 0 候选，需连同世界种子一起比对 11-05 那次的世界，而不是继续改扫描器。

### 39.7 部署与状态
BUILD SUCCESSFUL，`build/libs/rlcraft-explorers-compass.jar` `14:31`（386,439 B），
SHA256 `d1e4f1f8f52f85c216afbf71071fc8c1ff03b30c8f7edcd1f41c65e558c4c346`；
`mods/` 下 jar 同哈希，上一版备份 `build/deploy-backup/20261106-143114`。
jar 内同时含 `AuthorHallFastScan.class` 与 `RcFastScan.class`。
版本仍 **1.2.0**，**未提交、未推送**。

## 40. 指南针直接驱动异步深扫（2026-11-06 16:08，v1.2.0）

### 40.1 问题：100000 格却几秒返回「没找到」
用户在指南针里搜「天空之城」（`rlcompass:shivaxi`），半径 100000 格，几秒后聊天栏报
「搜索范围内没有找到」。日志实证（`2026-11-06-4.log.gz`，13:45:09→13:45:17）：

```text
[ULTRARARE] search stopped at the 8000 ms budget after 122589 chunks
[ULTRARARE] replay scanned 122589 eligible chunks, predicted 0 candidate(s) (8011 ms)
[ULTRARARE] 0 hit(s) for Shivaxi (0 recorded, 8017 ms)
[REGISTRY] findNearest(rlcompass:shivaxi) from (5812, -307) -> null (8018 ms)
Can't keep up! Running 8089ms behind, skipping 161 tick(s)
```

根因不是半径或单位：`config/rlcompass.cfg` 里就是 `searchRadius=100000`，
`StructureLocatorRegistry` 的上限也是 100000。真正的问题在同步路径——
`ItemExplorersCompass.search()` 调 `RcUltraRareLocator.findNearest()` → `Replay.scan()`，
后者有硬编码 `MAX_MILLIS = 8000L`，超时直接 `return found`，0 命中被当成「半径内没有」
发给客户端。100000 格 = 6250 区块半径，tile 半径 391，需要走完约 61 万个 tile
（1.56 亿 chunk），8 秒连零头都不到。空结果还会被 `CACHES` 缓存 10 分钟，
所以同位置再搜会「瞬间」返回没找到。

命令 `/rlcompass scan` 才是 tick 分片版本，但 `getRequiredPermissionLevel() = 2`，
单机不开作弊 / 生存服非 OP 玩家根本用不了——这正是用户指出的核心矛盾。

### 40.2 决策
按用户要求「以功能正确为准，直接调用深扫版本」：

- 指南针界面上的 RC 稀有 / 超稀有结构不再走同步查询，改发新包启动现有三个 tick 分片扫描器；
- 不经过 `CommandBase`，右键指南针即可用，**不要求作弊或 OP**；
- 半径随请求发送，顺带修掉专用服务器上客户端 cfg 与服务端不一致的问题；
- 异步执行、可取消、命中/扫完都回传客户端。

### 40.3 实现
- `PacketDeepScanRequest`（C→S，ID 4）：`cancel`、`structureId`、`radiusBlocks`。
- `PacketDeepScanResult`（S→C，ID 5）：`STARTED / HIT / MISS / CANCELLED / BUSY / UNAVAILABLE`，
  带坐标与维度。客户端 HIT 时 `ClientCompassState.setTarget`，MISS / CANCELLED 时 `clear`。
- `DeepScanService`：`targetFor(id)` → 维度校验（不符时复用 `PacketStructureResult.wrongDimension`）
  → 半径 `blocks/16` clamp 到 `8..6250` → 三分支路由（`authorshall` 独立快扫 / `rare` 参数化快扫 /
  其余通用 tile 深扫）；已有扫描时回 `BUSY` 并附带当前目标名。
- 三个扫描器在命中、扫满、停止时发结果包。`RcUltraRareLocator` 取距玩家最近的 `Hit`；
  两个快扫新增 `lastHit`，在 `clear()` 前发送。
- `ClientSearchStatus` 增加 `beginDeepScan()`（4 小时上限）与 `isDeepScan()`；
  `GuiStructureSelect` 对深扫目标发新包，`updateButtonState()` 在扫描期间把「搜索」改为
  「取消深扫」，点击发 `cancel`（命令取消仍保留，给管理员用）。
- `StructureNameUtil.isDeepScanTarget` 用纯字符串集合判断，避免客户端加载
  `RcUltraRareLocator`（引用 `EntityPlayerMP` 等服务端类）；必须与 `TARGETS` 同步维护。
- GUI 文案 `gui.rlcompass.radiusScanHint` 不再引导玩家用命令，改为「点搜索即启动后台深扫，不需要作弊」。

### 40.4 边界
- 深扫仍是全局单例：同一时间只允许一个，第二个请求收到 `BUSY`。
- 玩家中途退出/重连会丢失客户端指示器；服务端扫描继续，结果发不出去（已 try/catch）。
- tick 预算未改（作者大厅 4 ms、`rare` 8 ms、通用 4 ms）；大半径下仍可能有轻微 TPS 抖动，
  这是「异步但不影响正常游玩」的取舍，README 已写明。
- 同步路径仍保留给非 RC 结构；`/rlcompass find` 对 RC 目标仍是 8 秒预算，属于管理员诊断入口。

### 40.5 部署与验收
BUILD SUCCESSFUL `16:08`，jar 399,514 B，
SHA256 `48c6d7160681353f91185308ca2704440a2127ac2dfe6a0871a094001ef8d5de`，
`mods/` 下同哈希，上一版备份 `build/deploy-backup/20261106-160845`。
版本仍 **1.2.0**，**未提交、未推送**。

验收（**必须重启游戏**）：
1. 用**不开作弊**的存档，右键指南针 → 热门结构 → Shivaxi → 搜索；
2. 聊天栏应出现 `rlcompass.scan.started`，HUD 显示搜索中，界面立即关闭且可继续游玩；
3. 再次右键指南针，「搜索」按钮应显示「取消深扫」；点击后聊天栏出现取消提示；
4. 重新搜索并等待命中：HIT 后 HUD 指向坐标，仓库新增一条；本次用时记入日志
   `[FASTHALL] first hit` 或 `[ULTRARARE] deep scan finished`。

## 41. 半径回退、通用深扫提速与龙穴限制（2026-11-06 17:21，v1.2.0）

### 41.1 半径为什么「变回 10000」
日志实证：

```text
[16:18:00] deep scan for P_PlainsCity started ... radius 625 chunks (10000 blocks)
[16:22:31] deep scan for Shivaxi      started ... radius 6250 chunks (100000 blocks)
```

用户把滑条拖到 100000 后立刻点搜索，第一次仍按 10000 格（625 区块）跑，过一会儿再搜才是
6250 区块。根因有两条：

1. `GuiStructureSelect.searchFor()` 发的是 `CompassConfig.searchRadius`，而不是滑条当前值；
2. `persistRadius()` 调 `ConfigManager.sync` 后，Forge 会把配置文件回读进静态字段，玩家刚选的
   值会短暂被磁盘旧值覆盖，等下一次 reload 才恢复。

修复：`searchFor()` 直接读 `radiusSlider.getValue()`（搜索请求本来就带 `radiusBlocks`，服务端
用它算半径）；`persistRadius()` 写完盘后把选定值再写回字段一次，并在聊天栏播报
`rlcompass.radius.saved`「半径已更新成功：N 格（M 区块）」。这样即使 config 回读延迟，
点击搜索的那一刻也一定用玩家看到的半径。

### 41.2 作者故乡为什么慢
日志（16:27:49 起，17:14:25 时）：

```text
P_IceCity  radius 6250 chunks (100000 blocks, 156275001 chunks)
54,500,001 chunks scanned, 1,430,723 eligible, 0 candidate(s), tile ring 231/391, 2795 s
```

已扫 46.6 分钟、231/391 环，只有 3 个候选被「生成后核实」拒绝。冰原占 2.6%，所以
`planChunk` 被调用了 143 万次——这是全部成本所在，反射 RC 的 `generatedStructures` 每次约 2 ms。

改动（不改变搜索语义）：

- **保守 RNG 预筛**：`ultrarare` 目标在群系与类别通过后，再用 128 draws / `1e-3` 预筛；
  `1e-3` 是目标概率 `2.25e-5` 的 44 倍，真实命中必然低于它，唯一假设是目标掷点在前 128 次
  `nextDouble` 内（与快扫同一模型，作者大厅 64 draws 已实机命中过）。放行率约 12.8%，
  `planChunk` 降到约 18 万次。
- **`alreadyProcessed` 提前**：已由 RC 定稿的 chunk 不再先跑一遍选择器再丢弃。
- **tick 预算 4 ms → 8 ms**：与 `RcFastScan` 一致；`AuthorHallFastScan` 仍 4 ms。

预计 46 分钟 → 3~4 分钟（约 14 倍，待实机复测）。放行 chunk 仍走完整选择器、放置试算和
生成后核实，`rare` / `adventure` 目标不受影响。

### 41.3 龙穴为什么不能深扫
`DragonRoostLocator` 用 `ModdedStructureRegionScanner` 读 `region/*.mca` 里的特征方块，再按
「逐层直方图是否有空腔」区分地面巢与地下穴。Ice and Fire 1.7.1 没有位置记录、
没有 `MapGenStructure`，生成随机流还与同 chunk 的其他特征共用，无法重放未生成区块。
日志 `findNearest(rlcompass:dragoncave) -> null (396 ms)` 说明当前存档 region 里确实没有。
因此它保持 region 扫描；搜索无结果时改发 `rlcompass.search.notFoundGenerated`，明确说
「只能从已生成的区块里找」。热门列表其余 13 条都是 RC 目标，全部走异步深扫，不需要命令。

### 41.4 部署
BUILD SUCCESSFUL `17:21`，jar 400,324 B，
SHA256 `e0921f533882edb936c405b2a0267b6ad1fb7abfe5f67287108f66de019a8c32`，
`mods/` 同哈希，上一版备份 `build/deploy-backup/20261106-172111`。
版本仍 **1.2.0**，**未提交、未推送**。

验收（**重启游戏**，并先 `/rlcompass scan cancel` 停掉旧的 46 分钟扫描）：
1. 拖半径到 100000，松手，聊天栏应出现「半径已更新成功：100000 格（6250 区块）」；
2. 立刻点搜索，日志第一行必须是 `radius 6250 chunks (100000 blocks)`，不能再出现 625；
3. `icecity` 深扫的总 chunk 吞吐应明显提升；`eligible` 仍是群系匹配数、不会因预筛变小
   （预筛在 eligible 计数之后），要看 `finished` 里的 `target plans / planned`；
4. 龙穴搜索无结果时，聊天栏应显示「只能从已生成的区块里找」而不是普通「没找到」。

## 42. 龙穴半径、最大半径提示与 P_IceCity 零候选诊断（2026-11-06 18:52，v1.2.0）

### 42.1 龙穴仍按 10000 格搜索
日志 `[18:05:49] findNearest(rlcompass:dragoncave) ... (538 ms)` 没有半径字段，但龙穴走的是
同步 `PacketSearchRequest`，服务端读 `CompassConfig.searchRadius`；17:21 的修复只覆盖了
`PacketDeepScanRequest`，所以普通结构仍会被配置回读打回旧值。

修复：`PacketSearchRequest` 增加 `radiusBlocks`，客户端传滑条值，`ItemExplorersCompass.search`
优先用它（0 才回落到 `CompassConfig.searchRadius`）。命令 `search` 不受影响。

### 42.2 最大半径的返回文案
`PacketDeepScanResult.miss` 现在带 `radiusChunks`；客户端在 `>= 6250` 时改用
`rlcompass.scan.missMaxRadius`：「半径内没有找到 %s。已经扫到最大半径（100000 格），
请换一个远的地方再定位吧」。

### 42.3 作者大厅 1.5 亿 chunk 为 0：正常
`18:08:57-18:30:30` 的作者大厅快扫：

```text
156,275,001 chunks, 995,767 prefiltered, 2,140 eligible, 0 candidate, 2 rejected, 1293 s
```

花林只占 `2,140 / 1.56 亿 = 0.0014%`，不是 README 里的 0.22%（那是另一个世界的实测）。
`2,140 × 7.5e-6 ≈ 0.016` 个期望，0 个完全正常。问题不在扫描器，而在这个世界花林极少；
继续加半径不会改变「本地没有花林」这一事实。

### 42.4 P_IceCity 0 候选：强异常，但预筛不是根因
日志两段：

```text
16:27-17:14  54,500,001 chunks, 1,430,723 eligible, 0 candidate, 231/391, 2795 s  （无预筛）
18:33-18:46  27,750,000 chunks,   735,997 eligible, 0 candidate, 165/391,  783 s  （有预筛）
```

按 `ultrarare` 类别 `1.5e-4 × min(3/20,1) = 2.25e-5`，再除以类别内 3 个结构，P_IceCity
每匹配 chunk 为 **`7.5e-6`**：

- 73.5 万 eligible → 期望 5.5，`P(0)=0.4%`；
- 143 万 eligible → 期望 10.7，`P(0)=2e-5`；
- 两段合计期望 16.2，`P(0)≈1e-7`。

**预筛可以排除**：16:27 那段根本没有预筛，也是 0；`1e-3` 是目标概率的 133 倍，目标掷点必然
低于阈值，预筛只跳过不可能命中的 chunk，不降低召回。**placer 也可以排除**：日志里 `rejected`
只有个位数，绝大多数 chunk 是 `planChunk` 根本没产出 `P_IceCity`，不是产出后被拒绝。

最可疑的是 `generationBiomes: ICE_FLATS` 在 RC 内部的实际匹配率低于 `Target.matchesBiome`
（两者都用 `BiomeExpression`，但 `NaturalGeneration` 可能走另一条 biome 解析路径），
或者 `P_IceCity` 在当前世界的 `ultrarare` 权重/维度条件下被跳过。

### 42.5 新增诊断
`deep scan finished/stopped` 现在输出 `target plans / planned`：

- `0/N`：选择器从未选中目标 → 排查 `generationBiomes` 与 `Target.biomeExpr` 的一致性；
- `>0/N` 但 `candidate=0`：选中后被 placer 或生成后核实拒绝 → 看 `rejected` 与
  `skipped: RC did not place it`。

下次 `icecity 1200` 对照（同一世界、同一位置）即可把范围缩到「选择」还是「放置」。

### 42.6 部署
BUILD SUCCESSFUL `18:52`，jar 400,984 B，
SHA256 `afc59cb62f9a842552fba2c7fe7554a5c2d9ccdb285ef329bcbd369a5a0305c7`，
`mods/` 同哈希，上一版备份 `build/deploy-backup/20261106-185221`。
版本仍 **1.2.0**，**未提交、未推送**。

## 43. P_IceCity / 作者大厅零候选的根因：`.rcst` 缺 `spawnChance`（2026-11-06 19:25）

### 43.1 探针数据
1200 半径对照（19:17-19:20）：

```text
selector probe at chunk(-960,704): biome=minecraft:ice_flats distance=1191.4
  weightsSize=52
  totals={...=7.12, ...=2.02, ...=17.94, ...=2.0, ...=13.93}
deep scan finished (P_IceCity): 5,764,801 chunks, 205,299 eligible,
  0 candidate(s), 0 rejected, 0/0 target plans in 153 s
```

selector 有 52 个条目、5 个类别权重都 >0；`ultrarare.rcnc` 的
`spawnDistanceMultiplier=0.0`、`cap=1.0`，`distanceMultiplier` 恒为 1；
`config/reccomplex.cfg` 的 `structureSpawnChance=1.0`。所以问题只剩 `spawnChance`。

### 43.2 根因
`NaturalStructureSelector.GenerationInfo.spawnChance` 是 `public double`，Java 默认 **0.0**；
三个 `.rcst` 的 `generationInfo` 都没有这个字段。`SimpleCategory.spawnChance(biome, provider)`：

- `generationBiomes` 的 `BiomeExpression.test(biome)` 为 **true** → 用 `generationInfo.spawnChance`（0）；
- 为 **false** → 保留类别的 `defaultSpawnChances`（`1.5E-4`）。

所以 `ICE_FLATS` / `MUTATED_FOREST` 匹配上反而把概率乘成 0；`$SNOWY` 因为表达式没匹配上、
回退到 `1.5E-4`，所以 Shivaxi 一直能命中。`structuresInBiome` 里
`nextDouble() < chance × min(totalWeight/20,1) × distanceMultiplier × modifier`，
`chance=0` 时永远不产出，`planChunk` 返回空，`target plans=0`。

### 43.3 修复
在 `structures/active/` 的三个 `.rcst` 的 `generationInfo` 补 `"spawnChance": 1.5E-4`
（与 `ultrarare.rcnc` 的默认值一致）：

- `P_IceCity.rcst`
- `RLCraft_2_9_Update_Build.rcst`
- `Shivaxi.rcst`（保持行为一致）

原文件备份为同名 `.bak`；`worldData.nbt` 原样保留，只重写 `structure.json`。
这是**全局结构库**，所有世界共享；已生成的结构不变，只影响后续生成。

### 43.4 验证
启动后日志应出现：

```text
[ULTRARARE] selector probe ... spawnChances=[ultrarare=[1.5E-4], ...]
[ULTRARARE] deep scan finished (P_IceCity): ... N/N target plans ...
```

关键是 `spawnChances` 变成 `[1.5E-4]` 且 `target plans` 不再 `0/0`；是否命中仍受概率影响
（1200 半径期望 1.54 个，0 概率约 21%）。另需确认 RC 启动日志的结构数仍是
`135 structures known`，若减少说明 `.rcst` 重打包被 RC 拒绝，用 `.bak` 回滚。

### 43.5 部署
BUILD SUCCESSFUL `19:25`，jar 401,867 B，
SHA256 `74046863115c5f1c54dc26ee10e1d57eeb51c6d030553b94fd56120baa23fa21`，
`mods/` 同哈希，上一版备份 `build/deploy-backup/20261106-192542`。
版本仍 **1.2.0**，**未提交、未推送**。

## 44. 最终结论：P_IceCity 的 spawnChance 与表面表达式（2026-11-06 23:00，v1.2.0）

### 44.1 根因链
1. `GenerationInfo.spawnChance` 默认 `0`，三个 `.rcst` 都没写；`BiomeExpression` 匹配时用 0 →
   `structuresInBiome` 永远返回 0 → `pairs` 为空。
2. 运行时把 `ultrarare` 类别的 `spawnChance <= 0` 补成 `1.5E-4` 后，`pairs` 有 11,432 个，
   `P_IceCity` 被选中 6 次。
3. 6 次全部 `boundingBox()` 为空：`SelectivePlacer.sourceMatcher = "ICE"`，RLCraft 冰原表面
   多为雪/草。
4. 放宽为 `!air` 后 `rejected` 归零，但 6 个候选全部
   `skipped: RC generated the chunk during the dry-run and did not place it`。
5. 存档核对：当前世界 `测试-7` 的 265 条 RC 记录里**没有 P_IceCity**；`测试` 存档有 2 个
   真实记录（`(-23516,80,6814)`、`(-29839,80,14842)`）。所以 `测试-7` 的 6 个候选确实
   RC 没放置，不是扫描器误判。

### 44.2 最终代码状态
- `ensureSpawnChances(selector)`：`ultrarare` 类别的 `spawnChance <= 0` → `1.5E-4`。
- `relaxSourceMatcher(planned, placer)`：仅 `P_IceCity` 的 `sourceMatcher` → `!air`。
- `RcUltraRareLocator.knownHit(...)` + `DeepScanService.start` 短路：先查 RC 记录 / 仓库，
  有已知点直接回 `HIT`。
- 预筛 `ULTRARARE_PREFILTER_ENABLED = true`；`PROGRESS_EVERY=5M`、`CHAT_EVERY=10M`；
  `selector probe` 保留一次（只输出 `weightsSize` / `spawnChances`）。
- summary 恢复为 `chunks / eligible / candidate / rejected`，不再输出未收敛的诊断计数。

### 44.3 已知边界
- `测试-7` 没有 P_IceCity 记录；功能链路已通（选择器能选中、干跑能过、记录短路可用）。
- 想在新世界拿到 P_IceCity，需要 RC 真的生成过；换世界或换冰原区域比继续加半径有效。
- `sourceMatcher` 放宽只影响干跑，RC 实际生成仍按 `.rcst` 的 `ICE`，所以
  `skipped: RC did not place it` 会继续出现；这是「预测到了但 RC 没生成」的正常过滤。

### 44.4 发布
版本 **1.2.0**，提交并推送（`c55e0fb`）。GitHub Release 已发布：

- tag `v1.2.0` → `c55e0fb443031aaef8f5949c75a63fb1bb91afd6`
- Release: <https://github.com/Tom500101/rlcraft-explorers-compass/releases/tag/v1.2.0>
- 资产 `rlcraft-explorers-compass-1.2.0.jar`，`403,496` 字节，
  SHA-256 `ae951010c37bf8bb83d6fbd08551ba8e0486a8499a28bde43bf75c687f4bf4b6`。

发布走的是 API（本机 22 端口被封，HTTPS 的 `git push` 又受 schannel 吊销检查限制，
`curl --ssl-no-revoke` 可用）：先用 `POST /git/refs` 建 tag，再 `POST /releases`，
最后 `POST /uploads.github.com/.../assets` 传 jar。README 的下载链接与校验值同步改为 1.2.0。

## 45. 实验开关 turboScan：把三个超稀有的占空比拉满（2026-11-06 23:55）

### 45.1 数据依据
一次 `authorshall 5600` 实机扫描（18:08–18:30）：156,275,001 chunk / 1293 秒，
其中 995,767 个通过预筛。按 4 ms/tick 计算，有效 CPU 只有约 103 秒，
其余 **1192 秒都在等下一个 tick**。CPU 分解：

| 阶段 | 单 chunk 成本 | 总量 | 纯 CPU |
| --- | --- | --- | --- |
| RNG 预筛（64 draws，内联 LCG） | ~0.2 µs | 1.56 亿 | ~31 s |
| biome 采样（幸存 0.64%） | ~70 µs | ~100 万 | ~70 s |
| RC 重放 + 放置试算 | 毫秒级 | 2,140 | <1 s |

所以把预算提到 40 ms 就能把 21 分钟压到约 2 分钟；再往上没有意义，剩下的都是真 CPU 时间。

### 45.2 实现
- `CompassConfig.turboScan`（默认 `false`，写进 `config/rlcompass.cfg`），注释写明只影响三个
  超稀有、以及 TPS 代价。
- `/rlcompass turbo [on|off]`：改字段 → `ConfigManager.sync` 写盘 → **再把值写回字段**。
  这一步是必须的，和半径那次踩的坑同源：`sync` 会重读文件，把旧值盖回来。
  不带参数只报当前状态。
- 预算在 `tickDeepScan()` 与 `AuthorHallFastScan.tick()` 里**每 tick 读一次**，
  所以热切换对正在跑的扫描下一个 tick 就生效，不需要重开扫描。
- 日志与 summary 追加 `[turbo 40 ms/tick]`，两次跑测不会混。
- `rare`（`RcFastScan`）完全没动：用户只要三个超稀有。
- 顺手做了一项零风险微优化：作者大厅快扫的 deadline 检查从「每 chunk 一次」改成
  「每 128 chunk 一次」。1.56 亿 chunk 的跑测里 `System.nanoTime()` 本身约 4 秒；
  128 chunk 一批最坏 128 × 70 µs ≈ 9 ms，不会显著超支。

### 45.3 实测（2026-11-07 00:11–00:17，本机，三场连跑）
| 命令 | 扫描量 | 耗时 | 吞吐 | 对比非 turbo |
| --- | --- | --- | --- | --- |
| `scan icecity 2400` | 23,049,601 | **149 s** | 154.7k/s | 37.2k/s → **4.2×** |
| `scan shivaxi 1200` | 5,116,776（中途命中） | **34 s** | 150.5k/s | 37.2k/s → **4.0×** |
| `scan authorshall 5600` | 125,462,401 | **111 s** | 1.13M/s | 121k/s → **9.4×** |

- 基准吞吐：8 ms 的通用深扫取 19:50 那次（5,764,801 / 155 s = 37.2k/s）；
  4 ms 的作者大厅取 18:08 那次（156,275,001 / 1293 s = 121k/s）。
- 作者大厅倍率接近 4→40 ms 的理论值 10 倍；通用深扫只有 4 倍（8→40 是 5 倍，
  且 tile 采样在其中的占比更大）。
- 预筛比例稳定：这次 `801,562 / 125,462,401 = 0.639%`，上次 `995,767 / 156,275,001 = 0.637%`，
  说明 64 draws 的行为没有随版本漂移。
- `Can't keep up!` 111 秒内出现 **18 次**；用户实机反馈「没觉得很卡」。
- P_IceCity 仍是 0 candidate，但 `rejected = 4`：`578,424 × 7.5e-6 ≈ 4.3` 个期望选中，
  选中数与期望吻合，全部被 placer 的表面条件拒掉——再次确认是地形问题，不是扫描器。
- Shivaxi 命中 `(9086, ~, -18068)`，`0 rejected`，试算直接通过。

复测命令（重启游戏后）：

```mcfunction
/rlcompass turbo on
/rlcompass scan authorshall 5600
```

验收要点：日志开头变成
`[FASTHALL] fast scan for RLCraft_2_9_Update_Build started ... [turbo 40 ms/tick]`；
`/rlcompass turbo` 单独执行应报 `turbo is ON`。

### 45.4 边界
- 40 ms 是刻意选的上界：MC 自身每 tick 还要 5–10 ms，45 ms 之后只是更卡，不会更快。
- 想再快一个数量级必须并行化 biome 采样（101 秒里占 70 秒）。要处理 `BiomeCache` 共享
  （`IntCache` 是 ThreadLocal，GenLayer 无状态，但 `BiomeCache` 不是），而且候选必须回主线程
  做 `planChunk` / 放置试算。本次没做，属于后续可选路线。
- 部署：`405,088` 字节，SHA-256 `0b909e9f…`，`mods/` 同哈希。
  版本仍 **1.2.0**，**未提交、未推送**。

### 45.5 范围修正
第一版 `tickBudgetNanos()` 只看 `turboScan`，会把 `adventure`、`plainscity` 等也一起提速——
通用 tile 深扫不只服务超稀有。已改为 `turboActiveFor(deepTarget)`，只有
`category == "ultrarare"` 才用 40 ms；`turboTag()` 同步判断，日志不会误标。

### 45.6 turbo 下通用深扫也走预筛（00:29）
用户要求「统一速度」。差距的原因有两条：

1. 通用深扫必须**先**全量 tile 采样（`getBiomes`，等效 4.3 µs/chunk），才能知道哪个 chunk
   是冰原/雪原——这一项占了 149 秒里的约 99 秒；
2. 每个匹配群系的 chunk 都要 `planChunk`（冰原 2.5%、雪原 5%，单次约 86 µs），占约 50 秒。

改法：`scanTile` 在 `turboScan && ultrarare` 时**调换顺序**——先跑 64 draws / `1e-4` 预筛
（0.2 µs/chunk，放行 0.64%），只对幸存者调一次 `world.getBiome`（复用 `MutableBlockPos`），
再判定群系并重放。参数与 `AuthorHallFastScan` 完全一致，是同一条预筛模型。
`turbo` 关闭时仍走原来的全量 tile 采样，行为零变化。

- 新增统计：`summary` / `progress` 增加 `prefiltered` 字段，便于对比。
- 预期（`icecity 2400`，2305 万 chunk）：149 s → 约 20 s。

**召回验证（关键）**：用当前世界（`测试-8`，seed `-7876235295239507746`）核对已知真命中
`Shivaxi chunk(567,-1130)`：

```text
chunkSeed = -6435037275818507861
64 draws : min = 2.60404e-06 @ draw 4  -> PASS (阈值 1e-4)
```

draw 4 正好对应「前 4 个类别各掷一次、ultrarare 排最后」的结构，也解释了作者大厅 64 draws
为什么一直有效。**验收标准**：预筛版 `scan shivaxi 1200` 必须仍然返回 `(9086, ~, -18068)`；
如果变成 0 candidate，说明预筛漏判，立即回退。

一处错误的统计，记下来以免重犯：曾用「256 次内出现 `< 2.25e-5`」当目标代理，算出「75% 被
64-draw 挡住」。这是必然结果而非证据——最小值位置在 0–255 上均匀分布，落在 64 之后本来就是
75%。代理指标不能代替真实掷点位置，和 §36 是同一类错误。

部署：`405,483` 字节，SHA-256 `a326d265…`，`mods/` 同哈希。未提交、未推送。

### 45.7 命令短路、方形半径与「已生成」播报（11:41）
用户清空缓存仓库后重扫 `shivaxi 1200`，仍然 0 candidate。结论：**不是预筛漏判**。

- RC 记录里有 `Shivaxi 9086 131 -18068`；region 里 `chunk(567,-1130)`（`r.17.-36.mca`，
  offset=98，sectors=3）**已经生成**。
- 该 chunk 在 00:14 被预测到，玩家传送过去后生成，RC 就此定稿。11:35 再扫，
  `alreadyProcessed` 命中并跳过（设计如此：已定稿的区块重放只会回答「当年被拒绝」），
  于是 0 candidate。
- 两个真实缺陷：① 命令路径从不查 `knownHit`（只有 GUI 的 `DeepScanService` 查）；
  ② 跳过完全静默，玩家无法判断为什么 0 候选。
- 另外 `knownHit` 用欧氏距离、扫描用方形：`|x|=9086`、`|z|=18068` 的欧氏距离 20,226
  超过半径 19,200，造成「扫描覆盖得到、记录却查不到」的错位。

修复：
1. `CommandRLCompass` 的 `scan`（仅 `firstOnly`）与 `shivaxi` 分支先调 `knownHit`，
   命中直接回 `already recorded: ... — append all to force a full re-scan`；
2. `knownHit` 改方形判断，与扫描覆盖一致；
3. 新增 `deepSkipped` 计数与 summary 的 `already generated`；0 候选时额外播报
   `rlcompass.shivaxi.alreadyGenerated`。

### 45.8 预筛召回核对（`icecity 6250`，11:49）
```text
156,275,001 chunks, 998,022 prefiltered, 24,730 eligible,
0 candidate(s), 28 rejected, 0 already generated in 103 s
```

- 吞吐 152 万 chunk/s，对比 11-06 折算的约 8.4 万/s，**18 倍**。
- **候选数验证召回**：24,730 是「预筛放行的冰原 chunk」，条件概率
  `7.5e-6 / 0.00638 ≈ 1.18e-3`，期望 `24,730 × 1.18e-3 = 29.3`，实测 28，误差 4%。
  反推冰原总数 `24,730 / 0.00638 = 388 万`，与 1.56 亿 × 2.5% 一致。
  说明预筛放行的集合保住了真实命中。
- 28 个候选全被「生成后核实」拒：`P_IceCity` 的 `sourceExpression` 是 `ICE`，
  而 Shivaxi 与作者大厅是 `!(air | reccomplex:generic_space)`。冰原表面多为雪层/冻土，
  只有冻湖冰面才满足，所以「选得中、放不下」。

部署：`406,055` 字节，SHA-256 `22cd1a0e…`，`mods/` 同哈希。未提交、未推送。

### 45.9 `/rlcompass biome`（12:15）
用户给出一条产品原则，写在这里以免以后再偏离：**只做搜索，永远不改游戏本来的内容**。
具体到 P_IceCity，就是拒绝加「运行时把 `sourceMatcher` 从 `ICE` 放宽成 `!air`」的开关——
那不是搜索工具该做的事。因此本轮只增加诊断能力。

- `RcUltraRareLocator.biomeReport(World, BlockPos)`：首行是群系注册名与显示名，随后三个
  ultrarare 目标各一行 `MATCH` / `no` 加各自表达式，最后一行汇总其他匹配的目标。
- 命令 `/rlcompass biome`：直接输出上述行。
- 用途：区分「站错地方」与「运气不好」。扫描日志里 `eligible = 0` 表示群系判定从未通过，
  加半径没有意义；`eligible` 正常而 `candidate = 0` 才是这一次掷点没中。

部署：`406,872` 字节，SHA-256 `dc7fe83a…`，`mods/` 同哈希。未提交、未推送。

### 45.10 热门按钮覆盖半径、turbo 扩展到全部 RC 目标（13:42）
用户反馈：从热门列表里搜龙穴 / 沙漠黄金殿 / 绿宝石穹顶 / 普通黄金殿 / 两种城镇 / 海港时，
半径从 100000 掉回 10000。

根因在 `GuiStructureSelect.searchHot`：

```java
int recommended = StructureNameUtil.isUltraRare(structureId) ? 100000 : 10000;
if (CompassConfig.searchRadius != recommended) { ... radiusSlider.setValue(recommended);
                                                     persistRadius(); }
```

对非超稀有结构它把半径**写死成 10000 并落了盘**，所以点一下热门就等于把玩家的 100000 改掉。
修复后只对三个超稀有保证下限 100000，其余沿用滑条当前值。

turbo 同步扩展，用户要求「所有热门结构都加速，功能不变」：
- `turboActiveFor` 去掉 `category == "ultrarare"` 限制 → `adventure`、两种城镇、海港、
  罪恶学院、末地三结构都按 40 ms/tick 跑；
- `RcFastScan` 增加 `TURBO_TICK_BUDGET_NANOS` 与 `tickBudgetNanos()`，summary 追加
  `[turbo 40 ms/tick]`；
- 龙穴（`DragonRoostLocator`）与战斗塔是**同步 region 扫描**，不走 tick 分片，
  这个开关对它们不适用——它们慢是因为一次性读 region 文件，不是等待 tick。

关于用户「换了模型是不是改坏了什么」的疑问，用存档数据回答：`测试` 存档两个 P_IceCity
所在 region 的生成时间是 **11-05 22:34 / 23:35**，早于 11-06 19:25 的 `.rcst` 修改，更早于
本轮任何改动，所以原版就能生成；`测试-9` 的 94 次选中 0 落地，是因为 20/20 候选的 96 格内
都有 Y≥45 的中型结构（`JokerTower` / `UnnaturalCrater` / `NetherPortal` / `P_SnowTemple` 等），
`entriesAt(boundingBox)` 判定相交。与代码改动无关。

部署：`407,006` 字节，SHA-256 `35f7a2e2…`，`mods/` 同哈希。未提交、未推送。

### 45.11 真 bug：RC 数据损坏导致「生成后核实」误杀（14:22）
用户用 `测试的副本`（与 `测试` 同种子 `1392971457497389007`）从 (0,0) 搜 100000 格，
两座真实存在的 P_IceCity 没被报出，但 tp 过去方块确实在。

日志把过程写得很清楚：

```text
[14:09:34] candidate at (-23516, 6814) chunk(-1471,425) skipped: RC generated the chunk during the dry-run and did not place it
[14:09:38] candidate at (-29839, 14842) chunk(-1866,927) skipped: RC generated the chunk during the dry-run and did not place it
```

**扫描器的预测完全正确**——两个坐标与 11-05 实测生成的结构分毫不差。错在核实环节：

- 副本的 `reccomplex-structuredata.dat` 读取失败（`UTFDataFormatException: malformed input
  around byte 9`），RC 把它当作空数据；
- `getEntriesByID(target.rcName)` 于是在空集合上返回空，`hasStructureNear` 判定「没放」，
  候选被丢弃。

修复：`Replay.hasStructureNear` 在 RC 数据回答「没有」时回退到 `RecurrentComplexData.snapshot`
（本模组的 append-only TSV），±96 格内命中即保留候选。TSV 是从 RC 的 `[RC] recorded ...`
日志写出来的，属于「确实生成过」的直接证据。

顺带核对预筛召回：用日志里的**真实 chunk**（注意不是 `x>>4`，RC 记录的圆心偏移一格）重算——

| chunk | 64 draws 最小值 | 结论 |
| --- | --- | --- |
| `(-1471,425)` | `5.22e-06 @ draw 4` | PASS |
| `(-1866,927)` | `8.64e-06 @ draw 4` | PASS |

两次都落在 draw 4，与 Shivaxi 命中点同构（前 4 个类别各掷一次，ultrarare 第 5 次）。预筛无漏判。

另记：`测试/data/reccomplex-structuredata.dat` 已被 RC 重写为 81 字节（`checkedChunks` 为空），
该存档的 RC 元数据已丢失，只剩 TSV 备份；`测试的副本` 的 28 KB 数据虽然读不出，但内容完整。
用副本继续，别再用 `测试`。

部署：`407,295` 字节，SHA-256 `c3bf5c30…`（`deployToRlcraft` 本次被跳过，直接复制到 `mods/`）。

### 45.12 「生成后核实」测错了阶段：新区候选被整批丢弃（14:46）

#### 现象
`/rlcompass turbo on` + `/rlcompass scan icecity 6250`，在**全新**世界（种子
`1392971457497389007`，`新的世界--`）扫满 1.56 亿 chunk：

```
156275001 chunks, 997491 prefiltered, 24747 eligible, 0 candidate(s),
28 rejected by the placer, 0 already generated in 109 s [turbo 40 ms/tick]
```

28 个候选全部记为 `skipped: RC generated the chunk during the dry-run and did not place it`，
其中就包含 `chunk(-1471,425)` 与 `chunk(-1866,927)`——用户随后 tp 过去，两座冰原城市
**都在**，Y=80。

#### 取证
| 证据 | 结果 |
| --- | --- |
| `grep "recorded 'P_IceCity'"` | 只有 `14:37:05` / `14:37:41`（用户 tp 之后），扫描期间 0 条 |
| 14:34:40–14:34:58 的 `[RC] recorded` | 0 行 |
| `region/r.-46.13.mca` 全部 297 个区块的时间戳 | 全部落在 `14:37:40~14:37:52`，扫描的 14:34 没有写入 |
| 候选点区域文件（如 `r.38.19.mca`） | 不存在 |

结论：**放置干跑只生成地形，不触发 populate；RC 的结构放置与 `structureOverlap` 判定都在
populate 阶段完成。** 所以干跑刚结束时「查不到记录」是必然的，它不代表 RC 拒绝——
这条判据把「尚未决定」当成了「拒绝」，于是**每一个新区候选都被丢掉**，只有落在已生成区域的
候选才可能通过。之前 2026-11-07 上午 94 个候选全 0、20 个 tp 点全空，也都由这条错误判据
放大（那些点确实被别的结构占了，但判据说不出理由）。

#### RC 的真实判定（反编译 `StructureGenerator`）
```
RCConfig.avoidOverlappingGeneration
  && !allowOverlaps
  && !worldData.entriesAt(boundingBox).noneMatch(Entry::blocking)
  -> GenerationResult.Failure.structureOverlap
```
（`invokedynamic` 的 MethodHandle 是 `WorldStructureGenerationData$Entry.blocking:()Z`，
即只有 `blocking` 条目会阻挡。）

#### 修复
`Replay.placementY` 改为返回 `Placement{y, boundingBox}`；新增 `Replay.overlapAt(box)`，
用同一张 `WorldStructureGenerationData` 的 `entriesAt(boundingBox)` 复现上述判定，
命中的第一个 `blocking` 条目就作为占位结构名上报。候选处理改为：

```
placement == null                  -> rejected by RC's placer（地形不符，计数 deepRejected）
overlapAt(box) != null             -> 跳过并报出占位者（计数 deepOverlap）
否则                                -> 候选（confirmed / predicted 两种措辞）
```

`hasStructureNear` 保留，但只用于措辞（`confirmed by RC's record` vs
`predicted, RC decides when the chunk is populated`），不再是否决条件。

#### 验收
重启游戏后在 `新的世界--` 跑：

```mcfunction
/rlcompass scan icecity 6250
```

预期不再出现 28 个 `did not place it`；应报出 `chunk(-1471,425)` 或 `chunk(-1866,927)`
附近的候选并 `first hit` 停扫（新世界 `entriesAt` 为空，不会判重叠）。

部署：`409,365` 字节，SHA-256 `2376e487…`，`mods/` 同哈希。版本仍 **1.3.0 未发布**，
未提交、未推送。

### 45.13 作者故乡的传送落点：按结构高度锚定（16:05）

#### 现象
修复 §45.12 后用户首次成功找到作者故乡，但仓库「传送」把他送进了**地下冰窟**。

#### 原因
`SurfaceTeleport.find(world, x, z)` 只按 X/Z 处理：从该列最高点往下扫，取第一个
「脚下实心、头上两格空」的位置。`P_IceCity` 是下沉式城市——地面层在冰原表面之下，
中庭直通天空，于是这一列从上到下几乎全是空气，第一个可站立面出现在**地下室地板**。

#### 修复
`PacketTeleportRequest` 本来就带 `structureId`，第一版把带 id 的重载加在 `SurfaceTeleport`
里面（16:05）。用户随后要求**把作者故乡的逻辑单独成类，其他结构一律走旧逻辑**，于是 16:46
改成：

```
PacketTeleportRequest
  IceCityTeleport.matches(id) -> IceCityTeleport.find(world, x, y, z)
  找不到落点 / 其他结构        -> SurfaceTeleport.findNear(world, x, y, z)
```

`SurfaceTeleport` 为此完全恢复原样（删掉带 id 的重载与窗口版 `find`），新逻辑只存在于
`util/IceCityTeleport.java`：

```
IceCityTeleport.find
  窗口 [y-6, y+36]（BELOW=6 / ABOVE=36）
  环状搜索半径 16，逐列自上而下
  接受条件：实心、非液体、非危险方块、非树干；脚与头两格为空
```

窗口的设计意图：下界 `y-6` 把地下室排除在外，上界 `y+36` 让屋顶/上层先于地底被选中；
某一列在窗口内没有可用点时会继续向外扩环，因此即使正上方是天井，也能落到同一层级的平台上。
窗口内确实找不到落点才回退到通用规则，不会比旧行为更差。

`y` 来自仓库条目：扫描写入的是 `placementY` 的 `StructureBoundingBox.minY`（约 53），
RC 记录写入的是包围盒中心高度（约 80），两种锚点都落在窗口覆盖范围内。

#### 验收
在 `新的世界--` 打开仓库，对 `作者故乡` 点「传送」：应落在城市地面/屋顶，而不是冰窟；
`rlcompass.teleport.success` 播报的 Y 应明显高于 50（16:41 实测落在 `(-14905, 69, -1042)`）。
其他结构的传送点需回归一次（龙穴应在地下、天空之城应在岛上、末地污浊岛应在岛面）。

部署：`412,258` 字节，SHA-256 `c1ba1f40…`，`build/libs` 与 `mods/` 同哈希
（`deployToRlcraft` 自动落盘，上一版备份 `build/deploy-backup/20261107-160554`）。
版本仍 **1.3.0 未发布**，未提交、未推送。

### 45.14 预测候选的边界：只有作者故乡可以「赌」（16:46）

#### 现象
`shivaxi 6250`（16:35，`新的世界--`）：

```
16:36:13 candidate #1 for Shivaxi at (55662, ~, -34399) chunk(3478,-2151)
         predicted, RC decides when the chunk is populated
16:36:13 first hit: 48129431 chunks, 22039 eligible, 1 candidate(s), 21 overlap, 0 already generated
```

玩家传送过去是**空地**。同一轮里 `overlapAt` 已经替 21 个候选提前排除了冲突点，
只有这 1 个「无冲突但未确认」的预测混了过去。

#### 原因
§45.12 为了让新区能找到冰原城，把 `hasStructureNear` 从**否决条件**降级成了**措辞**。
对 `P_IceCity` 这是必要的（否则新区 28 个候选全被丢），但对所有目标一起放开就产生了
假阳性：RC 在填充阶段还可能因为**同批生成的其他结构**或地形一致性拒绝，扫描期看不到。

#### 修复
新增 `allowsPrediction(Target)`，只有 `rlcompass:icecity` 返回 true；三处候选判定
（tile 深扫 `scanChunk`、`Session.candidates`、同步 `Replay.scan`）都加了这一道：

```
confirmed（已有记录）                     -> 上报
!confirmed && allowsPrediction(target)     -> 上报为 predicted（仅作者故乡）
!confirmed && !allowsPrediction(target)    -> 丢弃，计入 deepRejected
```

`overlapAt` 保留（零成本，命中率 21/22）。聊天栏的「已排除 N 个位置」文案改为
「地形不符，或还没被 RC 确认」。

#### 代价（必须说明）
天空之城、作者大厅在**从未生成过**的区域会回到「扫不到」——这正是 14:46 之前的行为。
它们已有的记录仍会通过 `knownHit` 短路秒回；想在新区域找，需要先在该区域活动让 RC
自行生成，或接受 1 次空跑。这是「不给空坐标」的取舍，用户已确认按此执行。

#### 验收
重启后在同一位置跑：

```mcfunction
/rlcompass scan shivaxi 6250
```

预期：不再出现 `predicted` 的 Shivaxi 候选；有记录则秒回 `already recorded`，
没有记录则扫满后播报「没有候选」并提示换区域。作者故乡仍可返回 `predicted` 候选。

### 45.15 候选人不再靠猜：强制填充后核实（17:00）

#### 背景
用户问「你使用的是什么时期的代码」——**没有回退任何提交**，工作区始终是 1.3.0 的最新改动。
天空之城又找不到，是因为 16:46 的严格门控：没有记录就丢弃，于是新区只剩「扫不到」。
但这暴露了更根本的问题——扫描从来没有真正看到 RC 的决定。

#### 关键实现
`ChunkProviderServer.chunkGenerator` 是 `public final`，`Chunk.populate(IChunkProvider,
IChunkGenerator)` 与 `isTerrainPopulated()` 也都是公开 API（`build/classes/java/patchedMc` 反查确认）。
于是新增 `Replay.materialize(chunkX, chunkZ)`：

```java
ChunkProviderServer provider = (ChunkProviderServer) ((WorldServer) world).getChunkProvider();
Chunk chunk = provider.provideChunk(chunkX, chunkZ);
if (!chunk.isTerrainPopulated()) {
    chunk.populate(provider, provider.chunkGenerator);   // <- 这里才跑 GameRegistry.generateWorld
}
return true;                                             // RC 的决定此刻已定稿
```

候选流程变成：

```
placementY == null            -> rejected by RC's placer
overlapAt(box) != null        -> 跳过（会报占位结构名）
hasStructureNear              -> 命中（已有记录）
materialize 后 hasStructureNear -> 命中（真实放置）
仍无记录                       -> 跳过（RC 确实拒绝）
作者故乡例外                   -> 不 materialize，保持 predicted
```

作者故乡不 materialize 是有意的：它的 `SelectivePlacer` 在本模组里被放宽成了 `!air`
（见 §44），强制填充会让**扫描去创建城市**而不是定位城市。其他目标没有这个副作用。

#### 副作用与边界
- `Chunk.populate` 受 `isTerrainPopulated` 保护，玩家去过的区块不会被二次填充；
- 每个候选会生成结构足迹内的区块（Shivaxi 约 13×13），22 个候选实测可接受；
- 强制填充的顺序由扫描决定，可能与自然探索略有差异，但**结构确实会存在于存档里**，
  不会出现「报了坐标却没有」；
- 填充会把区块标记为已处理，下次扫描按 `alreadyProcessed` 跳过——语义正确。

#### 验收
重启后在同一位置重跑：

```mcfunction
/rlcompass scan shivaxi 6250
```

预期：要么命中**真实存在**的坐标（日志 `confirmed by RC's record`），要么明确报没有候选；
不再出现 `predicted` 的天空之城。作者故乡仍返回 `predicted`（其落点 16:41 实测
`(-14905, 69, -1042)`，用户已确认正确）。

部署：`412,876` 字节，SHA-256 `33cdb797…`，`build/libs` 与 `mods/` 同哈希。
版本仍 **1.3.0 未发布**，未提交、未推送。

### 45.16 回到 14:46 之前的判定，作者故乡单独例外（17:09）

#### 用户的判断
「今天最开始的代码，除了作者故乡其他的都能找到」——这是对的。中间两次"改良"都偏离了目标：

| 时间 | 改动 | 结果 |
| --- | --- | --- |
| 14:46 | `hasStructureNear` 降级为措辞 + `overlapAt` 预筛 | 作者故乡能找到了，但预测候选会报空点（16:36 天空之城） |
| 16:46 | 恢复严格门控，但 `overlapAt` 排在 `hasStructureNear` **之前** | 天空之城**又找不到**：目标的包围盒与它自己的记录相交，被自己挡掉 |
| 17:00 | 强制 `Chunk.populate` 后核实 | 能拿到 RC 的真实决定，但让扫描去推进世界生成 |

#### 当前实现（17:09）
三处候选判定（tile 深扫 `scanChunk`、`Session.candidates`、同步 `Replay.scan`）统一为：

```
placementY == null                        -> 排除（rejected by placer）
!allowsPrediction(target) && Y 已知 && 无记录 -> 排除
其余                                        -> 上报
```

`allowsPrediction(target)` 仅对 `rlcompass:icecity` 为 true。`overlapAt` 与 `materialize`
的调用与实现一并删除，结束摘要恢复「candidate(s) / rejected by the placer / already generated」。

`Y_UNKNOWN` 依旧**放行**——这是 14:46 之前就有的行为，不是新加的口子。

#### 作者故乡的两个专属
1. 搜索：允许未确认候选（`allowsPrediction`）；
2. 传送：`IceCityTeleport` 在 `[y-6, y+36]` 窗口内锚定。

另外它的 `SelectivePlacer.sourceMatcher` 仍被放宽为 `!air`（§44），这是「能找到城市」的前提，
也是唯一一处触碰游戏内容的地方，已向用户说明并等待其决定。

#### 验收
重启后在同一位置跑：

```mcfunction
/rlcompass scan shivaxi 6250
```

预期：与 14:46 之前一致——已有记录的目标直接命中；作者故乡仍返回候选（predicted）。

部署：`411,313` 字节，SHA-256 `2020949e…`，`build/libs` 与 `mods/` 同哈希。
版本仍 **1.3.0 未发布**，未提交、未推送。

### 45.17 真根因：扫描没等 RC 决定；撤掉 sourceMatcher 放宽（17:18）

#### 用户的指正
「同一种子，测试存档里有两座作者故乡，新世界同一坐标也生成了，但指南针找不到」——
这说明问题不在表面条件，而在**核实的时机**。`sourceMatcher` 放宽（11-06 加）只是让更多候选
通过干跑，与「本来就该找到的那两个坐标」无关，而且它改的是游戏内容。

#### 根因（复述 §45.12 的结论）
放置干跑只生成地形；RC 在 **populate** 阶段才放置并写记录。所以扫描查记录时永远是
「还没决定」，旧判据把它当成「RC 拒绝」，于是把真实候选取掉。

#### 最终实现（17:18）
1. **删除 `relaxSourceMatcher`**（连同 `sourceMatcherRelaxed` 字段与调用）——不再触碰
   `P_IceCity` 的 `sourceExpression: "ICE"`，恢复原版条件；
2. **候选改为强制填充后核实**，三处判定统一：

```java
boolean accepted = hasStructureNear(target, x, z);
if (!accepted && materialize(chunkX, chunkZ)) {   // Chunk.populate + isTerrainPopulated 保护
    accepted = hasStructureNear(target, x, z);
}
if (!accepted && placement.known()) { 排除 }
```

`materialize` 用 `ChunkProviderServer.chunkGenerator`（public final）与
`Chunk.populate(IChunkProvider, IChunkGenerator)`；玩家去过的区块不会被二次填充。

#### 与 17:09 的区别
17:09 撤掉 materialize 是因为当时它和 `allowsPrediction`、`overlapAt` 混在一起，把天空之城
误杀；这次只保留 materialize，且它作用于**所有**目标，没有特例。`overlapAt` 仍不启用
（它会命中目标自身记录）。

#### 验收（关键）
在 `新的世界--`（种子 1392971457497389007）站 (0,0) 跑：

```mcfunction
/rlcompass scan icecity 6250
```

预期：**`chunk(-1471,425)` 与 `chunk(-1866,927)` 会作为候选被报出**——即 `测试` 存档里
那两座城的坐标。日志出现 `confirmed by RC's record`，不再有
`relaxed P_IceCity sourceMatcher`。若某个候选被 `rejected by RC's placer`，说明该处表面
确实不是冰（原版也不会生成），属正确行为。

部署：`411,357` 字节，SHA-256 `9ee4076c…`，`build/libs` 与 `mods/` 同哈希。
版本仍 **1.3.0 未发布**，未提交、未推送。

### 45.18 干跑的「拒绝」不可信：先查记录，再强制填充（17:32）

#### 日志（17:25，世界 `1`，种子 1392971457497389007）
```
17:25:47 deep scan for P_IceCity started ...
17:28:22 finished: 156275001 chunks, 997491 prefiltered, 24747 eligible,
        0 candidate(s), 28 rejected by the placer, 0 already generated in 155 s
```

撤掉 `relaxSourceMatcher`（17:18）之后，28 个候选**全部在 `placementY` 阶段被拒**，一个都没
进入核实。而用户 `tp -23516 68 6814` 看到城市就在那里——即 `测试` 存档里的第一座城。

结论：**放置干跑的拒绝不可信**。它用 `SelectivePlacer` 在 `sealevel-10..sealevel` 里取一个源
位置测 `sourceExpression: "ICE"`，但 RC 真正生成时走的是另一条路径，能在这个位置放下。
干跑只适合回答「大概在哪一层」，不适合否决候选。

#### 新顺序（三处判定统一）
```java
boolean confirmed = hasStructureNear(target, x, z);          // 1. 有记录：直接认定
Placement placement = confirmed ? null : placementY(...);    //    干跑只用来取传送 Y
if (!confirmed) {                                            // 2. 没记录：强制填充
    materialize(chunkX, chunkZ);
    confirmed = hasStructureNear(target, x, z);              //    RC 的真实决定
}
if (!confirmed) { 排除 }                                      // 3. 填充后仍没有：确实没放
int y = placement != null && placement.known()
        ? placement.y : recordY(target, x, z);                // 传送 Y：干跑 → 记录兜底
```

`placement == null` 不再是淘汰理由；`recordY` 从 RC 记录里取包围盒中心高度作为传送锚点。

#### 回答「什么时候变成改游戏规则」
`relaxSourceMatcher` 是 **2026-11-06 19:48** 加的（当时为了绕开 ICE、让干跑能过），
14:46 的 `overlapAt` 方案本身是搜索判定，没有动游戏内容。问题在于 15:xx 用户明确「只做搜索、
不改游戏内容」后，我口头作废却没有删代码，它一直留在 `placementY` 里——17:18 已删除。
这次日志里不再出现 `relaxed P_IceCity sourceMatcher`，可作证明。

部署：`411,443` 字节，SHA-256 `64f42c17…`，`build/libs` 与 `mods/` 同哈希。
版本仍 **1.3.0 未发布**，未提交、未推送。

### 45.19 强制填充为什么没生效：Chunk.populate 要求四邻已加载（17:47）

#### 日志（17:43–17:45，世界 `2`）
```
17:43:54 ... candidate at (50023, 2221) chunk(3125,138) skipped: RC did not place it even after the chunk was populated
...
17:45:21 finished: 156275001 chunks, 24747 eligible, 0 candidate(s), 28 rejected, 0 already generated in 114 s
17:45:42 [CHAT] 你 传送到 -23515.5, 100.0, 6814.5
17:45:43 [RC] recorded 'P_IceCity' at (-23516, 80, 6814)     <- 玩家 tp 之后才写记录
```

新日志文案说明 17:32 版已加载，`materialize` 也确实被调用（28 次），但没有抛异常、也没有让
RC 放置。城市是在玩家 tp 的**那一秒**才生成的。

#### 根因（反编译 `net.minecraft.world.chunk.Chunk`）
`Chunk.populate(IChunkProvider, IChunkGenerator)` 开头会用 `getLoadedChunk` 检查
`(x-1,z)`、`(x+1,z)`、`(x,z+1)`、`(x-1,z)` 等邻居；只要有一个为 `null` 就**什么都不做直接返回**，
而真正干活的是受保护的 `populate(IChunkGenerator)`（内部才判 `isTerrainPopulated`）。
换言之：**孤立地填充一个区块是无效的**，正好解释了「扫描说没放、玩家一到就有」。

#### 修复
`materialize` 先 `provider.provideChunk` 生成 3×3 邻居，再填充中心区块（MC 会一次处理 2×2）。
代价是每个候选要多生成 8 个区块，扫描耗时会略增。

部署：`411,542` 字节，SHA-256 `48e64494…`，`build/libs` 与 `mods/` 同哈希。
版本仍 **1.3.0 未发布**，未提交、未推送。

## 46. 1.3.0 发布（2026-11-07 18:10）

### 46.1 验收依据
用户用种子 `1392971457497389007` 新建世界，从 `(0,0)` 出发依次搜索全部热门结构，
**全部命中**（含作者故乡）。据此把当前工作区定为 **1.3.0**。

### 46.2 本版内容
- turbo 扫描（40 ms/tick，覆盖全部 RC 目标）；通用深扫 RNG 预筛；`/rlcompass biome`；
- 候选改为「先查记录 → 未命中则强制填充（3×3 邻居 + `Chunk.populate`）→ 再查」；
- 作者故乡的传送落点独立为 `IceCityTeleport`；`SurfaceTeleport` 保持原样；
- 作者故乡的 `sourceExpression` 恢复原版 `ICE`（运行时放宽已删除）。

### 46.3 产物
- `build/libs/rlcraft-explorers-compass.jar`，`411,542` 字节；
- SHA-256 `61404c90d18adb97da56b0f507c38aee00bc76cf125110fcab073a8714a60916`；
- `mods/` 下同一哈希，版本号 `1.3.0`。

### 46.4 发布步骤（沿用 1.2.0 的经验）
本机 `git push` 的 22 端口被封、HTTPS 受 schannel 吊销检查限制，改用
`ssh://git@ssh.github.com:443/...`（主机密钥已核对）。Release 走 API：
先 `POST /git/refs` 建 tag，再 `POST /releases`，最后上传资产；`curl` 需 `--ssl-no-revoke`。

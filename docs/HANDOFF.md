# HANDOFF — 结构适配器状态

> 最后更新：2026-11-02
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
| `lostcities:city` | `LostCitiesLocator` | 是 | 是 | 未做 | 维度 111 `(152,64,296)` 75 格 / 74 ms |
| `roguelike:dungeon` | `RoguelikeLocator` | 是 | 是 | **已确认**（2026-11-02） | 见 §2 |
| `doomlike:dungeon` | `DoomlikeLocator` | 是 | 是 | 未做 | `(520,64,712)` 539 格 / 3 ms；变种搜索 `(5272,64,5400)` 105 格 / 9 ms 标 `unconfirmed` |
| `rlcompass:battletower`、`rlcompass:ruin` | `CacheLocator`（镜像） | 是 | 间接 | 未做 | 缓存重建/持久化验证过，但日志中 `via Cache` 命中 0 次（优先级最低，活适配器正常时轮不到它） |

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

## 3. HUD 行为（2026-11-02 调整）

1. **到达不再清空**（已改）：删除了 `CompassHudRenderer.activePlayer()` 里 `< ARRIVAL_RADIUS(16)` 的 `clear()` 分支。现在目标只会在三种情况下消失：切换维度、发起新的搜索且未找到、测试/退出重置（`ClientHudSelfTest:253`、`PacketStructureResult:135`、`CompassHudRenderer:184`）。
2. **目标行改绘制层**（已改）：从 `RenderGameOverlayEvent.Text`（Forge 左上角文字层，会被其他模组的药水/饰品面板覆盖）移到 `Post(ALL)`，画在箭头正下方 `screenHeight/2 + 54`，带半透明底色。`XYZ:` 行仍留在文字层。
3. **clienttest 同步**：`state.arrivalClears` → `state.arrivalKeeps`（断言 `hasTarget() == true`）。
4. **手持才显示**（已改）：`onRenderPost` 增加 `holdingCompass(player)` 门控——指南针在主手或副手才画箭头与文字；切到其他快捷栏格子立即隐藏，切回恢复（目标状态不清）。clienttest 的 `hud`/`arrow` 模式通过 `testOverlayActive()` 强制显示，因为测试假玩家手上没有物品。
5. **待实机确认**：重启客户端后确认 ① 目标行在箭头下方；② 距离 0 不消失；③ **切到其他格子隐藏、切回恢复**；④ 换维度清空。

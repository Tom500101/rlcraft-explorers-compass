# 阶段 1 结构探测器实测结果

> 生成日期：2026-10-28 · 环境：RLCraft 模组集（10 个 jar）+ Forge 14.23.5.2847 / MC 1.12.2 / MCP stable_39
> 复现方式：`gradlew runServer -Pprobe`（服务端启动后自动执行一次探测器）
> 原始日志：`run/logs/latest.log` 中 63 行 `[PROBE]` 输出
> 探测器代码：`src/main/java/com/tom500101/rlcompass/util/StructureProbe.java`

本文档是阶段 3/4/5 适配器的唯一依据。**凡与此文档冲突的计划书假设，以本文档为准。**

---

## 1. 关键修正：类名是 `ChunkGeneratorOverworld`

计划书（§2 风险表、§3 架构、阶段 1/3 任务）反复写的 `ChunkProviderOverworld`
在 1.12.2 **不存在**——那是 1.11 及更早的名字。

实测两个来源都确认现名：

- dev 反编译源码：`build/rfg/minecraft-src/java/net/minecraft/world/gen/ChunkGeneratorOverworld.java`
- 生产 SRG jar：`build/rfg/srg_merged_minecraft.jar` 内的
  `net/minecraft/world/gen/ChunkGeneratorOverworld.class`

**结论**：dev 与生产都用 `ChunkGeneratorOverworld`，不需要做类名分支。
只有**字段名**需要在 MCP 名 / SRG 名之间区分。

---

## 2. 第一节：`ChunkGeneratorOverworld` 的 `MapGenStructure` 字段

世界：`world`，维度 0，探测原点 `(220, 64, 264)`。
`chunkGenerator = net.minecraft.world.gen.ChunkGeneratorOverworld`（原版生成器未被整体替换）。

| MCP 字段名（dev） | SRG 字段名（生产） | 声明类型 | 运行时实际类 | `getStructureName()` |
|---|---|---|---|---|
| `strongholdGenerator` | `field_186004_w` | `MapGenStronghold` | `ivorius.reccomplex.events.handlers.RCTerrainGenEventHandler$5` | `Stronghold` |
| `villageGenerator` | `field_186005_x` | `MapGenVillage` | `ivorius.reccomplex.events.handlers.RCTerrainGenEventHandler$3` | `Village` |
| `mineshaftGenerator` | `field_186006_y` | `MapGenMineshaft` | `com.yungnickyoung.minecraft.bettermineshafts.world.MapGenBetterMineshaft` | `Mineshaft` |
| `scatteredFeatureGenerator` | `field_186007_z` | `MapGenScatteredFeature` | `ivorius.reccomplex.events.handlers.RCTerrainGenEventHandler$2` | `Temple` |
| `oceanMonumentGenerator` | `field_185980_B` | `StructureOceanMonument` | `ivorius.reccomplex.events.handlers.RCTerrainGenEventHandler$1` | `Monument` |
| `woodlandMansionGenerator` | `field_191060_C` | `WoodlandMansion` | `net.minecraft.world.gen.structure.WoodlandMansion` | `Mansion` |

附带确认的其它字段（阶段 3b 网格枚举会用到）：

| MCP | SRG | 类型 |
|---|---|---|
| `mapFeaturesEnabled` | `field_185996_o` | `boolean` |
| `caveGenerator` | `field_186003_v` | `MapGenBase` |
| `ravineGenerator` | `field_185979_A` | `MapGenBase` |
| `world` | `field_185995_n` | `World` |
| `settings` | `field_186000_s` | `ChunkGeneratorSettings` |

### 2.1 `getNearestStructurePos(world, origin, false)` 实测

| 结构 | 结果坐标 | 水平距离 | 耗时 |
|---|---|---|---|
| Stronghold | `(-712, 32, -1320)` | 1838 | 1 ms |
| Village | `(664, 64, 600)` | 557 | 2 ms |
| **Mineshaft (Better Mineshafts)** | `(1464, 64, 2056)` | 2181 | **9036 ms** |
| Temple | `(728, 64, -312)` | 768 | 0 ms |
| Monument | `(2312, 64, 1336)` | 2351 | 17 ms |
| Mansion | `(-14888, 64, 14328)` | 20641 | 166 ms |

> **⚠ 性能红线**：`MapGenBetterMineshaft#getNearestStructurePos` 单次 **9 秒**。
> 它跑的显然不是原版 `findNearestStructurePosBySpacing` 的轻量路径。
> 指南针搜索**绝不能**在主线程同步调用全部适配器——必须异步 + 超时 + 结果缓存，
> 否则一次右键卡服 9 秒。这条约束直接影响阶段 3/6 的设计。
>
> **已解决（阶段 3）**：根因是原版 `MapGenMineshaft#getNearestStructurePos` 做 1000 环螺旋，
> 每环都调 `canSpawnStructureAtCoords`，而 Better Mineshafts 的该重写会对每个候选调
> `World#getBiome`（会生成区块）；其中 99.7% 的候选本来会被随机骰子（`spawnRate=0.003`）否掉。
> `MineshaftLocator` 改为**先掷骰子、后查生态**，只把幸存者交给模组自己的实现，并保证
> 生成器 `Random` 的状态与原版一致。实测同一次查询从 `8976 ms` 降到 **`23 ms`**，
> 返回坐标完全相同（`(1464, 64, 2056)`），语义等价。

> **⚠ 正确性红线**：Mansion 返回 20641 格，远超计划书默认搜索半径 10000。
> 说明 `getNearestStructurePos` 的搜索不受我们的半径约束，适配器**必须**自己做半径过滤，
> 不能指望生成器返回半径内的结果。

---

## 3. 第二节：`GameRegistry` 注册的 `IWorldGenerator`

`GameRegistry.worldGenerators`（`Set<IWorldGenerator>`）**实测 5 个**，逐个扫描一层字段：

| # | 实现类 | 扫描到的字段 | 内含 `MapGenStructure`？ |
|---|---|---|---|
| 1 | `jaredbgreat.dldungeons.GenerationHandler` | 无实例字段 | **否** |
| 2 | `atomicstryker.ruins.common.RuinsMod$RuinsWorldGenerator` | `this$0 : RuinsMod` | **否** |
| 3 | `ivorius.reccomplex.world.gen.feature.GenerationSanityChecker$$Lambda$120/…` | 无实例字段 | **否** |
| 4 | `atomicstryker.battletowers.common.WorldGenHandler` | `biomesMap : HashMap`、`providerMap : HashMap`、`generator : AS_WorldGenTower` | **否** |
| 5 | `com.github.fnar.roguelike.worldgen.DungeonGenerator1_12` | **无实例字段** | **否** |

### 3.1 这条结果推翻了计划书的核心假设

计划书阶段 1 验收标准写着"对 Roguelike Dungeons，确认其内部 `MapGenStructure` 实例是否可反射获取"，
阶段 3 也把它列为"预期含 `MapGenStructure`、反射内部字段即可定位"的目标。

**实测答案：否。** `DungeonGenerator1_12` 没有任何实例字段，拿不到 `MapGenStructure`。
Roguelike Dungeons **不能**走阶段 3 的 `MapGenLocator` 路线，只能走阶段 4 缓存或它自身的 API。

同理，Ruins / Battle Towers / Doomlike Dungeons 三个 `IWorldGenerator` 也都不持有 `MapGenStructure`，
计划书把它们归到阶段 4 缓存是对的。

---

## 4. 八个目标模组的实测归属

| 模组 | 生成机制（实测） | 可用入口 | 计划阶段 |
|---|---|---|---|
| **Better Mineshafts** | 通过 `InitMapGenEvent.MINESHAFT` 替换 `mineshaftGenerator` 字段 | `ChunkGeneratorOverworld.field_186006_y` → `MapGenBetterMineshaft` | 3（但**必须异步**，9 秒） |
| **原版 Stronghold / Village / Temple / Monument** | `ChunkGeneratorOverworld` 字段 | 上表 SRG 字段名 | 3 |
| **原版 Mansion** | 同上，未被替换 | `field_191060_C` | 3 |
| **Recurrent Complex** | 用 `RCTerrainGenEventHandler$N` **包装**原生生成器；自身结构由 RC 的 worldgen 生成 | 原生四个入口只能定位到"被包装的原版结构"；**RC 自有结构不在其中** | 5（专项） |
| **Roguelike Dungeons** | `IWorldGenerator`，**无** `MapGenStructure` | 无标准入口 | 4（缓存）/ 5（专项） |
| **Ruins** | `IWorldGenerator`，无 `MapGenStructure` | 无 | 4（缓存） |
| **Battle Towers** | `IWorldGenerator` → `WorldGenHandler.generator : AS_WorldGenTower` | 该字段是 `AS_WorldGenTower` 不是 `MapGenStructure`，但其内部可能持有已生成塔坐标 | 4（缓存）/ 5（专项） |
| **Doomlike Dungeons** | `IWorldGenerator`，无字段 | 无 | 4（缓存） |
| **Antique Cities** | **两个列表中都没出现** | 未探明 | 需额外探测 |
| **Lost Cities** | 替换 `IChunkGenerator`，独立维度（日志见 `Unloading dimension 111`） | 维度级入口 | 5（专项）/ 4（该维度缓存） |

---

## 5. 对后续阶段的直接影响

1. **阶段 3 范围收缩**：只剩原版 6 类结构 + Better Mineshafts 走 `MapGenLocator`，
   共 7 个入口，全部来自 `ChunkGeneratorOverworld` 的 6 个字段（stronghold/village/mineshaft/
   scatteredFeature/oceanMonument/woodlandMansion）。
2. **阶段 3 必须先解决异步**：mineshaft 9 秒意味着 `IStructureLocator` 接口的
   `findNearest` 不能是纯同步阻塞式设计，或者至少要有"跳过昂贵适配器"的策略。
   计划书 §3 的数据流（右键 → 同步遍历 → 排序）需要调整为异步任务 + 超时。
3. **阶段 4 权重上升**：Roguelike Dungeons 从"预期反射"降级为"缓存"，阶段 4 覆盖的模组
   从 3 个（Ruins/Battle Towers/Doomlike）扩展到 4 个。
4. **阶段 5 需要先探测 RC 自己的结构注册表**：RC 的 `GenerationSanityChecker` 只注册了一个
   worldgen lambda，RC 自有结构的坐标必然存在别处。计划书说的"研究 RC 的 `/getStructureName`
   命令"仍是正确方向，但要新增一项：反射 RC 的 `WorldGenStructures` / 结构注册表。
5. **Antique Cities 是新的未知项**：它既不在 `ChunkGeneratorOverworld` 字段里，也不在
   `GameRegistry.worldGenerators` 里。下一步要么再探一层（`InitMapGenEvent` 的其它 EventType、
   `IChunkGenerator` 替换、`WorldSavedData`），要么直接放弃并用缓存覆盖。

---

## 6. 待补充探测（阶段 3 前完成）

- [ ] Antique Cities 的生成入口（当前完全未探明）
- [ ] Lost Cities 的 `IChunkGenerator` 实际类与城市定位能力
- [ ] Recurrent Complex 自有结构的坐标来源（`GenerationSanityChecker` 之外）
- [ ] Roguelike Dungeons 是否有 `WorldSavedData` 记录已生成地牢
      （日志中有 `Successfully generated dungeon with id builtin:forest at {...}`，
      说明它知道坐标，只是需要找到存储位置）
- [x] **Battle Towers 已确认：持久化到纯文本位置文件**（见 §7）
- [x] **Ruins 已确认：持久化到纯文本位置文件**（见 §7）

---

## 7. 意外收获：两个模组自带位置文件（阶段 4 直接受益）

阶段 2 的运行日志暴露了比计划书设想的"特征方块扫描"可靠得多的数据源——
Battle Towers 和 Ruins 都会把已生成结构的坐标写成**纯文本**，按维度分目录：

```
run/world/BattletowerPositionsFile.txt          # 维度 0
run/world/DIM-1/BattletowerPositionsFile.txt    # 下界
run/world/DIM1/BattletowerPositionsFile.txt     # 末地
run/world/LOST/BattletowerPositionsFile.txt     # Lost Cities 维度
run/world/RuinsPositionsFile.txt                # 上述四个维度各有一份
```

### Battle Towers 格式（模组自己在文件头写明）

```
xCoordinate yCoordinate zCoordinate towerType towerUnderground
```

`towerType`：0=Null, 1=Cobblestone, 2=MossyCobblestone, 3=Sandstone, 4=Ice,
5=Smoothstone, 6=Nether, 7=Jungle。
实测 6 条记录，例如 `16 88 64 5 false`、`320 61 320 6 false`。

### Ruins 格式

```
xMin yMin zMin xMax yMax zMax templateName
```

是有界盒而非单点，`templateName` 形如 `StoneGrill.tml`。实测 10 条记录。

> **注意**：Ruins 文件头声明 "file gets wiped upon flushing"，说明它是周期性快照而非权威数据源。
> 阶段 4 应优先读取模组内存中的记录（`WorldGenHandler` / `ChunkLoggerData`），文件只作为
> 进程重启后的补充。

**结论**：Battle Towers 与 Ruins **不需要**计划书阶段 4 设计的特征方块检测，
直接读它们自己的记录即可，漏检 / 误检风险归零。

---

## 8. 阶段 2 顺带确认的环境事实

- 服务端启动日志显示 `Netty` 通道正常（`Using default channel type`），`rlcompass`
  的 `SimpleNetworkWrapper` 通道注册无异常。
- `@Config` 正常生成 `run/config/rlcompass.cfg`，5 个配置项的注释、区间、默认值均正确。
- Lost Cities 的维度 id 是 **111**，目录名 `LOST`（见上表），阶段 5 可直接按维度处理。

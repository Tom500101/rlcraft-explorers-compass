# RLcraft Explorer's Compass

RLCraft 专属的探险者指南针模组，面向 Minecraft 1.12.2 Forge。

原版工具（以及现有的 `Where's That Structure?`）在 1.12.2 下无法可靠地定位模组结构，
因为 1.12.2 缺少统一的结构注册表。本项目针对 RLCraft 的结构模组做专项适配，
在可配置的搜索半径内（默认 10000 格，可调到 100000 格）按**距离最近优先**返回结构坐标。

除了「已生成结构」的直接读取，模组还对 Recurrent Complex 的超稀有结构（如 **Shivaxi 天空之城**，
约每 130 万个区块一座）实现了**确定性重放 + 放置试算**：只用世界种子、群系和 RC 自己的选择器，
就能在**从未生成过的区块**里算出候选坐标，并提前剔除会被 RC 放置检查拒绝的位置。

## 项目信息

| 项目 | 值 |
| --- | --- |
| 模组 ID | `rlcompass` |
| 版本 | `1.1.0`（变更见文末「版本记录」） |
| 显示名称 | RLcraft Explorer's Compass |
| 作者 | tom500101 |
| Minecraft | 1.12.2 |
| Forge | 14.23.5.x |
| 开发模板 | [CleanroomMC TemplateDevEnv](https://github.com/CleanroomMC/TemplateDevEnv)（Kotlin 版；模板自带的 Kotlin JVM 插件与 Forgelin-Continuous 依赖已移除，项目为纯 Java） |
| Gradle / RFG | Gradle 9.2.1 + [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle) 2.0.6 |
| 语言 | Java 8（56 个 `.java` 源文件；构建脚本 `build.gradle.kts` 为 Kotlin DSL，模板默认携带的 Kotlin JVM 插件与 Forgelin-Continuous 依赖均已移除，源码纯 Java） |
| 目标环境 | RLCraft 及其衍生整合包 |

## 核心设计

指南针不依赖单一搜索方法，而是采用**分层定位策略**，为不同生成机制的模组提供各自的适配器：

1. **标准 `MapGenStructure` 适配器**
   通过反射从 `ChunkGeneratorOverworld` 提取生成器实例，调用其定位逻辑。
   覆盖 Better Mineshafts 等替换原版生成器的模组，以及 Roguelike Dungeons
   这类内部持有 `MapGenStructure` 的模组（需反射其内部字段）。

2. **生成时缓存适配器**
   对 Ruins、Battle Towers、Doomlike Dungeons、Recurrent Complex 等
   非标准生成机制的模组，在世界生成时检测结构特征并记录坐标到
   `WorldSavedData`，搜索时直接查询缓存。

3. **模组专项适配器**
   对 Recurrent Complex 等有自身定位 API 的模组，
   优先调用其内部逻辑，无法调用时退回缓存方案。

4. **确定性重放 + 放置试算**（超稀有结构专用）
   只依赖 `worldSeed`、群系和模组自己的选择器，遍历未生成区块：
   - `ChunkGeneratorOverworld.populate` 的随机数是 `x*k + z*l ^ worldSeed`，已用运行时探针
     逐位比对确认；
   - Recurrent Complex 的类别迭代顺序来自 `HashMap` 身份哈希，逐会话不同，
     模组会把它钉死成确定序（否则同一 chunk 在不同会话里会掷出不同类别）；
   - 命中后用 RC 自己的 `StructureGenerator.boundingBox()` 做**纯试算**（内部调
     `Placer.place(...)`，返回 `DONT_GENERATE` 即拒绝），只读地形、不放方块，
     因此「预测到坐标但过去是空地」被提前过滤掉。

所有适配器统一实现 `IStructureLocator` 接口，指南针汇总各适配器的候选坐标，
按与玩家的距离排序，返回最近的一个。

## RLCraft 结构模组支持计划

| 模组 | 生成机制（实测） | 方案 | 状态 |
| --- | --- | --- | --- |
| 原版 6 类结构 | `ChunkGeneratorOverworld` 字段 | `MapGenLocator` | 已实现 |
| Better Mineshafts | 替换 `mineshaftGenerator` → `MapGenBetterMineshaft` | `MineshaftLocator` 有界搜索（8976 ms → **23 ms**） | 已实现 |
| Roguelike Dungeons | `IWorldGenerator` 无字段，但 `Dungeon.canSpawnInChunk` 是纯判定函数 | `RoguelikeLocator` 螺旋判定 | 已实现（实测 308 格 / 2 ms） |
| Antique Cities | **不生成结构**：Antique Atlas 的地图标记插件 | 无需适配 | 不适用 |
| Doomlike Dungeons | `IWorldGenerator`；`isDungeonCenter` 判定函数 | `DoomlikeLocator` 螺旋判定 | 已实现（实测 539 格 / 3 ms） |
| Ruins | `IWorldGenerator`，无 `MapGenStructure`；自带位置文件 | `RuinsLocator` 读位置文件（包围盒中心） | 已实现 |
| Battle Towers | `IWorldGenerator`；自带内存坐标表 + 位置文件 | `BattleTowersLocator` 读 `WorldGenHandler.worldMap`；为空时回退读存档里的 `BattletowerPositionsFile.txt`（应对 RLTweaker Consistent 模式） | 已实现 |
| Recurrent Complex | 包装原生生成器；自有结构记录在 `WorldStructureGenerationData` | `RecurrentComplexLocator` | 已实现（实测 `(220,84,265)` / 3 ms） |
| 冰与火：龙巢 / 龙穴 | `event.StructureGenerator` 按 chunk 随机触发，无位置记录 | `DragonRoostLocator` + `ModdedStructureRegionScanner` 扫描 region 的 12 位方块 ID；用**宝藏深度**区分龙巢（Y 57+）与龙穴（Y ≤ 25） | 已实现（判出的龙穴与存档里 `AgingDisabled=1` 的龙实体坐标吻合） |
| 恐怖生物：地牢 | 自有 `ExtendedWorld.dungeons`（`DungeonInstance` 含 `originPos` + `schematic`） | `LycanitesDungeonLocator` 优先反射该记录，失败退回 region 扫描；7 个变体 | 已实现（存档内 6 条记录，可与扫描结果互证） |
| 冰与火：Shivaxi 天空之城 | Recurrent Complex `ultrarare` 类别，`$SNOWY` 群系，`requiredConformity=0.9914` | `ShivaxiLocator`：确定性重放 + RC 放置试算；`/rlcompass shivaxi` 做百万级区块深度扫描 | 已实现（加入放置试算后 2/2、2/2 全部命中真城） |

状态列来自 [`docs/probe-result.md`](docs/probe-result.md)（阶段 1 在真实 RLCraft 模组集下的实测）。
探测器结果推翻了计划书的两条假设：`ChunkProviderOverworld` 这个名字在 1.12.2 不存在（应为
`ChunkGeneratorOverworld`），且 Roguelike Dungeons **无法**通过反射拿到 `MapGenStructure`。

## 当前进度

- [x] 开发环境搭建（RetroFuturaGradle + JDK，`gradlew build` 通过）
- [x] `gradle.properties` / `build.gradle.kts` 中的身份与版本配置（mod id、maven group、作者、模组名注入）
- [x] 本地模组依赖接入（`libs/` + `rfg.deobf`，10 个 jar 全部加载）
- [x] 替换模板残留：`mcmod.info` / `pack.mcmeta` 已改为 rlcompass，源码包改为 `com.tom500101.rlcompass`
- [x] 结构探测器：打印 RLCraft 环境下可获取的 `MapGenStructure` 实例 → `docs/probe-result.md`
- [x] `IStructureLocator` / `StructureLocatorRegistry`（`MapGenLocatorRegistrar` / `ModLocatorRegistrar`）/ `StructureResult` 框架
- [x] 网络通道（`SimpleNetworkWrapper` + 3 个数据包：`PacketStructureResult` / `PacketOpenCompassGui`（服务端→客户端）、`PacketSearchRequest`（客户端→服务端））
- [x] 配置文件（搜索半径、结构黑名单、缓存频率、慢适配器告警、精确坐标开关）
- [x] 生成时缓存适配器 `StructureCacheData`（WorldSavedData）+ `/rlcompass rebuildcache`，
      已验证重启后从磁盘读回 16 条记录
- [x] 标准 `MapGenStructure` 适配器（6 个原版结构 + Better Mineshafts，自测总耗时 221 ms）
- [x] Battle Towers / Ruins 适配器（读模组自有数据，命中率 100%，无特征方块检测）
- [x] Recurrent Complex 专项适配（`RecurrentComplexLocator` 反射 `WorldStructureGenerationData`；集成测试确认可定位）
- [x] 指南针物品 `explorers_compass`（`RegistryEvent.Register<Item>` 注册，模型 + 语言文件已打包）
- [x] 搜索链路 `ItemExplorersCompass.search()` → `PacketStructureResult` → 客户端状态
- [x] 选择 GUI `GuiStructureSelect` + HUD `CompassHudRenderer`（文本 + 箭头）
- [ ] 实机视觉确认（创造栏图标、GUI 布局、HUD 箭头朝向）——半自动冒烟测试用 `./gradlew runClient -Pclienttest=<mode>`（手工启动时用 `-Drlcompass.clienttest=<mode>` 兜底）；完整清单见 [`docs/runtime-verification.md`](docs/runtime-verification.md)
  - 已实机验证：hud 6/6、gui 9/9、registry 8/8；arrow/state/angle 3/3；`all` **32/32 PASS**；`search` **4/4 PASS**——所有 clienttest mode 已实机验证，运行期验证闭环。人工视觉确认（HUD 箭头朝向、GUI 布局、创造栏图标等）仍需按文档第 5 节人眼确认。详见上述文档的「实机验证状态」列。

集成测试结果见 [`docs/integration-test.md`](docs/integration-test.md)：
计划书阶段 8 的 8 项验收**全部通过**。
可用结构 ID 覆盖 vanilla 6 类、Roguelike / Doomlike / Ruins / Battle Towers / Recurrent Complex，
以及冰与火龙巢·龙穴、恐怖生物地牢、Shivaxi 天空之城；常规结构单次搜索最慢约 151 ms，
超稀有结构按 §「超稀有结构」一节单独说明。

## 后续扩展（2026-11）

在阶段 8 验收之后追加的功能，均已实机验证：

- [x] **缓存仓库**（`SearchMemory` + `GuiSuggestionWarehouse`）：每次搜索自动记录结构名 / 坐标 /
      三态（未探索 · 已找到 · 未找到），支持筛选、改状态、逐条删除；已记录的点不会再被搜到。
- [x] **维度标注**：主界面每行右侧标出生成维度；搜索时若维度不符，直接回一条
      「该结构不在当前维度」，而不是笼统的「没找到」。
- [x] **冰与火龙巢 / 龙穴**：`ModdedStructureRegionScanner` 解析 1.12.2 的 12 位方块 ID
      （`Blocks` + `Add`），用宝藏深度区分龙巢与龙穴。
- [x] **恐怖生物地牢**：优先读模组自己的 `ExtendedWorld.dungeons`（精确、带 schematic 变体），
      退回 region 扫描。
- [x] **Shivaxi 天空之城**：确定性重放 + RC 放置试算，见下节。
- [x] **GUI 搜索半径滑条**：左栏直接拖动，松手即写回 `config/rlcompass.cfg`。

## 超稀有结构（Shivaxi 天空之城）

Shivaxi 是 Recurrent Complex 的 `ultrarare` 类别结构，只在 `$SNOWY` 群系生成。
它的真实概率比资料里的「每 6666 区块」低得多：

```
每 chunk 抽中 ultrarare = 1.5e-4 × min(3/20, 1) = 2.25e-5
其中是 Shivaxi          = ÷ 3 = 7.5e-6（每个雪原 chunk）
再乘放置检查通过率      → 约 1 / 130 万 chunk
```

所以它不是「搜索半径内可能没有」，而是**默认半径内几乎必然没有**。模组为此提供三层处理：

| 层 | 做法 | 作用 |
| --- | --- | --- |
| 随机数 | 探针读 `PopulateChunkEvent.Pre` 里 `Random` 的真实内部状态，与 `x*k + z*l ^ worldSeed` 逐位比对 | 确认可以离线重放 |
| 类别顺序 | RC 用 `ArrayListMultimap`（`HashMap` 支撑）且类别对象未重写 `hashCode`，迭代顺序逐会话不同；模组把它换成按注册名排序的 `LinkedHashMultimap` | 让「重放」与「实际生成」一致，且跨会话稳定 |
| 放置试算 | 命中后反射构造 RC 的 `StructureGenerator`，调 `boundingBox()`（内部 `Placer.place(...)`，返回 `DONT_GENERATE` 即拒绝） | 只读地形、**不放方块**，提前剔除「过去是空地」的候选 |

实测（半径 1200 区块 = 19200 格，577 万 chunk，约 5 分钟）：

```
deep scan finished: 5764801 chunks, 345509 eligible, 4 candidate(s) in 294 s
```

未加放置试算时 4 个候选里 1 个是真城（其余被 `requiredConformity=0.9914` 拒绝）；
加入试算后两次实测均为 **2/2 全部命中**，被剔除的坐标玩家逐个核对过确实为空。

```mcfunction
/rlcompass shivaxi 1200     # 半径 1200 区块，后台分片运行，进度与候选直接发到聊天框
/rlcompass shivaxi cancel   # 中止
/rlcompass rccheck 10       # 自检：能否复现已记录的 RC 结构（验证重放链路）
```

深度扫描用 `ServerTickEvent` 分片，每 tick 只占约 4 ms，可以边玩边等。

**边界**：预测的是「RC 会在这里选中 Shivaxi」，放置检查已尽量提前过滤，但地形是生成时才确定的；
扫描会读取（并因此生成）候选结构范围内的区块，但不会放置任何方块。

## 开发环境

### 前置要求

- **JDK 25** 用于运行 Gradle。RFG 2.0.x 的插件字节码是 Java 25（class file 69），
  JDK 21 及以下会直接报 `UnsupportedClassVersionError`
- **JDK 8** 用于 Java 8 编译目标，以及启动 1.12.2 客户端/服务端
- **JDK 21**：RFG 的 `applyJST` 任务需要它
- 三个 JDK 的路径写在 `gradle.properties` 的 `org.gradle.java.installations.paths` 里
- 网络可访问 GTNH Nexus、Forge Maven、Mojang 资源；Gradle 发行版走腾讯云镜像
  （见 `gradle/wrapper/gradle-wrapper.properties`）

> **foojay-resolver 插件已被移除**：它所有已发布版本（0.4.0 … 1.0.0）都引用了
> `JvmVendorSpec.IBM_SEMERU`，而该字段在 Gradle 9 中已删除，插件一加载就中断构建。
> 因此 toolchain 改为只从本地已安装的 JDK 解析，详见 `settings.gradle.kts` 中的注释。

> 构建与网络环境的排障历史（JDK 25 / 代理 / 镜像等）见 [`docs/environment-diagnosis.md`](docs/environment-diagnosis.md)。

### 初始化

```bash
git clone https://github.com/Tom500101/rlcraft-explorers-compass.git
cd rlcraft-explorers-compass
```

用 IDEA 打开项目目录，在
`Settings > Build, Execution, Deployment > Build Tools > Gradle`
中把 **Gradle JVM** 指向 **JDK 25**，然后点击 Gradle 面板的 `Refresh All`。

命令行等价写法：

```bash
gradlew build -Dorg.gradle.java.home=/path/to/jdk-25
```

首次构建需要下载并反编译 Minecraft / Forge（反编译一步实测约 2.5 分钟），属正常现象。

### 构建后自动部署

`build` 结束时会自动跑 `deployToRlcraft`：把 `build/libs/rlcraft-explorers-compass.jar`（reobf 后的产物，
不是 `-dev`）复制到整合包的 `mods/`，并先替换掉同名的旧 jar。

```
rlcraft_mods_dir = C:/Users/<you>/AppData/Roaming/.minecraft/versions/RLCraft/mods
```

- 路径写在 `gradle.properties` 的 `rlcraft_mods_dir`，换机器改这一行即可；未设置或目录不存在时任务只打日志、不报错。
- 旧 jar 会先备份到 `build/deploy-backup/<时间戳>/`，只保留最近 10 份；需要回滚时直接拷回去。
- 目标 jar 与产物字节一致时跳过（不会每次都重写 mods 目录）。
- **游戏运行中 jar 被占用**：任务打一条警告并中止本次部署，不会让构建失败；关掉游戏后 `gradlew deployToRlcraft` 重试。
- 只动名字以本模组 jar 名开头的文件，`mods/` 里其他 200 多个 jar 不会被读取或删除。

### 本地模组依赖

RLCraft 的结构模组 jar 放在项目根目录的 `libs/` 下，`build.gradle.kts` 会自动加载并反混淆：

```kotlin
val libsFileTree = fileTree("libs") { include("*.jar") }
if (!libsFileTree.isEmpty) {
    implementation(rfg.deobf(libsFileTree))
}
```

`libs/` 中放的是第三方模组 jar，仅作本地编译依赖，已被 `.gitignore` 排除
（`libs/` 与本地工具链 `.tools/` 均不入库），无需手动清理。

### 运行

Gradle 面板中执行 `Tasks > forgegradle runs > runClient`，
或直接使用 IDEA 运行配置 `1. Run Client` / `2. Run Server` / `3. Run Obfuscated Client` / `4. Run Obfuscated Server`。

## 获取指南针

安装模组后，指南针通过下面的有序配方合成：

```text
      [铁锭]
[铁锭] [指南针] [铁锭]
      [末影珍珠]
```

`assets/rlcompass/recipes/explorers_compass.json`

创造模式下位于**工具**（Tools）标签页，物品 ID 为 `rlcompass:explorers_compass`。
想直接拿到手：

```mcfunction
/give @p rlcompass:explorers_compass
```

## 界面

右键指南针打开结构选择界面。布局参考了 1.12.2 上经典结构定位模组的做法，三区响应式排布
（所有尺寸由窗口宽度/高度推导，任何 GUI 缩放比例下都会自动重排）：

```text
┌──────────── 头部：标题 + 筛选输入框 ────────────┐
│                                                 │
├────────────┬────────────────────────────────────┤
│  操作       │  结构目录                          │
│  [ 搜索 ]   │  ┌──────────────────────────┐      │
│  [ 排序 ]   │  │ Village            主世界  │      │
│  [ 仓库 ]   │  │ 来源: minecraft           │      │
│  ▓▓▓░░░░░░  │  │ ID: minecraft:village    │      │
│  半径 10000 │  └──────────────────────────┘      │
│  半径越大，  │  （滚动列表 + 金色滚动条）            │
│  搜索越久    │                                    │
│  [ 取消 ]   │                                    │
└────────────┴────────────────────────────────────┘
```

| 控件 | 行为 |
| --- | --- |
| 筛选框 | 输入即筛选；`@minecraft` 这类 `@` 前缀按来源模组过滤 |
| 列表行 | 单击选中，双击直接搜索；选中行边框变为金色；右侧标注生成维度 |
| 搜索 | 对选中结构发起搜索并关闭界面 |
| 排序 | 在「名称 / 来源」之间循环 |
| 仓库 | 打开缓存仓库（增删改查 + 三态） |
| **半径滑条** | 拖动调整 100–100000 格，步长 100；**松开鼠标即写回 `config/rlcompass.cfg`**（拖动过程不写盘） |
| 取消 | 关闭界面（`ESC` 亦可，输入框聚焦时也不会吞掉 ESC） |

滑条下方常驻一行「半径越大，搜索越久」；选中 Shivaxi 这类超稀有结构时会追加
「超稀有结构：建议半径 100000，搜索可能需要数分钟」，避免玩家在默认半径下白等。

配色是深蓝底 + 金强调色（`#D8C58C`），定义集中在 `CompassPalette`，四个自制控件
（`TransparentButton`、`TransparentEditBox`、`StructureSearchList`、`RadiusSlider`）
共用同一套边框与阴影，避免原版贴图化控件和暗色面板冲突。

## 命令

`/rlcompass` 需要权限等级 2（单机需开启作弊）。

| 子命令 | 作用 |
| --- | --- |
| `/rlcompass list` | 列出当前世界可搜索的结构 id 及其生成维度 |
| `/rlcompass find <id>` | 定位并输出坐标 / 来源 / 耗时 |
| `/rlcompass search <id>` | 与指南针走同一条链路（含回到客户端的数据包） |
| `/rlcompass cache` | 查看生成缓存条目数 |
| `/rlcompass rebuildcache` | 重建生成缓存 |
| `/rlcompass rccheck [n]` | 自检：用最近 n 条 RC 记录验证重放链路（默认 10） |
| `/rlcompass shivaxi [半径区块]` | 超稀有结构深度扫描（默认 1200 区块，约 5 分钟） |
| `/rlcompass shivaxi cancel` | 中止扫描 |

## 配置文件

客户端/服务端首次启动后生成 `config/rlcompass.cfg`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `searchRadius` | `10000` | 最大搜索半径（格），区间 100–100000；也可在指南针界面里拖滑条修改，会自动写回本文件 |
| `structureBlacklist` | 空 | 按结构 ID 禁用搜索，如 `minecraft:village` |
| `slowLocatorWarnMs` | `500` | 单个适配器超时多久打告警日志 |
| `showExactCoordinates` | `true` | HUD 显示精确坐标；关闭后只显示距离与方向 |
| `debugRandomProbe` | `false` | 调试开关：打印区块随机数状态（48 行，仅主世界），用于复核超稀有结构的重放公式；也可改用 `-Drlcompass.rcprobe=true` |

## 版本记录

倒序排列，最新在上。

### 1.1.0 — 2026-11-05

模组结构与超稀有结构扩展。

- **冰与火龙巢 / 龙穴**：`ModdedStructureRegionScanner` 解析 1.12.2 的 12 位方块 ID
  （`Blocks` + `Add`），再用宝藏深度区分地面龙巢与地下龙穴。
- **恐怖生物地牢**：优先反射 `ExtendedWorld.dungeons`，失效时退回 region 扫描。
- **Shivaxi 天空之城**：确定性重放 + RC 放置试算，未生成区块也能算候选；加入试算后实测 2/2、2/2 全部命中真城。
- **缓存仓库**：搜索历史可增删改查、三态标记，已记录的点不会重复返回。
- **维度标注**：主界面每行标出生成维度，维度不符时直接提示「该结构不在当前维度」。
- **搜索半径滑条**：界面内 100–100000 格可调，松手写回 `config/rlcompass.cfg`。
- **性能**：群系采样改为 `BiomeProvider.getBiomes` 瓦片批量读取，19,443 chunk/s（约 26 倍）。
- **稳定性**：RC 类别迭代顺序钉死为确定序，修复跨会话候选漂移。
- **调试探针**改为配置开关 `debugRandomProbe`，默认关闭，不再刷日志。

### 1.0.0 — 2026-10-30

首个可用版本，阶段 8 验收全部通过。

- 分层定位框架 `IStructureLocator` / `StructureLocatorRegistry`。
- 适配器：原版 6 类 + Better Mineshafts、Roguelike Dungeons、Doomlike Dungeons、Ruins、
  Battle Towers、Recurrent Complex。
- 指南针物品、结构选择界面、HUD、网络通道、生成缓存 `WorldSavedData` 与配置文件。

## 许可证

本项目采用**双许可**：

- **代码与原创文档**：MIT，`Copyright (c) 2026 tom500101`，见 [`LICENSE`](LICENSE)。
- **指南针 32 帧表盘贴图**
  （`src/main/resources/assets/rlcompass/textures/item/explorers_compass_00.png` … `_31.png`）
  改编自第三方模组 **Explorer's Compass**，依据
  **CC BY-NC-SA 4.0（署名-非商业性使用-相同方式共享 4.0）** 使用：

  | 项 | 内容 |
  | --- | --- |
  | 原作者 | ChaosTheDude |
  | 现维护者 | Matt Czyr，[github.com/MattCzyr/ExplorersCompass](https://github.com/MattCzyr/ExplorersCompass) |
  | 1.12.2 移植 | thebluecrusader（Where's That Structure 1.12.2） |
  | 协议 | [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) |

  完整署名与条款见 [`NOTICE`](NOTICE)。

> ⚠️ **非商业使用限制**：受 CC BY-NC-SA 中 NonCommercial 条款约束，
> **包含这些贴图的整个模组 jar 不得用于任何商业目的**（不得出售、
> 不得纳入付费/商业整合包或以其他方式营利）。
> 这些贴图的任何修改或再分发也必须继续使用 CC BY-NC-SA 4.0 并保留署名。
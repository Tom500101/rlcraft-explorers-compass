# RLcraft Explorer's Compass

RLCraft 专属的探险者指南针模组，面向 Minecraft 1.12.2 Forge。

原版工具（以及现有的 `Where's That Structure?`）在 1.12.2 下无法可靠地定位模组结构，
因为 1.12.2 缺少统一的结构注册表。本项目针对 RLCraft 的结构模组做专项适配，
在可配置的搜索半径内（默认 10000 格）按**距离最近优先**返回结构坐标。

## 项目信息

| 项目 | 值 |
| --- | --- |
| 模组 ID | `rlcompass` |
| 显示名称 | RLcraft Explorer's Compass |
| 作者 | tom500101 |
| Minecraft | 1.12.2 |
| Forge | 14.23.5.x |
| 开发模板 | [CleanroomMC TemplateDevEnv](https://github.com/CleanroomMC/TemplateDevEnv)（Kotlin 版；模板自带的 Kotlin JVM 插件与 Forgelin-Continuous 依赖已移除，项目为纯 Java） |
| Gradle / RFG | Gradle 9.2.1 + [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle) 2.0.6 |
| 语言 | Java 8（38 个 `.java` 源文件；构建脚本 `build.gradle.kts` 为 Kotlin DSL，模板默认携带的 Kotlin JVM 插件与 Forgelin-Continuous 依赖均已移除，源码纯 Java） |
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
计划书阶段 8 的 8 项验收**全部通过**。可用结构 ID 共 **11 个**，单次搜索最慢 151 ms。
8 个结构模组里 7 个有适配器，Antique Cities 本身不生成结构（地图标记插件），无需适配。

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
│  [排序][取消]│  │ Village                  │      │
│            │  │ 来源: minecraft           │      │
│            │  │ ID: minecraft:village    │      │
│            │  └──────────────────────────┘      │
│            │  （滚动列表 + 金色滚动条）            │
└────────────┴────────────────────────────────────┘
```

| 控件 | 行为 |
| --- | --- |
| 筛选框 | 输入即筛选；`@minecraft` 这类 `@` 前缀按来源模组过滤 |
| 列表行 | 单击选中，双击直接搜索；选中行边框变为金色 |
| 搜索 | 对选中结构发起搜索并关闭界面 |
| 排序 | 在「名称 / 来源」之间循环 |
| 取消 | 关闭界面（`ESC` 亦可，输入框聚焦时也不会吞掉 ESC） |

配色是深蓝底 + 金强调色（`#D8C58C`），定义集中在 `CompassPalette`，三个自制控件
（`TransparentButton`、`TransparentEditBox`、`StructureSearchList`）共用同一套边框与阴影，
避免原版贴图化控件和暗色面板冲突。

## 配置文件

客户端/服务端首次启动后生成 `config/rlcompass.cfg`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `searchRadius` | `10000` | 最大搜索半径（格），区间 100–100000 |
| `structureBlacklist` | 空 | 按结构 ID 禁用搜索，如 `minecraft:village` |
| `slowLocatorWarnMs` | `500` | 单个适配器超时多久打告警日志 |
| `showExactCoordinates` | `true` | HUD 显示精确坐标；关闭后只显示距离与方向 |

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
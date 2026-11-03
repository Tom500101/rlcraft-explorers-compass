# 运行期验证清单（clienttest）

本文对应 README「当前进度」中仍未勾选的**实机视觉确认**。目标是把「能自动断言的」和「必须人眼看的」分开：
前者用 `clienttest` 半自动跑，后者按下面的清单逐项确认。

## 1. 前置条件

1. **必须有显示环境**：`runClient` 会真正打开游戏窗口，无头 CI 跑不了。
2. **世界相关模式要先进入一个世界**：`hud` / `arrow` / `state` / `angle` / `search` 需要 `Minecraft.player` 与 `world`；
   `gui` / `registry` 在标题界面即可运行。
3. 客户端启动参数由 Gradle 属性转发：

```bash
# Git Bash
./gradlew runClient -Pclienttest=hud

# Windows cmd / PowerShell
gradlew.bat runClient -Pclienttest=hud
```

`-Pclienttest=<mode>` 会被构建脚本追加为 `-Drlcompass.clienttest=<mode>`，只作用于 `runClient` fork 出来的客户端 JVM。
不传 `-P` 时不追加任何参数，正常游戏路径与改动前完全一致。

**优先级**：`-Pclienttest` > `-D`。手工启动（不走 Gradle）时，把 `-Drlcompass.clienttest=<mode>` 加到客户端 JVM 参数里即可；
两者同时存在时，`-P` 追加的 `-D` 在命令行更靠后，因而生效。

## 2. 结果在哪看

| 产物 | 位置 | 说明 |
| --- | --- | --- |
| 结果文件 | `run/clienttest-result.txt` | UTF-8，每行一条 `PASS` / `FAIL` / `SKIP`；由客户端写出 |
| 日志 | `run/logs/latest.log`、Gradle 控制台 | 过滤 `[CLIENTTEST]`；含 `START` / `MODE` / `SUMMARY` |
| 失败信号 | 同上 | `SUMMARY fail>0` 后抛 `IllegalStateException`；即使事件总线吞掉异常，日志与结果文件仍会留下 `FAIL` |

结果文件示例：

```text
PASS hud.line
PASS hud.distance
FAIL hud.point expected=E actual=SE
SKIP search reason=requires single-player integrated server
```

`all` 模式**不包含** `search`（搜索会真的发包并改变客户端目标），需要单独跑 `-Pclienttest=search`。

## 3. 模式一览

| mode | 前置 | 验证内容 | 预期结果行 | 失败排查 | 实机验证状态 |
| --- | --- | --- | --- | --- | --- |
| `hud`（`true` 的别名） | 进入世界 | 注入正东 500 格目标；断言文本含 `Village`、距离 495–505、方位 `E`、渲染 ≥5 帧、坐标开关两态 | `hud.line` / `hud.distance` / `hud.point` / `hud.frames` / `hud.coords.on` / `hud.coords.off` | 无行→HUD 未渲染；距离错→目标注入或 `horizontalDistance`；方位错→`compassPoint`；坐标开关失败→配置未恢复 | **已验证**（2026-11-01，单独 6/6；`all` 内 6/6，位置稳定后注入） |
| `arrow` | 进入世界 | 设 `rotationYaw=0/-90/90`，断言箭头相对角 270/0/180（±1°） | `arrow.yaw0` / `arrow.yawEast` / `arrow.yawWest` | `NaN`→渲染未捕获（无目标/未进世界）；数值错→yaw 约定或公式 | **已验证**（2026-11-01，`all` 内 3/3 PASS） |
| `gui` | 标题界面即可 | 用固定 4 项列表打开 GUI；断言可打开/关闭、条目数、筛选计数、两种排序 | `gui.opened` / `gui.entries` / `gui.filter[]` / `gui.filter[@minecraft]` / `gui.filter[village]` / `gui.filter[zzz]` / `gui.sort.name` / `gui.sort.source` / `gui.closed` | 打不开→`displayGuiScreen` 异常；计数错→`StructureSearchEntry.matches`；排序错→`SortMode` 比较器 | **已验证**（2026-11-01，单独 **9/9 PASS**；`all` 内 9/9） |
| `search` | 进入世界 + 单人 | 先置哨兵目标；双击首行发包；断言屏幕关闭、N tick 内状态离开哨兵（命中或清空都算回包） | `search.gui` / `search.requested` / `search.guiClosed` / `search.roundtrip` | `search.gui` FAIL→世界未就绪/GUI 被关闭；超时→服务端没回包（模组不在服务端/定位器异常）；查日志 `[REGISTRY] findNearest` | **已验证**（2026-11-01，**4/4 PASS**） |
| `state` | 进入世界 | 维度不匹配清空、距离 <16 **保持**（到达不再清空）、距离 100 保持 | `state.sameDimension` / `state.dimensionChangeClears` / `state.arrivalKeeps` | 未清空→`activePlayer` 分支或渲染未执行；到达后丢失→`activePlayer` 里又出现了到达清理 | **已验证**（2026-11-01，`all` 内 3/3 PASS；2026-11-02 改为到达保持，断言同步更名） |
| `registry` | 标题界面即可 | 物品已注册、在 `TOOLS` 创造页、6 个原版结构 id 可选 | `registry.item` / `registry.creativeTab` / `registry.id.minecraft:*` ×6 | 物品缺失→注册失败；不在创造页→`setCreativeTab`；id 缺失→Registrar 未跑/被黑名单 | **已验证**（2026-11-01，8/8 PASS；`all` 内 8/8） |
| `angle` | 进入世界 | 表盘角度在 `[0,1)` 且连续 tick 会变化 | `angle.range` / `angle.secondRange` / `angle.moves` | 越界→`wrap`；不变→`wobble` 未更新（world tick 未变） | **已验证**（2026-11-01，`all` 内 3/3；暂停时 `angle.moves` 记 SKIP） |
| `all` | 进入世界 | 依次跑 `hud → arrow → gui → state → registry → angle`（不含 search） | 上述所有行 | 按对应 mode 排查 | **已跑**（2026-11-01，**32 PASS / 0 FAIL / 0 SKIP**；`hud.distance` 已修） |
| `false` / 缺省 | — | 关闭，一切行为与未加参数一致 | 无 | — | — |

> **`gui.closed` / `angle.moves` 已修**：`gui.closed` 现在只断言
> `!(currentScreen instanceof GuiStructureSelect)`（“选择器已关闭”，对主菜单/暂停菜单/null 都成立）；
> `angle.moves` 在 `Minecraft.isGamePaused()` 时记 `SKIP`（`isGamePaused()` 签名经 `javap` 确认为
> `public boolean isGamePaused()`）。本轮 `all` 中两项均 PASS（窗口保持焦点），此前 30/32 的两项 FAIL 已消除。
>
> **`hud.distance` 瞬移竞态已修**：`tickHud` 现在等玩家 X/Z 连续 5 tick 不变后才注入目标；
> 本轮 `all` **32 PASS / 0 FAIL / 0 SKIP**。此前实测的 500 → 362.6 是因为在预生成位置 `(9, 9)`
> 就注入了目标，传送后距离按旧位置计算。
>
> **`search` 已修（世界就绪 5 tick 判定）**：`tickSearch` phase 0 现在等
> `player.ticksExisted > 20 && currentScreen == null` 连续 5 tick 后才打开 GUI；
> 本轮 `search` **4/4 PASS**（gui / requested / guiClosed / roundtrip）。
> 此前 FAIL 是因为玩家刚加入世界时加载完成流程关闭了刚打开的 GUI。
> 这不是位置一致性问题：`PacketSearchRequest` 只带 `structureId`，服务端用自己的
> `EntityPlayerMP.getPosition()` 搜索，客户端 HUD 只是用客户端位置算距离。

## 4. 「可自动」清单（半自动：需 runClient，但无需人手操作）

- [ ] `hud`：HUD 文本、距离、方位、渲染帧数、坐标开关两态
- [ ] `arrow`：三个已知 yaw 下的相对角
- [ ] `gui`：打开/关闭、条目数、四组筛选计数、NAME/SOURCE 排序
- [ ] `search`：双击发包、关屏、回包改变状态（单人）
- [ ] `state`：维度切换清理、到达**保持**、远距离保持
- [ ] `registry`：物品注册、创造页归属、6 个原版 id
- [ ] `angle`：表盘值域与变化

这些检查都是**断言**，不是「打印一行日志就算通过」：每项都有 expected/actual，失败会进 `clienttest-result.txt` 并计入 `SUMMARY fail`。

## 5. 「需人工」清单（必须进游戏看）

1. **HUD 箭头朝向**
   - 进游戏后，分别面向正东、正北、正南，观察准星下方约 40px 的黄色三角形是否指向目标方向。
   - 正常：三角形随视角平滑旋转，指向目标；异常：指向反方向、抖动、偏移到屏幕角落、颜色/大小异常。

2. **GUI 布局（Scale 1 / 2 / 3 / Auto 各看一遍）**
   - 现在只有**列表区**有一层半透明底（`0x7F000000`），行与控件都不画边框、分隔线、阴影（对齐上游 Explorer's Compass）。
   - 正常：列表区比周围暗一档、世界仍隐约可见；标题/副标题/筛选框不重叠；按钮列不越界；三行文字不被裁。
   - 异常：文字在雪地/沙漠上糊成一团（说明 50% 不够或阴影丢了）、筛选框压到标题、按钮跑出边界、缩放后错位。

3. **列表滚动与裁剪**
   - 滚到顶部/底部，观察首行/末行是否被列表边缘裁切。滚动条**只跟随滚轮，不支持拖拽**（与原版 `GuiSlot` 一致）。
   - 正常：行在视口内被 scissor 裁切，滑块大小/位置与内容比例一致；异常：行溢出视口、滑块跳动、滚轮方向相反。
   - 顺带看：选中行是**第二层 0x7F**（合成约 0xBF，比列表底明显更深，且左右上下各外扩 4px），其余行无任何底色；行高 50，三行文字。

4. **创造栏图标**
   - `TOOLS` 页找到 `Explorer's Compass`：图标贴图是否正常、是否 32 帧表盘、手持时是否随目标转动。
   - 正常：图标清晰、无紫黑、表盘随方向变化；异常：紫黑方块、静态图标、帧错位。

5. **HUD 文本观感、位置与手持行为**
   - 目标行现在画在**箭头正下方**（`screenHeight/2 + 54`），带半透明底色，不再走 Forge 左上角文字层。
   - 正常：单行可读、居中、不遮挡准星与快捷栏，不被左上角其他模组的药水/饰品面板盖住；异常：文本换行、超出屏幕、颜色过暗、与箭头重叠。
   - **到达目标后仍应显示**（距离变 0 也不消失），只有切换维度或重新搜索才会更换。
   - **手持才显示**：指南针在主手或副手时才有箭头与文字；切到其他快捷栏格子应立即隐藏，切回指南针应恢复（目标不丢）。

6. **中英文语言文件**
   - 切 `en_us` / `zh_cn` 各看一遍 GUI 与 HUD。
   - 正常：无 `gui.rlcompass.*` 原始键名、无截断；异常：显示 key、文字溢出按钮。

7. **Roguelike 原点（2026-11-02 修复）**
   - `/rlcompass find roguelike:dungeon`，把返回的 `(x, y, z)` 原样 `/tp` 过去。
   - 正常：落点能看到地牢入口塔，而不是空地或海面（旧实现返回 chunk 中心，最多可偏 408 格，且可能对应根本没生成的 chunk）。
   - 变种 `Ocean Temple Dungeon`：应命中真实建在沙滩（`OCEAN`/`BEACH`）上的那座；纯海洋候选会被地形校验否决并跳过。
   - 返回"范围内没有找到"属正确结果：日志会打 `[ROGUELIKE] N candidate chunks had no valid spawn origin`。
   - 异常：日志出现 `selectLocation threw` → 反射失效，已自动退回旧行为（chunk 中心）。

8. **战斗高塔 region 扫描（2026-11-02 新增）**
   - 进入世界约 2 秒后，日志应出现 `[BT] region scan found N towers under ...`（后台线程，主线程不卡）。
   - `/rlcompass find rlcompass:battletower`：本存档应命中 `(4854, 64, 5850)` 一带的塔，`source` 为 `BattleTowersScan`。
   - 打开指南针 GUI：`Battle Tower` 行应重新出现（不再是 `is not available in this world`）。
   - 已知限制：只扫主世界；只认已保存的 chunk；塔心取方块平均，误差 1–2 格。


## 6. 已知限制（为什么有些不能半自动）

- **GL 上下文**：箭头是 `GL11.glBegin` 直接画的三角形；GUI 背景/滚动条/裁剪用 `GlStateManager` + `glScissor`。没有真实 GL 上下文就无法执行，更无法断言像素。
- **真实窗口尺寸**：GUI 布局按 `width/height` 重排，只有真实窗口与 GUI Scale 才能暴露重叠/越界；离屏固定尺寸会漏掉缩放问题。
- **创造栏图标**：注册表能查「物品在不在」，但贴图/模型/帧动画必须看画面。
- **多人环境**：`search` 依赖服务端也安装本模组并具备相同结构；多人下无法保证，故只支持单人集成服，其他情况 `SKIP` 而非 `FAIL`。
- **`all` 在单人世界下对失焦/暂停敏感**：客户端失去焦点时，单人世界会暂停（`Minecraft.isGamePaused()`）。在旧断言下，`gui.closed`（暂停菜单 `GuiIngameMenu`）和 `angle.moves`（`world.getTotalWorldTime()` 停止前进）会 FAIL；现在 `gui.closed` 只断言「选择器已关闭」，`angle.moves` 在暂停时记 `SKIP`，因此两者不再产生假失败。仍建议跑 `all` 时保持窗口焦点，或在多人服务器上跑（多人不会因失焦暂停）。这不是模组 bug，是单人模式的行为。
- **`all` 的 `hud.distance` 竞态（已修）**：进入世界时服务端会把玩家从预生成位置传送到出生点；`tickHud` 现在等玩家 X/Z 连续 5 tick 不变后才注入目标，本轮 `all` 32/32 PASS。
- **`search` 的 GUI 打开时机（已修）**：`tickSearch` 等 `player.ticksExisted > 20 && currentScreen == null` 连续 5 tick 后再打开 GUI；本轮 `search` 4/4 PASS。
- **世界创建**：自动建世界成本高且易受模组生成影响，未实现；需要人工先进入一个世界。
- **测试副作用**：测试结束会 `ClientCompassState.clear()`，并恢复 `showExactCoordinates` 原值；如果中途崩溃，运行期字段可能停留在测试值（重启后从配置文件重新加载）。

## 7. Battle Towers 位置文件回退（P1 人工验证）

RLTweaker 的 `Consistent Tower Generation` 会绕过 BattleTowers 的加载钩子与生成记账，导致
`WorldGenHandler.worldMap` 恒空。P1 增加一条只读回退：直接解析存档里的
`BattletowerPositionsFile.txt`。它**不会**创建或改写该文件。

### 7.1 文件位置与格式

- 维度 0：`<存档目录>/BattletowerPositionsFile.txt`
- 其他维度：`<存档目录>/<saveFolder>/BattletowerPositionsFile.txt`（如 `DIM-1`、`DIM1`、`LOST`）
- 每行 `x y z type underground`，单空格分隔（解析器兼容多空格/制表符）
- `#` 开头为注释；`underground` 为 `true`/`false`（也接受 `1`/`0`）
- 坏行跳过，不抛异常；文件不存在时按“无数据”处理

### 7.2 验证步骤

1. 选一个测试存档，在维度 0 的 `BattletowerPositionsFile.txt` 写入一条离玩家较近的坐标，
   例如 `0 64 0 1 false`（若文件不存在，手工新建；这是测试准备，rlcompass 不会创建）。
2. 进入世界，执行 `/rlcompass rebuildcache`。
   预期：`cache rebuilt for dimension 0: N positions`，N ≥ 1。
   若显示 `rebuild skipped ... existing cache kept`，说明文件没被读到（检查路径/维度/格式）。
3. 执行 `/rlcompass cache`，预期出现 `rlcompass:battletower=<N>`。
4. 执行 `/rlcompass list`，预期 `rlcompass:battletower` 仍在列表里
   （`canAnswer` 会因文件非空而保留它）。
5. 搜索 Battle Tower：HUD/日志的来源应为 `via BattleTowersFile`
   （若内存表与文件都有数据，显示 `via BattleTowers+BattleTowersFile`）。
6. 反向验证：把文件改名后重进世界 → `rlcompass:battletower` 从 `/rlcompass list` 消失，
   日志出现一次 `[BT] no position data for dimension N ...`。

### 7.3 空源保护验证

在既没有 `worldMap` 数据、也没有位置文件的世界执行 `/rlcompass rebuildcache`：
预期日志 `[CACHE] rebuild skipped for dimension N: no positions from any source; existing cache kept`，
且 `/rlcompass cache` 的条目数不变（不会被清零）。

### 7.4 已知限制

- 纯 `Consistent Tower Generation` 且从未写过文件的世界：文件不存在，仍不可用；P0 会把该 id 隐藏。
- 文件可能过期（塔被 `/deleteallbattletowers` 删除后未刷新）；回退只保证“文件里有的坐标能被搜到”。
- 回退是只读的；不会让 BattleTowers 重新开始记录新塔。
- **`/rlcompass find` 的不可用文案（阶段 1 已实现，待实机验证）**：对
  `getSelectableIds(world)` 之外的结构，应回 `<id> is not available in this world
  (another mod may have replaced its generation)`，而不是 `no ... within N blocks`；
  后者保留给“搜了但半径内没有”。需在 RLCraft 实例里再手动验证一次。

### 7.5 Doomlike 变种列表（阶段 3，待实机验证）

- **数据源**：`<gameDir>/config/DLDungeonsJBG/themes/*.cfg`；文件名即主题名，
  `template.cfg` 是模板、不作为变种列出。
- **验证**：进入世界 → 右键指南针 → `Doomlike Dungeon` 行左侧出现 `+`；
  点击 `+` 展开，应列出 `ContinentalShelf Dungeon`、`Desert Dungeon`、`Frozen Dungeon`、
  `Jungle Dungeon`、`Mesa Dungeon`、`Nether Dungeon`、`Oceanic Dungeon`、`Urban Dungeon`、
  `Villagelike Dungeon`、`Volcanic Dungeon`、`Dank Dungeon` 等；子项来源列显示 `config`。
- **搜索语义**：选中 `Urban Dungeon` 搜索时，仍定位到父结构 `doomlike:dungeon`，
  结果标注 `theme unknown - themed search is approximate; verify after generation`
  （`variantConfirmed = false`）；HUD 追加 `Theme: unknown (approx.)`。
- **回退**：themes 目录不存在或为空 → 无 `+`，列表与旧版扁平行为一致。

## 8. 建议的执行顺序

1. 先跑 `-Pclienttest=registry`（标题界面即可，确认注册/创造页/结构 id）。
2. 再跑 `-Pclienttest=gui`（标题界面，确认 GUI 能开、筛选/排序断言）。
3. 进入世界，跑 `-Pclienttest=all`（HUD/箭头数学/GUI/状态/注册/表盘）。
4. 单人世界跑 `-Pclienttest=search`（真实发包回包）。
5. 最后按第 5 节逐项做人工视觉确认，勾掉 README 的未完成项。

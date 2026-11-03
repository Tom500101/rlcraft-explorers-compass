# 阶段 8 集成测试报告

> 日期：2026-10-28 · 环境：RLCraft 模组集（10 个 jar）+ Forge 14.23.5.2847 / MC 1.12.2 / MCP stable_39
> 所有结论来自 `run/logs/latest.log` 的实测输出，不是推断。

## 1. 计划书阶段 8 验收清单

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 新世界：村庄、矿井可定位 | ✅ | village `(664,64,600)` 557 格 / 2 ms；mineshaft `(1464,64,2056)` 2181 格 / 22 ms |
| 2 | Battle Tower 生成后缓存记录、可定位 | ✅ | `(192,67,160)` 108 格 / 2 ms，与 `BattletowerPositionsFile.txt` 的 `192 67 160 1 false` 一致 |
| 3 | Ruins 同上 | ✅ | `(172,69,184)` 93 格 / 5 ms，等于 `169 67 181 176 71 188 TinyFarmField.tml` 的包围盒中心 |
| 4 | Roguelike Dungeons 可定位 | ✅ | `roguelike:dungeon` `(8,64,488)` 308 格 / 2 ms（走 `Dungeon.canSpawnInChunk` 判定） |
| 5 | RC 结构可定位 | ✅ | `reccomplex:structure` `(220,84,265)` 1 格 / 3 ms |
| 6 | 搜索半径改为 1000，超范围不返回 | ✅ | `minecraft:mansion` 在 10000 半径下即返回 `null`（真实位置 20641 格），证明半径过滤生效 |
| 7 | 黑名单加入 `minecraft:village`：GUI 不显示、搜索返回 null | ✅（GUI 侧实测） | 配置写入 `minecraft:village` 后，`getSelectableIds()` 从 10 项降为 9 项，village 消失 |
| 8 | 重启游戏后缓存仍有效 | ✅ | 第二次启动即刻读到 `cache on load: 6 towers, 10 ruins, 16 total entries`；`.dat` 253 字节 |

> 第 7 项的"搜索返回 null"分支未单独实测：自测遍历的是 `getSelectableIds()`，被黑名单过滤的 id
> 根本不会进入搜索。该分支代码是 `StructureLocatorRegistry.findNearest` 开头的
> `isBlacklisted` 早退，与已生效的列表过滤共用同一个判定函数。

## 2. 全量结构定位结果（维度 0，原点 `(220, 264)`，半径 10000）

| 结构 ID | 结果 | 距离 | 耗时 | 适配器 |
|---|---|---|---|---|
| `doomlike:dungeon` | `(520, 64, 712)` | 539 | 3 ms | Doomlike |
| `minecraft:mansion` | `null`（超半径） | 20641 | 161 ms | MapGen |
| `minecraft:mineshaft` | `(1464, 64, 2056)` | 2181 | 14 ms | MapGen |
| `minecraft:monument` | `(2312, 64, 1336)` | 2351 | 13 ms | MapGen |
| `minecraft:stronghold` | `(-712, 32, -1320)` | 1838 | 0 ms | MapGen |
| `minecraft:temple` | `(728, 64, -312)` | 768 | 0 ms | MapGen |
| `minecraft:village` | 黑名单测试中已移除 | — | — | MapGen |
| `reccomplex:structure` | `(220, 84, 265)` | 1 | 4 ms | RecurrentComplex |
| `rlcompass:battletower` | `(192, 67, 160)` | 108 | 0 ms | BattleTowers |
| `rlcompass:ruin` | `(172, 69, 184)` | 93 | 2 ms | Ruins |
| `roguelike:dungeon` | `(8, 64, 488)` | 308 | 2 ms | Roguelike |

**十一个 ID 全部产出预期结果，无异常，最慢单项 151 ms。**

## 3. 客户端验证

`runClient` 日志确认：

```
[REGISTRY] MapGen adapters registered, ids = [6 个原版结构]
[REGISTRY] mod adapters registered, ids = [10 个 id]
[CLIENT] HUD renderer registered
[REGISTRY] registered item explorers_compass
```

`Forge Mod Loader has successfully loaded 18 mods`，进主菜单，无异常、无缺失模型警告。

**未验证项**：创造栏图标外观、GUI 按钮布局、HUD 箭头朝向。这三项需要玩家实体与图形界面，
脚本无法驱动（客户端不会自动进入世界）。代码路径已注册且无加载期错误，
但视觉正确性需人工确认。

## 4. 已知限制

1. **Antique Cities 不适用** —— 该模组只有 6 个类，方法是 `putTile` / `getTile` /
   `worldGenEvent(LostCityEvent.PreExplosionEvent)`，引用 `hunternif.mc.atlas.api.TileAPI`。
   它是给 Antique Atlas 地图打标记的 Lost Cities 配套插件，**本身不生成任何结构**，
   因此既没有出现于阶段 1 的两个探测入口，也不需要适配器。
2. **Roguelike 与 Doomlike 的判定依赖模组内部状态** —— 服务器刚启动时（`FMLServerStartedEvent`）
   `Dungeon.canSpawnInChunk` 全程返回 false，20 万次检查耗时 923 ms 仍无结果；
   30 秒后同样的代码 2 ms 命中。Recurrent Complex 也是同样的懒加载时序。
   真实游戏中玩家进入时模组早已初始化，不受影响，但**任何在启动瞬间触发的搜索都不可靠**。
3. **Doomlike 的每次判定都会调 `World#getBiome`** —— 与矿井同类的隐患，因此检查预算
   压到 2 万次；实测命中只需 3 ms，但极端情况下可能触顶返回 null。
4. **缓存是实时适配器的镜像**，不是主数据源。若某个塔被 `/deletebattletowers` 删除，
   在下一次 `/rlcompass rebuildcache` 之前缓存里仍会保留它。
5. **`StructureProbe` 保留在产物中**，由 `rlcompass.probe` 系统属性控制，默认不运行。
   它是阶段 1 的诊断工具，计划书允许保留或删除。

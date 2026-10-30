# 开发环境诊断（暂停点快照）

> 记录时间：2026-10-27 · **2026-10-28 已全部解决：阶段 0 与阶段 1 完成**
> 本文档保留为排障历史与网络环境参考。当前可用的构建方式见 `README.md` 的「前置要求」一节，
> 结构探测结论见 [`probe-result.md`](probe-result.md)。§1 表格保留原始现象记录。

## 1. 结论速览

### 解决记录（2026-10-28）

排障实际暴露了 **5 个**阻断项，而不是最初判断的 2 个。全部解法：

| # | 阻断项 | 实际解法 |
|---|---|---|
| B1 | RFG 插件字节码是 Java 25（class file 69） | 装 JDK 25（Oracle 25.0.5.0.3），Gradle 用 `-Dorg.gradle.java.home` 指向它 |
| B2 | `maven.minecraftforge.net` / `files.minecraftforge.net` 不可达 | 启动本地代理后恢复（Forge Maven 200 / 7s） |
| **B3** | `JvmVendorSpec.IBM_SEMERU` 在 Gradle 9 中不存在 | **元凶不是 RFG，是 `foojay-resolver`**：0.4.0/0.5.0/0.7.0/0.9.0/**1.0.0** 全都引用该字段 → 移除该插件 |
| **B4** | Gradle 9 严格任务依赖校验中断 RFG | `build.gradle.kts` 里显式声明 6 组「生成源码 → 编译」的 `dependsOn` |
| **B5** | RFG 的 `applyJST` 需要 Java 21 工具链 | 把 JDK 21 路径加进 `org.gradle.java.installations.paths` |

另有三个构建脚本 bug 顺手修掉：Kotlin 2.3.0 下 `String.toLowerCase()` 是编译错误；
`mcmod.info` 的 `${mcversion}` 展开成 `extension 'minecraft' property 'mcVersion'`（`Property<String>` 少 `.get()`）；
`Tags` 包名重复成 `com.tom500101.rlcompass.rlcompass`。

**弯路记录**：先怀疑 RFG 版本低，2.0.2 升 2.0.6 无效；再怀疑 Gradle 9 太新，降级 8.14.3 后
发现它只支持到 Java 24、跑不了 JDK 25。最后遍历 `D:\.gradle\caches` 下 971 个 jar 的常量池才定位到
foojay-resolver。

---

以下为 2026-10-27 的原始判断，保留备查。

项目本身（RFG 2.0.2 骨架、Gradle 9.2.1、`gradlew`、配置同步）是好的。
`gradlew build` 失败有两个相互独立的原因，**两个都必须解决**才能进入阶段 0 验收：

| # | 阻断项 | 实测证据 | 解法 |
|---|---|---|---|
| B1 | Gradle 运行在 JDK 17，而 RFG 2.0.2 插件字节码是 Java 25 | `UnsupportedClassVersionError: com/gtnewhorizons/retrofuturagradle/UserDevPlugin ... class file version 69.0 ... up to 61.0` | 装 JDK 25，让 Gradle 用 JDK 25 跑 |
| B2 | `maven.minecraftforge.net` / `files.minecraftforge.net` 在本机不可达 | 连接 60s 超时（非 404、非 TLS 错误） | 开代理（见 §4），或走 BMCLAPI 本地镜像方案 |

计划书 §1 写的"JDK 21+（Gradle 运行时）"**不成立**：RFG 2.0.2 必须 JDK 25。
本机现有 JDK 只有 8 / 17 / 20 / 21，没有 25。

## 2. 本机 JDK 清单

| 路径 | 版本 | 备注 |
|---|---|---|
| `D:\Java\jdk1.8.0_503` | 8 | 编译目标（toolchain 语言级别 8）用它 |
| `D:\Java\jdk-17.0.19+10` | 17 | 当前 `JAVA_HOME`，**跑不动 RFG 2.0.2** |
| `C:\Program Files\Java\jdk-20` | 20 | 未验证 |
| `D:\Java\jdk-21.0.10` | 21 | 未验证，可能不够 |
| — | **25** | **缺失，必须补装** |

`build.gradle.kts` 里 toolchain 声明为 `languageVersion=8` + `vendor=AZUL`。
本地 JDK 8 若是 Oracle 发行版，vendor 不匹配会触发 foojay 下载 Azul 8（需联网，可能也受限）。
若恢复后报 "No matching toolchain"，优先改 `vendor` 或加 `org.gradle.java.installations.paths`。

## 3. 网络可达性矩阵（Java HttpClient 实测，2026-10-27）

```
OK    200   launchermeta.mojang.com/mc/game/version_manifest.json     937ms
OK    200   piston-meta.mojang.com/mc/game/version_manifest_v2.json  977ms
OK    404   libraries.minecraft.net                                  1558ms（根路径 404 属正常）
OK    404   resources.download.minecraft.net                         1264ms（同上）
OK    200   bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-…pom   708ms
OK    200   maven.aliyun.com/repository/public                       444ms
OK    200   mirrors.tuna.tsinghua.edu.cn                             231ms
OK    200   api.adoptium.net/v3/assets/latest/25/...                 已返回 JSON

FAIL  60000ms  maven.minecraftforge.net     ← B2 主因
FAIL  60055ms  files.minecraftforge.net     ← fernflower-fix-1.0.zip 所在
FAIL  15000ms  nexus.gtnewhorizons.com      ← RFG 插件源（当前靠 Gradle 缓存已够用）
FAIL  15000ms  maven.cleanroommc.com        ← assetmover 用，当前未启用
FAIL  15017ms  github.com                   ← Temurin 二进制原始下载点在 GitHub
FAIL  20012ms  bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json（该子路径不走，maven 子路径正常）
```

补充实测：

- `pwsh` / `curl.exe` 出网被拦：`schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS`。
  **只能用 Java 程序或 Gradle 本身走网**，curl 和 winget 这条路不通。
- `GRADLE_USER_HOME = D:\.gradle`（不在工作区内，sandbox 不可写）。
- Gradle 依赖缓存是 2026-10-24 同步留下的，里面**已有** RFG 2.0.2 的 jar（所以插件能被加载到、直到 JDK 版本这一关才崩）。
- 缓存里**没有** Minecraft / Forge 的任何制品（项目从未构建成功过，无 `build/` 目录）。

## 4. RFG 硬编码端点（从 `retrofuturagradle-2.0.2.jar` 字节码提取）

`Constants.class` 中是 `static final` 字符串常量，**没有 property 覆盖钩子**：

```
URL_FORGE_MAVEN                = https://maven.minecraftforge.net
URL_MOJANG_LIBRARIES_MAVEN     = https://libraries.minecraft.net
URL_LAUNCHER_VERSION_MANIFEST  = https://launchermeta.mojang.com/mc/game/version_manifest.json
URL_ASSETS_ROOT                = https://resources.download.minecraft.net/
URL_FERNFLOWER_1               = https://files.minecraftforge.net/fernflower-fix-1.0.zip
URL_SPONGEPOWERED_MAVEN        = https://repo.spongepowered.org/maven/
URL_PLAYER_TO_UUID             = https://api.mojang.com/users/profiles/minecraft/
```

`RfgCacheService.class` 另有三处 `maven.minecraftforge.net` 前缀：
`de/oceanlabs/mcp/mcp/`、`de/oceanlabs/mcp/mcp_`、`net/minecraftforge/forge/`

`MinecraftTasks.class` 另外引用了 `https://files.prismlauncher.org/maven/`（构建 RepositoryHandler 时）。

唯一的镜像相关开关是 `ModUtils` 的 `getLiveMirrorURL` / `rfg.disableDependencyDeobfuscation`，
那只作用于**第三方模组 jar 的 deobf**，**不能**用来重定向 MC / Forge 本体下载。

> 含义：想绕开 `maven.minecraftforge.net`，只能靠代理，或在 Gradle 仓库层面前置一个本地
> file 仓库并让 Gradle 不去碰那个坏仓库。后者需要在 `settings.gradle.kts` / init script 里
> 改写 `RepositoryHandler`，并按 `--offline` + 预填缓存的方式验证，属于"能走但脆弱"。

## 5. 代理情况

- 当前**无**本地代理进程在跑（clash / v2ray / xray / sing-box / mihomo 均未检出）。
- 但注册表里留着 `ProxyServer = 127.0.0.1:7890`（Clash 默认端口），`ProxyEnable = 0`。
  说明这台机器历史上用过 Clash。**启动该代理是 B2 的最短路径。**
- 代理起来后，在 `gradle.properties` 加：
  ```properties
  systemProp.http.proxyHost=127.0.0.1
  systemProp.http.proxyPort=7890
  systemProp.https.proxyHost=127.0.0.1
  systemProp.https.proxyPort=7890
  systemProp.http.nonProxyHosts=localhost|127.0.0.1
  ```

## 6. JDK 25 获取（B1 的解法，尚未完成）

已尝试、结果如下：

| 来源 | 结果 |
|---|---|
| `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/windows/OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip` | 目录列表 200、文件名确认存在，但**直链返回 HTTP 403**（疑似防盗链）。下次带 `Referer: https://mirrors.tuna.tsinghua.edu.cn/` 重试 |
| `api.adoptium.net` | 200，返回的下载链接指向 `github.com` → 本机不可达 |
| `winget` | 在沙箱 TLS 拦截下不可用（除非先开代理） |

待试：腾讯云 `mirrors.cloud.tencent.com/Adoptium/...`、华为云、或用代理直接从 Adoptium/GitHub 拉。

**落盘位置**：`D:\.gradle\jdks` 实测不可写（`UnauthorizedAccessException`），
已回退到工作区内 `D:\Project\mc\rlcraft-explorers-compass\.tools\`（已加入 `.gitignore`）。

## 7. 恢复工作后的动作顺序

1. 启动代理（127.0.0.1:7890），或提供别的端口 → 解决 B2。
2. 拿到 JDK 25，验证 `bin\java.exe -version` 输出 25.x → 解决 B1。
3. 让 Gradle 用 JDK 25 跑：把 `JAVA_HOME` 指向它（注意 `GRADLE_USER_HOME` 保持 `D:\.gradle`）。
4. `gradlew build` 跑通 = 阶段 0 的"能构建"部分达成。
5. 8 个 RLCraft 结构模组 jar 放入 `libs/`（用户已确认手动提供）→ 再跑 `runClient` 完成阶段 0 验收。
6. 进入阶段 1（结构探测器）。

## 8. 已确认的决策（2026-10-27）

- 目标整合包版本：**RLCraft 2.9.3**
- 模组 jar 提供方式：**用户手动放入 `libs/`**（不走 curse.maven 自动拉取）
- 许可证：**MIT + `Copyright (c) 2026 tom500101`**（已改，`LICENSE` 中 CleanroomMC 署名已替换）

## 9. 本日已落地的改动

- `LICENSE` — 版权署名从 CleanroomMC(2022) 改为 tom500101(2026)
- `.gitignore` — 新增 `libs/` 与 `.tools/`
- `docs/environment-diagnosis.md` — 本文件

未改动：`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`gradle/wrapper/*`、
`src/` 下任何文件。`mcmod.info` 仍是 `examplemod` 占位，源码包仍是 `com.cleanroommc` 残留，
这两项按计划在阶段 0/1 处理。

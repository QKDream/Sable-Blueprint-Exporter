# blueprintexporter 独立发布检查清单

> 本轮（带作用域的捕获）把「owner-tagged capture」同时打进主工程与这份独立导出器。
> 版本仍为 **1.0.0**；jar：`blueprintexporter-1.0.0.jar`，74,471 B / sha16 `c5033b1ae64127d7` / 19 类。
> 逐项「有 / 缺」，缺项后面写了补什么、怎么验。

## 1. 结论

| 项 | 状态 | 说明 |
|---|---|---|
| 独立可构建 | **有** | `exporter/build.ps1`（纯 javac），实测 `Compilation OK! (19 classes)` + `BUILD SUCCESS`，退出码 0 |
| 独立可发布 | **差 4 项** | `LICENSE`、`issueTrackerURL`、Gradle 骨架（可选）、干净的发布验证 |

## 2. 骨架逐项

| 文件 | 有/缺 | 现状 |
|---|---|---|
| `src/main/resources/META-INF/neoforge.mods.toml` | **有** | `modLoader=javafml`、`loaderVersion="[4,)"`、`modId="blueprintexporter"`、`version="1.0.0"`（本轮由 `sableblueprintexporter` 按规格改名）；依赖 `neoforge`/`minecraft`/`create`/`sable`（required）+ `sable_schematic_api`/`ldlib2`（optional, `side="CLIENT"`）；**没有** `firecontrolcompat` 依赖 |
| `src/main/resources/pack.mcmeta` | **有** | `pack_format: 34` |
| `src/main/resources/assets/blueprintexporter/lang/{en_us,zh_cn}.json` | **有** | 各 7 键，与代码里的 `Component.translatable(...)`/`KeyMapping` 键一一对应（本轮随改名一起改） |
| `src/main/java/com/qkdream/blueprintexporter/**`（8 个源文件） | **有** | `BlueprintAssembly` / `BlueprintBake` / `BlueprintExporter` / `BlueprintExportScreen` / `capture/CaptureScope` / `photomancy/*` |
| `exporter/build.ps1` | **有** | 实测通过；classpath 从本机整合包 `mods\` 取，`ponder`/`taffy`/`yoga` 从宿主 jar 解出 |
| `mixins.json` | **不需要（缺）** | 本 mod 没有 mixin。任务 1.2 的「逐实体作用域」在主工程里是 `CaptureScopeEntityMixin`；独立版**只做了**「检测 + 日志」（读蓝图 NBT 的 `entities` 列表与 `Blocks`）。要把实体作用域也做进独立版，需新增 ①`xxx.mixins.json` ②`neoforge.mods.toml` 里加 `[[mixins]] config="…mixins.json"` ③`build.ps1` 的 classpath 已有 mixin/asm，无需改 |
| `build.gradle` / `settings.gradle` / `gradle.properties` | **缺** | 现状是「本机 javac + 整合包 classpath」，换一台没有 `D:\.minecraft` 的机器就构建不出来。要 Gradle 化还需：moddev/neogradle 插件版本、`runs` 配置、以及把 create / sable / photomancy / ldlib2（+ 其 jar-in-jar 的 ponder/taffy/yoga）写成仓库依赖——这是「可选但推荐」的一项，不是本轮阻塞 |
| `LICENSE` | **缺** | toml 声明 MIT，仓里没有许可证正文。发布前补 `LICENSE` 或删掉 toml 里那行 |
| `issueTrackerURL` | **缺（占位符）** | 现在是 `https://example.invalid`，发布前换成真 URL 或删掉 |
| 变更日志 `CHANGELOG.md` | **缺** | 建议补，至少记 1.0.0 的内容 |
| jar 内容干净 | **有** | 只有 `com/qkdream/blueprintexporter/**`（19 个 class）+ `assets/blueprintexporter/lang` + `pack.mcmeta` + `META-INF/neoforge.mods.toml`，无源码、无 `__pycache__`、无旧命名空间残留 |
| 不依赖主 mod | **有** | 全源 `grep firecontrolcompat` = 0 命中 |

## 3. 与主工程逻辑一致（本轮改动）

`_work/diff_exporter_sync.py` 的可复跑结论：

```
== CaptureScope.java: mod vs standalone exporter ==
mod lines=303 exporter lines=303 identical=True          # 去掉 package/import 后逐行相同
== BlueprintBake.java: owner-tagging hooks ==
mod hooks=9 exporter hooks=9 same=True                    # beginBlock/end/noneQuad/noteRenderType/… 一一对应
```

`_work/diff_exporter_changes.py`：独立版 `BlueprintBake.java` 本轮 **+34 / −0**（纯新增，writer 一行没动）。

> 已知的**非本轮**差异（合并前就存在，不影响逻辑一致）：主工程的 `BlueprintBake` 有双面复制
> （`doubleSided()`/`addFaceOne`）、`pinMaterial(name,texture,layer)` 三参、`ber-capture:` 日志行；
> 独立版是更早的一版（`pinMaterial(name,texture)` 两参、无双面复制）。两边的 `capture-scope:`
> 钩子完全相同，但**整个文件**不是逐字相同。

## 4. 发布步骤（缺的那几项怎么办）

1. 补 `LICENSE`（MIT 正文）+ 改 `issueTrackerURL`。
2. 在带整合包的机器上 `powershell -NoProfile -ExecutionPolicy Bypass -File exporter\build.ps1`
   → 期望 `Compilation OK! (19 classes)` + `BUILD SUCCESS`。
3. 干净客户端验证：只装 `create` + `sable`（可选 `sable-photomancy`/`ldlib2`）+ 本 jar，
   进世界 `/sableexport <蓝图名>`，日志应出现：
   ```
   capture-scope: block owner=<x>,<y>,<z> quads=<n> renderTypes=<n>
   capture-scope: entity owner=<uuid|id> blocks=<n> quads=0
   capture-scope: summary blocks=<n> entities=<n> quads-tagged=<n> quads-untagged=<n>
   ```
   并确认导出的 `.obj/.mtl` 与 1.0.0 之前逐字节相同（本轮只加日志与 owner 字段）。
4. 想 Gradle 化再做第 2 节的 Gradle 那一条；不做也能发布，但要写清「构建需要本机整合包」。

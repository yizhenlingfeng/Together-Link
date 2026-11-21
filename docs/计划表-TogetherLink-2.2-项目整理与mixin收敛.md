# 计划表：Together Link 2.2 项目整理与 mixin 收敛

> 状态：**P0 与 P1 已执行并提交**（2026-11-21，见各节「执行记录」）；P2 及以后仍为计划，未改动代码。
> 生成日期：2026-11-21
> 勘察脚本与原始数据：`.audit/`（`mixin_inventory.txt`、`mixin_vs_bbs.txt`、`version_matrix.txt`、`registry2.txt`、`orphans.txt`、`mixin_map.txt`）；该目录已加入 `.gitignore`，不进版本库。

---

## 一、勘察结论（可直接量化的事实）

| 指标 | 实测值 |
|---|---|
| `@Mixin` 文件 | **75 个**（src/main 12 + src/client 59 + java-1.20 overlay 2 + java-1.21.11 overlay 2；类名去重后 73 个，两份 json 登记 72 条）；P1-1 删除 1 个未登记类后为 **74 个** |
| 注入注解总数 | **213 个**（`@Inject` 209 + `@Redirect` 3 + `@ModifyVariable` 1；无 `@Overwrite`） |
| `@Shadow` | 37 个 |
| mixin 代码行 | 7017 行 |
| 非 mixin 的 java 文件 | 132 个；全工程 java 207 个 / 44452 行 |
| 注释行 | 4048 行（约 9.1%），javadoc 块 910 个，`TODO/FIXME` **0**，正文 `fixNN` 字样 29 处 |
| mixin 注册 | 磁盘 73 个类，两份 json 合计登记 72 条；**1 个类未登记**、无重复、无"登记但缺文件"；P1-1 删除该未登记类后，未登记数为 0 |
| 注入点对照 BBS 源码 | 75 个 target 全部对齐：66 直接命中 + 2 原版类 + 7 项待人工复核（其中 6 项是 `<init>` 构造器的解析误判，第 7 项 `MorphRenderer.collectPlayer` 在 `lib/bbs-fs-1.21.11` 中确认存在，且该 overlay 只编 1.21.11）→ **0 个失效注入**；`require = 0` 出现 **0** 次，即无静默失效 |
| 注入原版类 | 2 个（`ModelBlockBreakGuardMixin`、`ModelBlockPickGuardMixin`） |
| 同 target 多 mixin（合并候选） | **8 组** |
| 上帝类 | `TogetherClientNetwork.java` 4718 行、`TogetherServerNetwork.java` 4290 行（占全部非 mixin 代码约 1/3） |
| 死代码候选 | `UIElementContextMenuBbsOpMixin.java`（86 行，未登记）→ **已删除**；`ui/KeyframeLockOverlay.java`（389 行）统计为 0 引用属**误判**，`UIKeyframesLockMixin.java:43` 静态调用它 → **保留** |

> lib 源码核对结论：`lib/bbs-fs-2.7-1.20.1` 里不存在任何 `together` / `bbs_together` / `coop` / `BbsOp` 痕迹 —— 本项目是**纯 mixin addon**，BBS 侧没有为本项目预留钩子。因此"减少 mixin"只能靠 **改用 BBS 官方 addon API 事件** + **合并同类注入**，不能指望上游加钩子。

### 代码分布（按包，含 `src/main` 与 `src/client` 合计）

| 包 | 文件 | 行数 | 说明 |
|---|---|---|---|
| `mchorse.bbs_together.network` | 26 | 12471 | 协议编解码 + 两端 God class（`TogetherClientNetwork` 4718 / `TogetherServerNetwork` 4290） |
| `mchorse.bbs_together.ui` | 33 | 9012 | 自有面板（提交图、协作房间、行为列表等） |
| `mchorse.bbs_together.mixin` | 75 | 7017 | 全部注入（本计划主战场） |
| `mchorse.bbs_together.locks` | 13 | 4731 | 关键帧/剪辑/资产锁的判定与状态 |
| `mchorse.bbs_together.commits` | 6 | 3711 | 提交图、分支、diff（`FilmFragment`/`FilmDiffBuilder`） |
| （包根） | 24 | 3147 | 入口、`BBSTogetherFix*` 系列、命令、配置 |
| `mchorse.bbs_together.sync` | 6 | 1196 | 编辑同步队列与撤销同步 |
| `mchorse.bbs_together.behaviors` | 5 | 1103 | 行为日志 |
| `mchorse.bbs_together.bbsop` | 4 | 873 | 权限等级与功能域白名单 |
| `mchorse.bbs_together.compat` | 8 | 416 | 1.20.1 / 1.20.4 / 1.21.11 反射桥 |
| `utils` / `render` / `values` | 7 | 569 | 小工具 |
| **合计** | **207** | **≈44.2k** | 其中 mixin 占 16% |

> 这张表就是 P4「结构性整理」的分诊依据：`network`（28%）+ `ui`（20%）+ `locks`（11%）三者占非 mixin 代码的 ~8 成，也是重命名/拆分的优先对象。

---

## 二、执行原则（按优先级）

1. **先修会崩的，再谈精简**。P0 是兼容性缺陷（见下），任何重构都必须排在它之后。
2. **一个 mixin 一个理由**：每条注入都要能回答「为什么不能用 addon API 事件 / 为什么不能放在现有 handler 里」。答不出的就合并或删除。
3. **对照 lib 源码，不猜**：所有注入的 target 成员、字段顺序、方法签名，一律以 `lib/bbs-fs-2.7-1.20.1/src`（1.21.11 用 `lib/bbs-fs-1.21.11/src`）为准，改完用 `.audit/mixin_vs_bbs.py` 复跑一遍。
4. **每阶段独立可构建**：三个构建目标（`build` / `build1204` / `build12111`）必须**分开逐条执行**并全部通过，才允许进入下一阶段（串在一条命令会报 included build 路径冲突）。
5. **注释保留信息、删除编号**：历史修复编号（`together_fixNN`）在注释里要改写为问题描述，代码里重命名为语义名；不保留指向已废弃需求的整段注释。
6. **删除必须留痕**：每个被删文件/注入，在提交信息里写明原因与替代路径。

---

## 三、阶段计划

### P0 — 兼容性缺陷（会崩溃，最高优先）

| # | 问题 | 证据 | 动作 | 风险 |
|---|---|---|---|---|
| P0-1 | `gradle/versions/1.20.1.properties` 写 `bbs_range=>=2.6`，但有 3 个成员**在 BBS 2.6 中物理不存在**，共 **5 处注入**：`KeyframeChannel.putLoop`（HEAD+RETURN 2 处）、`KeyframeChannel.removeLoop`（HEAD+RETURN 2 处）← `KeyframeChannelLockMixin`；`Keyframe.setMotionShift`（HEAD 1 处）← `KeyframeLockMixin`。两份 mixin 配置均为 `injectors.defaultRequire = 1` → 2.6 用户启动即 mixin apply 失败 | `.audit/version_matrix.txt` + `lib/bbs-fs-2.6-1.20.1/src/main/java/mchorse/bbs_mod/utils/keyframes/{KeyframeChannel,Keyframe}.java`（putLoop/removeLoop/setMotionShift 命中 0，而 2.7 对应为 3/1/5） | 二选一：**（推荐）** 把 `bbs_range` 抬到 `>=2.7`，并同步 fabric.mod.json 的依赖声明与 README 版本表；或保留 2.6 支持，给这 5 处注入加 `require = 0` + 运行时能力探测（`KeyframeChannel` 无 putLoop 时禁用区间锁并在 UI 隐藏入口） | 抬版本会失去 2.6 用户群，需产品决策 |
| P0-2 | 两份 mixin 配置不一致：`bbs-together.mixins.json` 有 `"overwrites": {"requireAnnotations": true}`，client 侧没有 | `.audit/registry2.txt` | 两边都显式声明，防止将来误写 `@Overwrite` 而无注解校验 | 低 |
| P0-3 | `remap` 声明不统一：9 个文件全篇无 `remap` 关键字（FilmRepositoryMixin、ModelBlockBreakGuardMixin、ModelBlockPickGuardMixin、ModelManagerMixin、UIDataDashboardPanelCommitsMixin、UIFilmPanelCommitsMixin、UIPanelTopBarCommitsMixin、UITabListCommitsMixin、ModelBlockEntityMixin），其余 66 个写 `remap=false` | `.audit/mixin_vs_bbs.txt` | 统一策略：BBS 类一律 `remap = false`（显式），原版类保持默认 true；顺手统一注解写法（数组 `method={"x"}` 与 `method="x"` 混用） | 极低，但改动面 9 个文件 |

**验收**：把 `bbs_range` 的下限与 `lib/bbs-fs-2.6-1.20.1` 里实际存在的成员做一次全量比对（脚本已在 `.audit/`），要求"声明支持的最低版本里 0 缺失成员"。

**执行记录（2026-11-21）**：P0-1 `bbs_range` 抬到 `>=2.7`（附原因注释），`README.md` / `README_en.md` 版本表同步；P0-2 client 侧补 `overwrites.requireAnnotations`；P0-3 的 7 个 BBS-target 文件共 15 处 `@Inject` 补 `remap = false`（两个原版 target 保持默认）。另修 `build.gradle` 第 181/187 行的 em dash 乱码（原为 `鈥?`）。`build` / `build1204` / `build12111` 全部 BUILD SUCCESSFUL。

---

### P1 — 死代码与漏登记（纯删除/接线，无行为风险）

| # | 对象 | 证据 | 动作 |
|---|---|---|---|
| P1-1 | `src/client/java/mchorse/bbs_together/mixin/UIElementContextMenuBbsOpMixin.java`（86 行） | 未被任何 mixins.json 登记；提交 `ded549e`（fix(ui): filter form context menus without dropping addon entries）已把只读拦截迁到 `UIFormCategory.buildContextMenu`，此文件成为遗留 | **删除文件**；同时修正 `UIFormCategoryBbsOpMixin.java:30-33` 的 javadoc（仍写着「live in {@link UIElementContextMenuBbsOpMixin}」） |
| P1-2 | `src/client/java/mchorse/bbs_together/ui/KeyframeLockOverlay.java`（389 行） | 早期脚本统计「全仓引用数 0」，复核为**误判**：`UIKeyframesLockMixin.java:43` 以静态方式调用 `KeyframeLockOverlay.render(...)`，属活跃渲染路径 | **保留**。`.audit/orphans.py` 只匹配构造式引用（`new X(`），漏计静态调用；后续引用统计需按静态调用方式复核 |
| P1-3 | `.workbuddy/`、`dist/`、`logs/`、`run*/`、`build*/`、`.gradle-old`&nbsp;`.gradle-user`&nbsp;`.gradle-build`、根目录解包残留 `META-INF/` `assets/` `bbs.mixins.json` `bbs.client.mixins.json` `bbs.accesswidener` `fabric.mod.json` `client-log.txt` | 均为 .gitignore 已忽略但实际存在的构建/解包残留；根目录那几个 json 是 **BBS 本体**的 mixin 列表（`mchorse.bbs_mod.mixin` 包，含 iris/sodium 条目），与本 addon 无关 | 清理工作区（不涉代码）；在 `.gitignore` 补 `.audit/` |
| P1-4 | `lib/bbs-fs-2.7-1.20.1/bbs-fs-2.7-1.20.1.zip`（114 MB） | 其 `src/` 1847 条目与当前 `lib/bbs-fs-2.7-1.20.1/src/` **逐字节相同**（`.audit/lib_zip_diff.txt`），纯冗余快照 | 删除（或移出仓库）。它不能作为"上游参照"，留着只增加误导 |

**验收**：`git grep` 目标符号 0 命中；三个构建目标全绿；jar 内 lang 键无悬空（`checkLangCopy` 通过）。

**执行记录（2026-11-21，随 P0 一并提交）**：P1-1 已删除文件并修正 javadoc；P1-2 复核为误判，文件保留；P1-3 工作区解包残留已清理，`.gitignore` 已补 `.audit/`；P1-4 的 114 MB zip 已删除。另按 Q4 决策把 `lib/bbs-fs-2.6-1.20.1/` 源码整体移出仓库（1816 个文件）。

---

### P2 — 同 target 合并（8 组中 7 组做合并，净减 7 个文件；C 组改为删除）

已有的先例：`UIFilmPanelMixin` 的类注释明确写过「All UIFilmPanel injections in one class … handler declaration order mirrors the previous cross-class application order」，说明合并手法在本项目已被验证可行。

| 组 | 现状 | 合并方案 | 注意 |
|---|---|---|---|
| A | UIFilmPanel ← `UIFilmPanelMixin`(17 注入) + `UIFilmPanelCommitsMixin`(3) | 后者并入前者，**必须保持 handler 声明顺序**（注释已说明：顺序即应用顺序） | 3 个注入里有 `@Inject(at = INVOKE)`，注意 `@At` 目标不要因方法体内 lambda 编号变化而失效 |
| B | UIClips ← `UIClipsMixin`(15) + `UIClipsLockMenuMixin`(1, `<init>` TAIL) | 后者并入前者 | `<init>` 注入依赖字段初始化顺序，合并后确认 `@Unique` 字段仍在正确位置 |
| C | UIElement ← `UIElementKeyframeLockMixin`(5) + `UIElementContextMenuBbsOpMixin`(1) | 直接删后者（见 P1-1），不合并 | — |
| D | ValueChangeUndo ← `ValueChangeUndoLockMixin`(2) + `ValueChangeUndoResolveMixin`(1) | 后者并入前者，类名保留 `ValueChangeUndoLockMixin` | 三个注入都是 HEAD，合并后注意 undo/redo/resolveValue 的相对顺序 |
| E | IUIKeyframeGraph ← `IUIKeyframeGraphLockMixin`(2) + `IUIKeyframeGraphDefaultKeyframeMixin`(2) | 后者并入前者（接口 mixin，`addKeyframe` 上 HEAD 两处 + RETURN） | 两个 HEAD 注入在同一方法：合并时必须区分 `cancellable` 语义，避免后一个覆盖前一个的 cancel |
| F | UIFilmPreview ← `UIFilmPreviewBbsOpMixin`(2) + `UIFilmPreviewNullGuardMixin`(2) | 后者并入前者 | 后者是 `render` 的 HEAD 空指针守卫，前者 TAIL 追加菜单；合并后确认守卫仍然最先执行 |
| G | UIDataDashboardPanel ← `UIDataDashboardPanelCommitsMixin`(4) + `UIFilmPanelSaveSyncMixin`(3) | 合并后**按实际 target 重命名**（现文件名 `UIFilmPanelSaveSyncMixin` 指向 UIDataDashboardPanel，属误导命名） | 周期保存标志 `together_periodicSaveInProgress` 与 commits 的 `saveOpen/showTab` 共用同一 panel，注意重入 |
| H | ModelBlockEntity ← `ModelBlockEntityMixin`(1, updateForm TAIL) + `ModelBlockEntityPacketMixin`(1, toUpdatePacket HEAD) | 合并 | 简单 |

**验收**：合并前后用 `gradlew build` 的 mixin 应用日志对比注入数量一致（213 不变），运行 `runClient` 冒烟：打开影片面板 / 剪辑轴 / 关键帧轴 / 仪表盘 / 模型方块面板各一次，无 `MixinApplyError`。

---

### P3 — 用 BBS/addon 官方事件替代 mixin（"尽可能少的 mixin 注入"的主战场）

BBS 2.7 的 addon API（`lib/bbs-fs-2.7-1.20.1/ADDONS.md` + `mchorse.bbs_mod.api.**`）已提供下列事件，逐条评估能否吃掉现有注入。**每条都要先做等价性验证再删 mixin**。

| 候选 API | 可替代的现有 mixin | 等价性风险 |
|---|---|---|
| `TimelineEvents.OVERLAY.render(Film, UIContext, Area, DoubleToIntFunction toX)` | `UIClipsMixin` 的 `render` TAIL 覆盖层；`UIKeyframesLockMixin` 的 `renderOverlay` | **中**：API 给的是影片绝对 tick → toX，若现有代码依赖 `UIClips`/`UIKeyframes` 内部滚动/缩放私有状态则不能直接换。需逐行比对 |
| `FilmEditEvents.CHANGED.changed(Film, List<BaseValue>, Cause)` | `UIFilmUndoHandlerMixin` 的 `handleValue`（4 注入） | **中**：API 只在批事务完成时发，加载/播放/拖动中间样本不发；若现有逻辑依赖中间态（同步队列 `TogetherEditSyncQueue`）则保留 mixin，只把"最终通知"部分迁出 |
| `RegisterDashboardPanelsEvent`（含 `public final UIDashboard dashboard`） | `UIDashboardPanelsBbsOpMixin.registerPanel` TAIL | **低**：直接用注册事件加面板。仅 `renderBackground` 那处需保留 |
| `RegisterPreviewOverlaysEvent.register(Function<UIFilmPreview, UIElement>)` | `UIFilmPreviewBbsOpMixin` 的 `<init>` 菜单追加（若只是加可交互图层） | **中**：语义是"自有图层"，不是"往别人菜单里塞条目" |
| `RegisterFrameOverlaysEvent` | `ModelBlockEntityRendererBbsOpMixin` / 渲染类叠加 | 低（若确为成品帧叠加） |
| Fabric `CommandRegistrationCallback`（原版 Fabric API，非 BBS） | `BBSCommandsMixin.register` TAIL | **低**：把 `/bbstogether` 子命令改用 Fabric 命令回调注册，可整类删除 |
| Fabric `AttackBlockCallback` + `PlayerBlockBreakEvents.BEFORE` | `ModelBlockBreakGuardMixin`（attackBlock / updateBlockBreakingProgress / breakBlock） | **中**：`updateBlockBreakingProgress`（长按持续破坏）没有等价事件，可能需保留 1 条注入；必须双开实测 |
| `VanillaAccess` / `ClientVanillaAccess`（api.compat） | `ValueGroupForeignAccessor`（纯 `@Accessor`/`@Invoker` 类） | **低**：先确认 API 暴露的访问面是否覆盖；覆盖则删除 accessor mixin |
| — | `ModelBlockPickGuardMixin`（`MinecraftClient.doItemPick`） | 原版无 pick 事件，**保留 mixin**（1 条注入，成本可接受） |

**明确不能改为事件的部分（ADDONS.md 已声明）**：Audio clip（需继承 `AudioClip`）、Track kinds（枚举）、per-instance 运行时附加字段。这几处的 mixin 属结构性必需，不进收敛范围。

**验收**：每个替换都要有一条可复现的手动用例（见 P5 表格），且 `git diff` 中 mixin 注入数下降、行为日志/网络包内容逐字段一致。

---

### P4 — 结构性整理（"整理项目所有内容"）

| # | 对象 | 现状 | 方案 | 风险 |
|---|---|---|---|---|
| P4-1 | `TogetherClientNetwork.java` 4718 行 / `TogetherServerNetwork.java` 4290 行 | 两个上帝类，包住协议分发 + 状态机 + 业务逻辑 | 按**协议域**拆包：`network/protocol`（包注册与编解码）、`network/handler/{film,clip,keyframe,form,asset,coop,behavior,locks,commits}`、`network/state/{CoopSession,EditSyncQueue,UploadAggregate}`。**纯搬移**，不改逻辑；每拆一步跑一次构建 | 中：静态方法互相调用多（client 470 引用 / server 523 引用），需先画调用图，避免循环依赖；建议先拆"无状态工具"再拆 handler |
| P4-2 | `BBSTogetherFix14`(470)/`Fix19`/`Fix28Client`/`Fix28Server`(364)/`Fix33Client`/`Fix45`/`Fix65`/`Fix66Client`/`Fix67UndoPath`/`FixReplayStructure`(475) 等 10+ 个以修复编号命名的类 | 命名只对历史提交有意义，对新人零信息量 | 按职责重命名（如 `Fix28Server` → `FilmLeaveSnapshotSync`、`Fix19` → `RecordingHandoff`、`FixReplayStructure` → `ReplayStructureRepair`），类内注释把"fix28"改写成问题描述 | 低-中：需同步 README/文档中的引用；用 IDE 重命名 + 全仓 grep 校验 |
| P4-3 | mixin handler 名 13 文件 / 21 处（`together_fix1615BeforePasteReplay`、`together_fix133ApplyReplayCategory`、`together_fix164MarkSmallReenter`、`together_fix131SmallExitSave`、`together_fix30Appear`、`together_fix28JoinFilm`、`together_fix132SkipRenderWithoutFilm`、`together_fix65*`、`together_skipEmptyKey`） | 同上 | 重命名为语义名（`onBeforePasteReplay` 等），注释保留"为什么需要"而不是编号 | 低：`@Unique` 方法改名不影响注入；但要确认没有通过字符串引用的地方 |
| P4-4 | 注释体量 4048 行 / 910 个 javadoc | 大量 javadoc 写的是"改了什么/第几版修的" | 保留：为什么这么做、坑在哪、上游行为前提；删除：变更史、编号、被代码自我解释的逐行说明 | 低 |
| P4-5 | 文档 | `docs/` 已有优化表 1.7.9 / 2.1、计划表双版本、验收清单 2.0 | 归档旧版到 `docs/archive/`；把本计划执行结果合并成 `docs/架构-TogetherLink.md`（模块地图 + 协议表 + mixin 清单 + API 替代决策表），READEME 只留快速上手 | 低 |

---

### P5 — 验证矩阵（每阶段结束时都要跑）

| 用例 | 覆盖点 | 判定 |
|---|---|---|
| `build` / `build1204` / `build12111`（**分三条命令**） | 三目标编译、`apiCheck`、`checkLangCopy` | 三条各自 `BUILD SUCCESSFUL`，产出 jar 名与版本号正确 |
| `runClient` 冒烟 | 启动无 `MixinApplyError`；打开影片面板 / 剪辑轴 / 关键帧轴 / 仪表盘 / 模型方块面板 / 表单列表 | 无崩溃、无注入失败日志 |
| 双开联机：只读账号 | 权限拦截（P3 里 `PermissionUtils`/`StartRecording` 相关注入未被误伤） | 只读账号看不到可写菜单，且**没有静默无响应**（当前已知问题：拦截无提示，见开放问题） |
| 双开联机：关键帧锁 | `KeyframeLockMixin` / `KeyframeChannelLockMixin` / `ValueChangeUndo*` 全链路 | 锁住的通道不能写入；撤销不能绕过；合并后行为与 2.1 一致 |
| 双开联机：剪辑锁 + 撤销 | 剪辑锁定、撤销同步队列 | 远端改动不被本地撤销覆盖 |
| 双开联机：资产同步 + 超大模型 | `*Oversized*` 5 个 mixin、资产锁 | 超大模型单元格角标与提示正常 |
| 双开联机：合作录制 | `CoopClient`/`CoopSessionManager`/`UIFilmControllerRecordingSyncFix19Mixin` | 邀请→选人→录制→合并全链路通 |
| BBS 2.6（若保留支持） | P0-1 的直接验证 | 不崩溃且相关功能降级可见 |

---

### P6 — 收尾

1. 删除 `.audit/`（或加进 `.gitignore` 保留为工具目录）；把 `mixin_vs_bbs.py`、`version_matrix.py` 移到 `scripts/audit/` 作为**长期回归工具**（改 BBS 版本时必跑）。
2. `docs/` 归档 + 新架构文档落笔。
3. 版本号 → 2.2，`CHANGELOG` 列出：兼容性修复、删除清单、mixin 收敛前后数字（75 → 目标值 / 213 → 目标值）。
4. README 双语的版本/依赖表同步（`bbs_range` 变更必须写）。

---

## 四、预期收益（量化目标）

| 指标 | 现在 | P2 后 | P3 后（目标） |
|---|---|---|---|
| mixin 文件 | 75 | ~67 | **≤ 60** |
| 注入注解 | 213 | 213（不变，只是合并容器） | **≤ 185** |
| 注入原版类的 mixin | 2 | 2 | 1（`doItemPick` 保留） |
| 最大单文件 | 4718 行（非 mixin） | 4718 | ≤ 1200（按协议域拆分后） |
| 死代码 | 2 处已知（475 行） | 0 | 0 |

> 注：213 → 185 的估算基于 P3 表中 8 组"低/中风险"替代（约 28 条注入）。实际数字以执行后实测为准，**不为了凑数字牺牲行为等价**。

---

## 五、需要你决策的开放问题

1. **是否放弃 BBS 2.6 支持**（P0-1）？推荐放弃（`bbs_range` 抬到 `>=2.7`），因为 3 处注入在 2.6 里物理不存在，用 `require = 0` 换来的只是"静默降级 + 更难维护"。
2. **只读账号拦截要不要给提示**？现状是 `canEdit("film")==false` 时静默 cancel，用户以为按钮坏了（`.workbuddy/memory/2026-10-13.md` 已记录该诊断）。
3. **P4-1 上帝类拆分是否本轮做**？纯搬移、零行为变化，但改动面最大（9000 行），会与任何并行改动冲突。若近期还要开发新功能，建议先做 P0/P1/P2/P3，P4-1 单独排期。
4. **`lib/` 三份完整 BBS 源码是否继续留在仓库**？`lib/bbs-fs-2.6-1.20.1` 若不再支持 2.6（问题 1 选"放弃"），可从仓库移除，仅保留 2.7 与 1.21.11 两份。

---

## 附录 A：mixin 全量清单与逐条处置

按功能域归拢的 75 个 mixin（每行右侧为该域内文件与本轮处置；标记 **删除**/**合并** 的即 P1/P2 的动手对象）。

| 功能域 | 文件数 | 成员与处置 |
|---|---|---|
| 权限（bbsop） | 16 | `ActionManagerBbsOpMixin` `FilmRecordingControllerBbsOpMixin` `FilmsBbsOpMixin` `ModelBlockEntityRendererBbsOpMixin` `PermissionUtilsBbsOpMixin` `ServerNetworkBbsOpMixin` `UIDashboardPanelsBbsOpMixin`(部分替换) `UIFormCategoryBbsOpMixin` `UIFormListBbsOpMixin` `UIFormPaletteBbsOpMixin` `UIModelBlockPanelBbsOpMixin` `UIModelEditorPanelBbsOpMixin` `UIReplayPropertiesPanelBbsOpMixin` `UIReplaysListPanelBbsOpMixin` `UIFilmPreviewBbsOpMixin`（均保留）；`UIElementContextMenuBbsOpMixin` **删除** |
| 锁与撤销 | 18 | 保留 15：`BaseValueEditLockMixin` `FilmStorageLockMixin` `IUIKeyframeGraphLockMixin` `KeyframeChannelLockMixin`（\*） `KeyframeLockMixin`（\*） `UIClipLockShieldMixin` `UIClipsPanelPickGuardMixin` `UIElementKeyframeLockMixin` `UIKeyframeClipLockMixin` `UIKeyframeFactoryLockMixin` `UIKeyframeLoopsLockMixin` `UIKeyframesLockMixin` `UINumericKeyframeFactoryLockMixin` `UIReplayPropTransformLockMixin` `ValueChangeUndoLockMixin`；**合并** 3：`IUIKeyframeGraphDefaultKeyframeMixin`→`IUIKeyframeGraphLockMixin`、`UIClipsLockMenuMixin`→`UIClipsMixin`、`ValueChangeUndoResolveMixin`→`ValueChangeUndoLockMixin`。（\*）这两个需 P0-1 版本守卫 |
| 提交（commits） | 4 | `UIDataDashboardPanelCommitsMixin`（合并容器，接收 G 组） `UIPanelTopBarCommitsMixin` `UITabListCommitsMixin`；`UIFilmPanelCommitsMixin` **合并** → `UIFilmPanelMixin` |
| 超大模型/贴图 | 5 | `FormCellRendererTooLargeMixin` `UIFormCategoryModelSizeMixin` `TextureCellRendererOversizedMixin` `UIListOversizedMixin` `UILikeableStringListOversizedMixin`（均保留，可共抽 helper） |
| 合作录制 | 3 | `ActionPlayerCoopCastMixin` `UIBaseMenuCoopPopupMixin`；`UIFilmControllerRecordingSyncFix19Mixin`（保留，**重命名**） |
| 影片面板与回放 UI | 13 | `UIFilmPanelMixin`（合并容器） `UIFilmPanelSaveSyncMixin` **合并**→`UIDataDashboardPanelCommitsMixin` `UIFilmPreviewNullGuardMixin` **合并**→`UIFilmPreviewBbsOpMixin` `UIFilmUndoHandlerMixin`（部分替换） `UIReplayListMixin` `UIReplaysEditorMixin` `UIDataOverlayPanelMixin` `UIDataPathListMixin` `UIDashboardMixin` `UIUtilityOverlayPanelMixin` `FilmRepositoryMixin` `FilmLoadQuietMixin` |
| 存储/命令/边界守卫 | 4 | `PacketCrusherBoundsMixin` `FormStableIdsEmptyKeyMixin` `UserFormSectionCategoriesPathMixin`（保留）；`BBSCommandsMixin` **可删** → Fabric `CommandRegistrationCallback` |
| 原版类与渲染/入口杂项 | 12 | `ModelBlockEntityMixin`（合并容器） `ModelBlockEntityPacketMixin` **合并**→前者 `ModelBlockBreakGuardMixin`（候选换 Fabric 事件） `ModelBlockPickGuardMixin`（保留） `ModelManagerMixin` `SyncHudOverlayMixin` `BBSClientInitMixin`（评估） `ValueGroupForeignAccessor`（评估） `UIKeyframeDopeSheetMixin`×2、`MorphRendererMixin`×2（版本覆盖，保留） |
| **合计** | **75** | 删除 1（P1-1 的 `UIElementContextMenuBbsOpMixin`）、合并减 7 → P2 后 67 |

> 逐文件的 target / 注入成员 / `@At` 已在 `.audit/mixin_map.txt` 与 `.audit/mixin_vs_bbs.txt` 中；执行期应把这两个脚本移到 `scripts/audit/` 作为回归工具（改 BBS 版本时必跑），而不是随 `.audit/` 一起删掉。

## 附录 B：删除清单（执行后回填）

| 路径 | 原因 | 替代 |
|---|---|---|
| `src/client/java/mchorse/bbs_together/mixin/UIElementContextMenuBbsOpMixin.java` | 未登记，功能已迁 `UIFormCategoryBbsOpMixin` | `UIFormCategoryBbsOpMixin.buildContextMenu` |
| `src/client/java/mchorse/bbs_together/ui/KeyframeLockOverlay.java` | 引用数 0 | `UIKeyframesLockMixin` 内联渲染（待确认） |
| `lib/bbs-fs-2.7-1.20.1/bbs-fs-2.7-1.20.1.zip` | 与 lib src 逐字节相同的冗余快照 | 无（无需替代） |

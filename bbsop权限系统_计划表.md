# bbsop 权限系统 · 实施计划表

> 目标：为服务器新增 `/bbsop` 指令，服主可为玩家设置 0～2 三级「BBS 编辑权限」。
> 权限由**服务端裁定、客户端执行 UI 限制**：0 级玩家进入 BBS 后全程只读（可浏览、可下载，不可编辑、不可上传）。
> 本文档仅为计划，**暂不改动任何代码**。一期只实现 **0 级**，1/2 级仅预留枚举。
> 适用版本：bbs-together / BBS FS 2.7（lib 内参考源码）/ MC 1.20.1 Fabric。

---

## 一、需求拆解

### 1.1 权限等级

| 等级 | 名称 | 权限 | 一期状态 |
|---|---|---|---|
| 0 | 只读（浏览者） | 仅可浏览所有内容；仅可**下载**资产；**不可上传**、不可编辑任何内容 | ✅ 本期实现 |
| 1 | （预留） | 待定 | ❌ 枚举预留，不做逻辑 |
| 2 | （预留） | 待定（等同完全编辑） | ❌ 枚举预留，不做逻辑 |

- 玩家进入服务器**默认 0 级**（未设置 = 0）。
- 权限数据存**服务端**（JSON 持久化），随玩家名/UUID 保存。

### 1.2 指令设计

```
/bbsop <player> <level>     // level ∈ {0,1,2}，一期实际只生效 0/1/2 数值均可写入，但 UI 仅按 0 处理非零……见 1.3
/bbsop <player>             // 查询该玩家当前等级
```

- 使用门槛：`requires(src -> src.hasPermissionLevel(2))`，与现有 `bbstogether` 指令一致 ——
  独服控制台 / OP（含单人开服房主）可用，普通玩家不可用。
- 修改成功后：服务端持久化 → **立刻向目标玩家下发同步包**，客户端即时刷新 UI 锁定状态（无需重进）。
- 注册位置：`TogetherCommands.register()` 内追加，风格保持一致。

### 1.3 语义确认（需服主确认的点）

- 1/2 级一期「可写入数值但无差异化效果」还是「直接拒绝写入 1/2」？
  **建议**：允许写入并保存数值，客户端逻辑统一为「非 0 全放开」，等二期再细分 —— 这样将来加 1 级限制时无需迁移数据。
- 0 级玩家在**单人本地世界**（未连服务器）时不受任何限制：权限系统仅在 `TogetherClientNetwork.isConnected()` 时生效（与现有各 mixin 的判断一致）。

---

## 二、现状分析（已核对 BBS 2.7 与本项目源码）

### 2.1 服务端已有可复用的机制

| 机制 | 位置 | 用途 |
|---|---|---|
| 面板编辑门槛先例 | `bbs_mod/utils/PermissionUtils.arePanelsAllowed`：gamerule `bbsEditing` 或 OP 才允许 | 证明「服务端拒绝写包」是可行路线，但它是全局/OP 粒度，我们需**按玩家**粒度，另建 `BbsOpManager` |
| 资产上传入口 | `TogetherServerNetwork`：`SERVER_ASSET_UPLOAD` → `handleAssetUpload` | 0 级拦截点 ①（不可上传） |
| 影片保存同步 | `TogetherServerNetwork`：`SERVER_FILM_SAVE_SYNC` / `SERVER_FILM_DATA_SYNC`；BBS 侧 `ServerNetwork.handleManagerDataPacket`（SAVE/RENAME/DELETE…） | 0 级拦截点 ②（不可保存/改删影片） |
| 模型方块写入 | BBS `ServerNetwork.handleModelBlockFormPacket / handleModelBlockTransformsPacket`（本项目已有 `ServerNetworkModelBlockMixin` 可参考挂法） | 0 级拦截点 ③ |
| 指令注册范式 | `TogetherCommands`（brigadier，`hasPermissionLevel(2)`） | `/bbsop` 照此风格注册 |
| 行为日志 | `BehaviorLogManager.record(...)` | 可选：记录「服主修改了某玩家权限」审计日志（复用 EDIT 类） |

### 2.2 客户端需挂钩的 UI 类（均在 lib/bbs-fs-2.7 参考源码中确认）

| 页面 | BBS 类 | 关键钩子 |
|---|---|---|
| 伪装（变形）页 | `ui/morphing/UIMorphingPanel` | 右键菜单 / 编辑按钮置灰 |
| 影片页 | `ui/film/UIFilmPanel`、`ui/film/controller/UIFilmController` | `setFlight/toggleFlight`（`Keys.FLIGHT` 键位）；录制为 `Keys.FILM_CONTROLLER_START_RECORDING` → `pickRecording`（按钮 + 键位两处都要拦，已核对 2.7 源码）；`Keys.UNDO/REDO` 撤销重做；保存入口 |
| 音频录制 | `ui/film/audio/UIAudioRecorder`（`startRecording`） | 属于「录制」范畴，一并禁用 |
| 回放栏 | `ui/film/replays/UIReplayList`、`UIReplaysEditor`、`UIReplayPropertiesPanel`、`UIReplaysListPanel` | 上下文菜单（`MenuVerb.ADD`、删除、剪辑等）、面板禁用；「添加回放」按钮为 `UIReplaysListPanel` 中 `new UIIcon(Icons.ADD, b -> replays.addReplay())`（已核对 2.7 源码） |
| 时间轴 | `ui/film/clips/UIClips`、关键帧曲线（本项目已有 `UIClipsMixin`、`UIKeyframeDopeSheetMixin` 等挂点先例） | 禁止拖动/编辑 clip 与关键帧，保留播放头拖动 |
| 模型方块页 | `ui/model_blocks/UIModelBlockPanel` | 右侧编辑面板不可选中，仅可右键浏览页面 |
| 粒子页 | `ui/particles/UIParticleSchemePanel` | 整页锁定 |
| 模型编辑器页 | `dashboard/panels` 注册的 `UIModelEditorPanel` | 整页锁定 |
| 纹理编辑器页 | `dashboard/textures/UITextureManagerPanel` | 整页锁定 |
| 音频编辑器页 | `utility/audio/UIAudioEditorPanel` | 整页锁定 |
| 面板总入口 | `UIDashboard.registerPanels()`（buildStep morphing/film/model blocks/particles/model editor/textures/audio） | 统一注册拦截 |

### 2.3 锁定视觉表现（沿用 BBSFS 本体美术风格）

- **按钮置灰**：UIIcon 使用灰色绘制（BBS 已有 `enabled` 概念与暗色图标渲染），禁用回调。
- **栏位覆盖灰膜 +「仅浏览」**：在目标面板上方叠一层半透明灰罩（参考本项目 `SyncBarRenderer` 直接用 `UIRenderingContext` 画罩的写法），中央绘制文本「仅浏览 / View only」（多语言走 `strings/*.json`）。
- **上锁图标**：复用 `Icons.LOCKED`（本项目 `UIDashboardMixin` 已有画 LOCKED 图标的现成代码可抄）。
- 时间轴/回放项「可选中不可编辑」：选中高亮保留，右键菜单直接拦截不弹出。

---

## 三、总体架构

```
服务端                                客户端
┌────────────────────────┐            ┌──────────────────────────────┐
│ BbsOpManager           │            │ BbsOpClientState             │
│  · Map<UUID,Integer>   │  S2C 同步   │  · 当前玩家等级（默认 0）      │
│  · JSON 持久化 run/…   │ ─────────▶ │  · isReadOnly() 判断          │
│  · 连服时下发 / 变更推送 │            │      (等级==0 且已连接服务器)   │
│ /bbsop 指令入口         │            │ 各 UI Mixin 读取 → 置灰/上锁/  │
│ 写包拦截(上传/存片/方块) │            │ 灰罩「仅浏览」                 │
└────────────────────────┘            └──────────────────────────────┘
```

原则：**服务端为最终裁决**（客户端 UI 锁定只是体验层，被改客户端也无法通过上传/保存包写入）；
**客户端为表现层**（所有置灰、灰罩、上锁都只看本地收到的等级）。

---

## 四、实施步骤

### 阶段 A：数据与网络（服务端为主）

1. **新增 `BbsOpManager`**（`src/main/.../bbsop/`）
   - `Map<UUID, Integer>` + `get(uuid)`（无记录 = 0）+ `set(uuid, level)`。
   - 持久化：`run/bbs_together/bbsop.json`（仿 `BehaviorLogManager` 的读写方式）。
2. **新增网络包**
   - S2C `c_bbsop_sync`：写入 `int level`，客户端更新 `BbsOpClientState`。
   - 时机：玩家进入服务器（join 事件）、`/bbsop` 修改后向目标玩家推送。
3. **注册 `/bbsop` 指令**（改 `TogetherCommands`）
   - `literal("bbsop").requires(level 2)` → `argument("targets", EntityArgumentType.players())` + `integerArgument("level", 0, 2)`。
   - 子命令/空 level 时回显当前等级。反馈消息走 `strings/*.json` 多语言（中英必翻，其余语言补占位即可，与现有惯例一致）。

### 阶段 B：服务端强制拦截（0 级）

4. **拦截上传**：`handleAssetUpload` 入口处（或包一层 Mixin/直接改本类，它在自家 mod 里）—— 等级 0 → 拒绝并可选回发提示。
   - **放行下载**：`SERVER_ASSET_REQUEST`（下载请求）**不拦**——0 级允许下载是明确需求；上传/下载两条通道分离，互不影响。
5. **拦截影片写入**：
   - `SERVER_FILM_SAVE_SYNC` / `SERVER_FILM_DATA_SYNC` 处理入口：等级 0 → 丢弃；
   - BBS `handleManagerDataPacket` 的 SAVE/RENAME/DELETE/ADD_FOLDER 等写操作：新增 Mixin（仿 `ServerNetworkNullFilmFixMixin` 挂法）在等级 0 时 return。LOAD/KEYS/BACKUPS 等读操作放行（浏览+下载不受限）。
6. **拦截模型方块写入**：`handleModelBlockFormPacket` / `handleModelBlockTransformsPacket` 同上加门槛。
7. **拦截远端录制通道**（本项目独有，易漏）：`bbstogether record <players>` → `TogetherServerNetwork.sendToggleRecording(player, true)` 会直接给客户端发开录包，**完全绕过客户端 UI 锁定**。需在该入口（或 `TogetherCommands` 的 record 目标过滤处）跳过 0 级玩家并向服主反馈"某玩家为 0 级无法录制"。

### 阶段 C：客户端 UI 锁定（0 级）

7. **新增 `BbsOpClientState`** + `isReadOnly()` = `TogetherClientNetwork.isConnected() && level == 0`。
8. **伪装页**（Mixin `UIMorphingPanel`）：右键菜单与「编辑伪装」入口在只读时置灰/不弹菜单（按钮灰色 = BBS 禁用态）。
9. **影片页**（Mixin `UIFilmPanel` + `UIFilmController`）：
   - `toggleFlight/setFlight` 直接 return；`Keys.FLIGHT` 键位 `.active(() -> !readOnly)`；
   - 录制拦截**两处**：`Keys.FILM_CONTROLLER_START_RECORDING` 键位 `.active(() -> !readOnly)` + 按钮/入口置灰变暗（`FilmRecordingController.startRecording` 前拦截兜底）；音频录制 `UIAudioRecorder.startRecording` 一并禁用；
   - `Keys.UNDO/REDO`（撤销/重做）与保存入口只读时禁用（服务端已有兜底，客户端纯体验）；
   - 图层区上方绘制 `Icons.LOCKED`（复用 `UIDashboardMixin` 的画法）。
10. **属性面板**（Mixin `UIReplayPropertiesPanel` 与剪辑属性面板容器）：整栏覆盖灰罩 + 中央「仅浏览」文本；控件统一禁用。
11. **时间轴**（扩展现有 `UIClipsMixin` / dope sheet Mixin）：
    - 禁止拖动 clip、关键帧、剪辑编辑；播放头（cursor）拖动保留；
    - 兜底：保存/撤销等写操作回调里再判一次只读。
12. **回放栏**（Mixin `UIReplayList` / `UIReplaysEditor` / `UIReplaysListPanel`）：
    - 拦截上下文菜单（右键不弹，包括 `MenuVerb.ADD` 一切增删改项）；
    - 「添加回放」按钮（`UIReplaysListPanel` 的 `Icons.ADD` → `addReplay()`）置灰（表现方式与飞行模式禁用一致）；
    - 保留：选中回放、滚动浏览。
13. **模型方块页**（Mixin `UIModelBlockPanel`）：仅可右键页面浏览；右侧编辑面板不可选中（效果与影片属性面板同风格：灰罩 + 仅浏览）。
14. **其余四页整体上锁**（粒子 / 模型编辑器 / 纹理 / 音频）：在 `UIDashboardPanels.setPanel` 或各 Panel 的 `onOpen` 处拦截，页面内容上覆灰罩 + 锁图标 + 仅浏览；底部导航按钮保留可点（能看到页面但不可编辑）。

### 阶段 D：收尾

15. **多语言**：`zh_cn` / `en_us` 必翻（`bbsop` 指令反馈、「仅浏览」文案、权限等级名），其余 18 个语言文件补英文占位。
16. **审计日志（可选）**：`/bbsop` 修改写入 `BehaviorLogManager`（EDIT 类：服主修改了某玩家的 BBS 权限）。
17. **构建与自测**（见第五节验收清单）。

---

## 五、验收清单（0 级玩家视角）

- [ ] 新玩家进服默认 0 级，BBS 各页呈只读态；服主设 0 级玩家行为一致
- [ ] `/bbsop` 仅服主/控制台/OP 可用；玩家执行被拒绝
- [ ] 修改等级后**无需重进**，目标玩家 UI 即时切换锁定/解锁
- [ ] 0 级：伪装列表右键不可编辑（按钮灰）
- [ ] 0 级：影片页飞行模式、录制按钮禁用（图标变暗）+ 图层上方锁图标
- [ ] 0 级：回放属性 / 剪辑属性面板灰罩 +「仅浏览」
- [ ] 0 级：时间轴不可拖 clip/关键帧，**可拖播放头**
- [ ] 0 级：回放栏不可添加回放、右键无菜单，仅可选中
- [ ] 0 级：模型方块页仅可右键浏览，右侧面板不可选
- [ ] 0 级：粒子/模型编辑器/纹理/音频四页上锁
- [ ] 0 级：**可以**下载资产；**不能**上传（改客户端强发也会被服务端拒绝）
- [ ] 0 级：录制键位（FILM_CONTROLLER_START_RECORDING）、撤销/重做、保存入口均不可用
- [ ] 0 级：音频录制（UIAudioRecorder）不可用
- [ ] 非连接服务器时（单人本地）所有功能不受影响
- [ ] 1/2 级数值可保存、当前行为等同放开，二期再细分

## 六、风险与备注

- **客户端锁定可被绕过**：所以 B 阶段服务端拦截是安全底线，C 阶段只是体验。两者都要做。
- **UIFilmPanel 体量大**：飞行/录制/时间轴分散在多个内部类，优先复用本项目已有 Mixin 挂点（`UIClipsMixin`、`UIFilmPanelRecordingMixin` 等）追加判断，减少新增注入点。
- **BBS 版本差异**：关键类 `UIMorphingPanel`、`UIReplayList`、`UIModelBlockPanel` 已在 2.6 / 2.7 双参考源码中**确认均存在**（类名一致）；动手前仅需比对回放右键菜单结构等细节差异。
- **多入口遗漏风险**：录制、添加回放、保存、撤销重做都存在「按钮 + 键位」双入口，客户端拦截必须两处都盖住（计划各条已注明），并以服务端拦截兜底。
- **灰罩渲染**：统一走 `UIRenderingContext` 自绘（参考 `SyncBarRenderer`），避免侵入 BBS 布局；多面板复用同一个渲染工具类（如 `ReadOnlyOverlayRenderer`）。
- 一期不做的：1/2 级差异化、按页细粒度白名单、离线玩家设置（可二期用 offline UUID 扩展）。

## 七、与现有系统的叠加关系（对照本项目源码核对补录）

1. **资产同步开关（`TogetherSettings`）**：本项目已有 `assetSyncUpload / assetSyncDownload / assetSyncReceive` 等开关。优先级约定：**0 级权限拦截独立于这些设置，始终生效**（即使服主开了 upload，0 级玩家仍拒）；非 0 级时完全沿用现有开关逻辑，互不干扰。实现时拦截点放在开关判断**之前**。
2. **服务器行为列表面板的「设为 OP / 解除 OP」**：该右键菜单操作的是**原版服务端 OP 列表**（`getPlayerManager().isOperator`），与 bbsop 的 0~2 级是**两套独立体系**——原版 OP 不自动获得 bbsop 等级（仍默认 0），bbsop 等级也不赋予原版 OP。一期不做联动；二期可考虑在行为面板玩家右键菜单追加「设置 BBS 权限」入口（0/1/2），复用 `/bbsop` 的服务端逻辑。
3. **行为审计日志（`BehaviorLogManager`）**：0 级玩家上传/保存被服务端拒绝时，可选记一条拒绝记录（复用 UPLOAD/EDIT 类 + `SUB_*` 子类），便于服主在行为面板里发现违规尝试；默认关闭，留作可选项。

## 八、覆盖度自查（对照 src/ 全量 Mixin 与网络通道）

| 项目现有通道 | bbsop 是否覆盖 |
|---|---|
| `SERVER_ASSET_UPLOAD`（上传） | ✅ B 阶段拦截 |
| `SERVER_ASSET_REQUEST`（下载） | ✅ 明确放行 |
| `SERVER_FILM_SAVE_SYNC / SERVER_FILM_DATA_SYNC`（存片） | ✅ B 阶段拦截 |
| BBS `handleManagerDataPacket`（SAVE/RENAME/DELETE） | ✅ B 阶段拦截（读操作放行） |
| 模型方块 form / transforms 两包 | ✅ B 阶段拦截 |
| `bbstogether record` 远端开录 | ✅ B-7 补录（服务端过滤） |
| 客户端 UI（伪装/影片/回放/时间轴/模型方块/四页） | ✅ C 阶段覆盖 |
| `TogetherClientNetwork` 下载侧（assetSyncDownload） | ➖ 不动，沿用现有开关 |

---

## 九、二期实施记录（2026-10-12：1/2 级权限落地）

- **等级语义**：0=仅浏览；1=编辑者（默认可编辑全部内容）；2=管理员（可编辑全部 + 管理他人等级/域）。
- **功能域**（`BbsOpDomain`）：morph / film / model_blocks / particles / model_editor / textures / audio / upload。
- **1 级域白名单**（`BbsOpManager.grants`）：黑名单制——首次"禁止"某域即进入白名单模式（其余全开）；白名单随 `bbsop.json` 持久化（新格式 `{levels, grants}`，兼容旧扁平格式）。`/bbsop <player> grant <domain> <allow|deny|reset>` 可在控制台配置。
- **管理入口**：行为面板玩家列表右键——"设置 BBS 等级 0/1/2"+（对 1 级玩家）八个域的允许/禁止开关；原版 OP 与 2 级玩家可用（服务端 `canManage` 复核）。
- **玩家列表三分类**：2 级（最上）→ 1 级 → 0 级，各带分组标题行，"所有玩家"仍置顶；等级持久化，玩家每次进服自动归入原分组。
- **网络**：新增 C2S `s_bbsop_set` / `s_bbsop_grant`；`c_bbsop_sync` 扩展携带白名单；玩家快照附带 `bbsop` 等级与 `bbsopGrants`。
- **拦截点全部改为按域判定**：服务端上传=UPLOAD、影片写包/远端录制=FILM、模型方块=MODEL_BLOCKS（`ServerNetworkBbsOpMixin` 同步）；客户端各 BbsOp Mixin 按 morph/film/model_blocks/四页各自域 + UPLOAD 判定。

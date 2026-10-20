# Together Link 1.7.9 优化与 CDN 适配评估表

> 生成：2026-10-20（Asia/Shanghai）  
> 基线：`master` / `e61d155` / `mod_version=1.7.9`  
> 本次只做评估，**未修改任何代码、配置或构建脚本**；只新增本报告。  
> 表中“预计收益”来自代码路径静态分析，不是运行时压测结果；实际收益随资产数量、影片体积、在线人数变化。

---

## 0. 先给结论

1. **体积**：当前 jar 657,001 B（252 个 entry，解压后 1,455,845 B）。不做激进混淆/裁剪的前提下，最现实的目标是 **600–620 KB（约 −6%～−9%）**；其中 locale 回退裁剪约 −22 KB、发布包调试信息约 −15～30 KB、日志/死代码/重复广播约 −10～20 KB。
2. **性能最痛的不是“算得慢”，而是“在主线程算”**：资产分片被逐片 gzip、影片保存按收件人逐份序列化+gzip、进服影片清单在主线程逐部 SHA-1、提交页和提交图在主线程做全量比较/每帧 O(n²) 绘制。这五处是 P0。
3. **内存最痛的是“按 film×branch 永久缓存整部影片”**：`lastSavedFilmSnapshots` 与 `FilmBranchStore.WORKING` 都没有上限，多人多分支长期运行会线性吃堆。这是 P0。
4. **CDN 可以“只读分发”，不能直接当权威源**：未改动的 CDN 只有 `/files`、`/file`、`/upload`、`/delete`，没有 per-player 权限、锁、tombstone、mtime、分片/断点。推荐“服务器权威 + CDN 单向镜像下载”，上传和删除仍走游戏服；否则 BBS 等级 0/1/2、资产锁、行为审计都会被绕过。
5. **发现三处“内容/发布不一致”**，建议在下个 patch 一并纠正：
   - `fabric.mod.json` 声明 `bbs >= 2.6`，但 `gradle.properties` 编译目标是 `2.7-1.20.1`，且 mixin 目标按 2.7 写；
   - 构建产物是 1.7.9，`dist/` 最新只有 1.7.6，用户实例里是 1.7.7；
   - Together 的 `cdnUrl` / `cdnToken` 已注册但代码从未读取；F6 的 CDN 按钮用的是 BBS 自己的同名配置，两套互不相通。
6. **F6 的「CDN」下载/上传不是 Together Link 的同步按钮**：它是 BBS 2.7 自带功能，读的是 `BBSSettings.cdnUrl/cdnToken`（`bbs.json`），不是 Together 的 `bbs_together.json`。两者语义/安全模型不同，确实存在适配问题，详见 X11 与 3.2；**不要把它当成 Together 同步的一部分**。

> **CDN 语义假设**：仓库内没有 CDN 服务端源码或部署目录。下表把 BBS 2.7 的 `CDNAssetSyncService` 四个 HTTP 接口当作“尚未改动的 CDN 服务器内容”。如果你指的是另一套 CDN/对象存储（或另一台游戏服），把路径/接口文档/仓库地址给我，我可以把 CDN 部分细化到字段级。

---

## 1. 实测基线

| 项 | 实测值 | 证据 |
|---|---|---|
| Java 源文件 | 143 个（main 45 / client 98），1,230,730 B | `src/**/*.java` |
| 最大的两个类 | `TogetherServerNetwork` 3,231 行 / 170,581 B；`TogetherClientNetwork` 4,054 行 / 191,531 B | 源文件 |
| 编译产物 | `together-link-1.7.9-1.20.1.jar` 657,001 B；252 entries；解压 1,455,845 B；entry 压缩后 608,517 B | `build/libs` |
| 类体积前三 | server network 149,732 B、client network 144,530 B、`UIFilmPanelMixin` 32,263 B | jar entry |
| Mixin | 50 个（client 42 + main 8），全部在 json 中注册；`required=true`、`defaultRequire=1` | 两个 mixins json |
| BBS 语言文件 | 18 个，117,627 B；16 个非英文文件中，15 个有 80–81/86 条与 `en_us` 完全相同（`de_de` 只有 5 条真翻译），`zh_tw` 为 46/90 条相同、44 条真翻译 | `src/main/resources/.../strings` |
| 构建 | `gradlew clean build --no-build-cache` 成功；主源码 `compileJava` 可执行、client 重新编译 | 本次构建日志 |
| 仓库体积 | `src` 1.3 MB；`lib` 390 MB（其中 `src` 47.9 MB 被 git 跟踪）；`dist` 19.2 MB（22 个 `.bak` 共 8.6 MB）；`run` 336.7 MB；`.gradle` 89.4 MB | 目录测量 |
| 发布一致性 | 构建 1.7.9；`dist/` 最新 `together-link-1.7.6-1.20.1.jar`；用户实例 `E:\mc\...\mods` 为 1.7.7 | 文件时间/版本 |

### 1.1 已经做过、本次不重复的优化

以下来自 1.7.4～1.7.8 的历史优化，已落地，不用再做：

- A1/A2：服务端/客户端 `(mtime,size)→sha1` 缓存；
- A3：光标 50 ms 节流 + 单条增量广播；
- A4：影片编辑落盘去抖（5 s/最长 15 s）；
- A5：资产分片 + 令牌桶限速；
- B1/B2：客户端同步偏好上报、每玩家已持有清单；
- B3：影片按需拉取、保存广播收敛；
- B4：manifest 内容指纹 + unchanged 回包；
- Mixin 从 49 个降到 34 个后又因 1.7.7/1.7.9 新功能回到 50 个，但已无自类 mixin。

本次表格只针对**仍然存在或新引入**的问题。

---

## 2. 优化表

优先级定义：

- **P0**：会崩溃、会丢数据、或主线程秒级卡顿/无界内存，建议下个 patch 就修；
- **P1**：高收益，建议 1.7.10；
- **P2**：中期，收益明确但改动面较大；
- **P3**：可选/清理项。

### 2.1 体积（jar 与仓库）

| ID | 优先级 | 问题与证据 | 建议 | 预计收益 | 风险/工作量 | 验证 |
|---|---|---|---|---|---|---|
| S1 | P1 | 18 个 BBS locale 共 117,627 B；16 个非英文文件中，15 个有 80–81/86 条与 `en_us` 完全相同，`zh_tw` 46/90 条相同。等于把英文兜底抄了 16 份。`L10n` 先加载 `en_us` 再加载目标语言，缺键会自动回退（`L10n.java:149-154`、`load()` 覆盖逻辑）。 | 保留 `en_us` 全量；其它语言只保留与 `en_us` 不同的键。用脚本生成并做 JSON 校验。 | 原始 **−90.8 KB**；jar 约 **−22 KB（−3.3%）**。`zh_cn` 几乎不删，`zh_tw` 保留 44 条。 | 低；只需生成脚本 + 语言切换 smoke test。 | 裁剪前后键集合对比；de_de/ar_ar/zh_tw 各切一次语言，确认缺键显示英文而不是 key 名。 |
| S2 | P2 | 编译默认带 `LineNumberTable` + `LocalVariableTable`（`javap` 可见）；发布 jar 不需要局部变量表。 | 发布构建加 `options.debugOptions.debugLevel = 'source,lines'`；开发构建保留全量。 | 约 **−2%～−5% jar（13～33 KB）**。 | 低；代价是发布包没有局部变量调试信息，行号仍在。 | 对比 jar 大小；用一个故意异常确认堆栈仍有行号。 |
| S3 | P2 | 50 处 `printStackTrace`、43 处 `System.out.println`；部分字符串很长（例如 `handleSetOperator`、上传拒绝提示）。`UIReplayListMixin:146-156` 还有两个被注释禁用的 `handleSwap` 方法。 | 统一走 `LOGGER`/debug 开关；删死方法与仅用于日志的长字符串；保留必要的管理员审计输出。 | 约 **−5～12 KB**，并减少控制台 I/O。 | 低；注意不要删行为日志（`BehaviorLogManager` 的审计是功能）。 | 编译；检查日志级别；确认行为面板仍记录。 |
| S4 | P2 | 多处“向每个收件人重新构造同一个 buffer/序列化同一个对象”的循环（见 P8/P2）。抽公共 helper 后代码会明显变短。 | 增加 `broadcast(identifier, writer)` / `sendToBranch(...)` helper；同一 payload 只序列化一次。 | 约 **−8～20 KB**，同时降 CPU/GC。 | 中；需要逐个替换并验证收件人过滤语义。 | 双人/三人同分支、跨分支各测一次。 |
| S5 | P3 | Gradle `jar` 使用默认 DEFLATE 级别；当前 entry 压缩后 608,517 B。 | 若追求极限，可在发布后处理用 `7z -mx=9` 重压缩；不建议改 Loom 任务。 | 约 **−1%～−3%**。 | 低收益、增加发布步骤；不利于可复现构建。 | 解压/再压缩后跑一次启动 smoke test。 |
| S6 | P3 | `dist/` 22 个 `.bak` 共 8.6 MB，最老的 1.7.4 备份链已无回滚价值。 | 只保留最近 2～3 个版本备份；旧版本移到外部归档。 | 工作区 **−7 MB 左右**（不进 jar）。 | 低；确认不再需要回滚到 1.7.4。 | 列出保留清单。 |
| S7 | P3 | `lib/bbs-fs-2.6-1.20.1/src` 约 23.7 MB 被 git 跟踪；`lib/bbs-fs-2.7` 约 24.2 MB；两者合计占 tracked 49.65 MB 的绝大部分。 | 若确定只支持 2.7：删除 2.6 源码树或移到外部压缩包；若仍要支持 2.6：保留但改为子模块/发布包。 | tracked **−24 MB**（仓库减半）。 | 中；会失去 2.6 对照源码。先确认 X2。 | `git ls-files lib` 对比；2.6 构建任务是否还需要。 |
| S8 | P3 | `run` 336.7 MB、`.gradle` 89.4 MB、`build` 3.1 MB、`.crash-report` 1 MB；都是 ignored，但占磁盘。 | `gradlew clean` + 删除 `.gradle`（会重新下载缓存）+ 备份/移走 `run` 世界；不要直接删用户测试世界。 | 工作区 **−300 MB 以上**。 | 低（但 `run` 可能含测试影片/世界，先备份）。 | 确认 `run/saves` 已备份。 |

> 体积项全部做完，jar 现实目标约 **600–620 KB**；若再叠加 S4/S5 可到 **590 KB 左右**，与 1.7.7 的 590,991 B 接近，但功能更多。

### 2.2 服务端 CPU / 内存（主线程与堆）

| ID | 优先级 | 问题与证据 | 建议 | 预计收益 | 风险/工作量 | 验证 |
|---|---|---|---|---|---|---|
| P1 | **P0** | **每个 1 MB 资产分片都被 gzip**：`PacketCompression.COMPRESS_THRESHOLD = 1 MiB`（`PacketCompression.java:21`），而 `asset_send_batch_kb` 默认 1024 KiB（`TogetherSettings.java:65`），整片大小恰好 `>=` 阈值，必然进入 gzip。PNG/OGG/MP4 等已压缩内容通常压不小，仍要付 gzip CPU + 1 MB 临时拷贝。`CompressedServerPacketCrusher.send` 在服务端主线程执行（`CompressedServerPacketCrusher.java:16`）。 | 给资产分片走 **`sendRaw`/`compress=false`**；或按扩展名/内容类型决定（图片/音频/视频跳过，JSON/模型文本保留）；影片/manifest 仍 AUTO。 | 每 1 MB 分片省约 **10–30 ms CPU + 1 MB 分配**；80 MB 资产约省 **0.8–2.4 s CPU**，并消除主线程尖峰。 | 低；新增一个重载/枚举，协议不变（接收端只认 flag）。 | 用 80 MB 模型包下载，对比 tick 时间与 jar 传输耗时；确认接收端 hash 一致。 |
| P2 | **P0** | **同分支 N 个玩家，影片被序列化 + gzip N 次**：`broadcastFilmSaved` 对每个 interested 收件人调用 `broadcastFilmSavedToAll`（`TogetherServerNetwork.java:1620-1638`），每次都走 `crusher.send(BaseType)` → `writeToBytes` + `encode`。 | 先序列化/压缩一次，再用 `crusher.send(Collection<PlayerEntity>, ...)` 一次发给所有同分支收件人；consumer 尾部字段相同。 | 序列化/压缩次数 **N→1**；20 MB 影片每多一个同分支玩家约省 **100–300 ms 主线程**。 | 低；`PacketCrusher.send(Collection)` 已存在；注意 `PacketByteBuf` 复用。 | 3 人同分支同时保存 20 MB 影片，测服务器 tick；确认 3 人都收到。 |
| P3 | **P0** | **进服时逐部影片在主线程 SHA-1**：`handleFilmInventory` 在后台线程读 inventory，但对每部影片 `server.execute` 一个任务，并在任务里 `sha1OfPathCached(file)`（`TogetherServerNetwork.java:1401-1445`，尤其 1410/1422）。首次进服（缓存为空）会把所有默认分支影片读一遍。 | 在后台线程完成全部 `sha1`/比较，收集结果；主线程只做一次发送与状态更新。顺带跳过非默认分支、空文件。 | 消除进服数秒 tick 卡顿；50 部 × 5 MB ≈ **250 MB 读+哈希移出主线程**。 | 低；注意 `BBSMod.getFilms()` 的线程安全（只读 keys/file 列表）；发送仍回主线程。 | 50 部影片首次进服，测 `debug` tick；对比改造前后 MSPT。 |
| P4 | **P0** | **整部影片快照/工作副本无上限**：`lastSavedFilmSnapshots` 每个 `film\tbranch` 存一份完整 `MapType`（`TogetherServerNetwork.java:190`、1683-1689、2964、3049）；`FilmBranchStore.WORKING` 每个 `film→branch` 存一份解析后的 `Film`（`FilmBranchStore.java:44`、233、273），只有关服才 clear。 | ① 当某 film 没有玩家在编辑/打开时，清掉它的 snapshot 与 WORKING；② 或做 LRU（如 8 个 branch）；③ 基线改为“路径→hash”紧凑结构，不存整片。 | 20 部 × 3 分支 × 10 MB ≈ **600 MB → 可压到 2–4 份（约 40 MB）**；同时减少 GC 停顿。 | 中；要保证清掉后能从磁盘重建，且 dirty 数据先落盘。 | 20 部影片、每部 3 分支来回切换，2 小时后看 heap（GC 后）；确认再次打开内容正确。 |
| P5 | P1 | **manifest 每次请求全树遍历 + 全量指纹**：`sendAssetManifest` 每次新建线程、`walkFileTree` 全目录、拼 `path=sha1:size:mtime` 大字符串、再 SHA-1（`TogetherServerNetwork.java:863-913`）。多个玩家同时进服会各跑一遍。 | 全局缓存 `{manifest, fingerprint, builtAt}`；单飞构建；上传/删除时失效；或按目录 mtime 粗粒度失效。 | M 个玩家同时进服从 **M 次全树遍历 → 1 次**；10k 文件可省每请求几十到数百 ms 后台 CPU。 | 低；缓存失效要覆盖外部直接改文件（可加 30–60 s TTL 兜底）。 | 3 人同时进服；外部新增文件后下一次 manifest 必须出现。 |
| P6 | P1 | **分片读取在服务端主线程**：`sendTaskInBatches` 对每个 slice `server.execute`，在任务里 `readSlice` 打开 `FileChannel` 读 1 MB（`TogetherServerNetwork.java:1222-1245`、1260-1276）。 | 在 drain 线程读好 `byte[]`，主线程只负责 `crusher.send`；或每 tick 发多片，减少 `execute` 次数。 | 每 1 MB 约省 **1–5 ms 主线程**；80 MB 下载约 **80–400 ms** 主线程时间。 | 低/中；注意并发读同一文件与删除/替换的竞态（可先 stat/size）。 | 80 MB 单文件下载，测 MSPT；文件被替换时不能读错。 |
| P7 | P1 | **光标广播 O(N²) + 每收件人 synchronized 查询**：`handleCursorPosition` 遍历所有玩家，对每人调 `sameBranch` → `FilmBranchStore.branchOf` → `FilmCommitStore.get`（`TogetherServerNetwork.java:649-672`）。10 人约 2k 次/s，50 人约 50k 次/s，且都在全局锁上。 | 维护 `film → branch → Set<UUID>` 在线索引；`setBranch`/进出影片/断线时更新；光标只遍历同分支集合。 | 大 N 下 CPU/锁竞争显著下降；光标路径不再随 N² 增长。 | 中；索引要与现有 `playerEditingFilm`、断线清理保持一致。 | 10/50 人压测（可用假客户端）；确认跨分支不可见。 |
| P8 | P2 | **同一 payload 按收件人重复构造**：`handlePlayerColor`（588-600）、`broadcastFilmSelections`（720-729）、`broadcastFilmScreens`（1501-1510）都在循环里新建/写同一个 buffer。 | 循环外构造一次，循环内只 `ServerPlayNetworking.send`。 | 每次广播少 N−1 次分配/写入；GC 更平稳。 | 低。 | 多人切换影片/颜色；抓包确认内容一致。 |
| P9 | P1 | **提交一次做三遍全量**：`commit` 先 `BaseType.equals(headData, data)` 深比较（`FilmCommitStore.java:604-610`），`write` 里 `FilmDiffBuilder.diff` 全量（995），再 `DataStorageUtils.writeToBytes(data)` 全量（1021）。 | 先序列化一次得到 `bytes`，用长度+SHA-256 判等；diff 复用同一棵树；snapshot 直接写 `bytes`。 | 大影片提交 CPU 约 **−20%～−40%**；50 MB 影片可省 100–300 ms 主线程。 | 中；hash 判等要保留“完全相同才拒绝”的语义；碰撞概率可忽略。 | 提交相同内容应拒绝；提交修改内容应产生正确 diff；大影片计时。 |
| P10 | P2 | **提交图索引每次全树扫描**：`FilmCommitStore.index()` 递归遍历 films 树并对每部 `get(filmId)` 加载 index（`FilmCommitStore.java:408-476`）；每次打开提交页都会走。 | 缓存 index，按 films 根目录/各 `_together/index.bin` mtime 失效；或服务端启动时构建一次，变更时增量更新。 | 100 部 × 300 提交的重复解析避免；打开提交页从数百 ms 降到 <50 ms（估算）。 | 中；失效条件要覆盖新增/删除/重命名/迁移。 | 100 部影片打开提交页；新增一部后应出现。 |
| P11 | P1 | **行为列表全量重发**：`query` 在 `clientVersion != version` 时 `full=true`，把最多 1000 条全部序列化（`BehaviorLogManager.java:406-430`）；而 `version` 每次 `record` 都 +1，面板每 3 s 拉一次（`UIServerBehaviorsPanel.java:41`）。活跃编辑时几乎每次都是全量。 | 增加 per-entry `updatedVersion` 或服务端变更日志；客户端带 `afterId` + `sinceVersion`，只回变更/删除的 id；合并条目单独标记。 | 活跃时面板流量 **−90%+**；1000 条 × 3 s 的全量包消失。 | 中；要处理 merge/evict/rollback 导致的删除语义。 | 开面板连续编辑 1 分钟，抓包对比字节数；列表仍实时更新。 |
| P12 | P1 | **分支保存序列化两次**：`FilmBranchStore.save` 先 `film.toData()` + `writeToBytes` 算 digest（`FilmBranchStore.java:353-358`），默认分支又调 `BBSMod.getFilms().save(filmId, data)`，BBS 再序列化+gzip（380）。 | 增加“已知 bytes”快速路径，或至少复用同一 `MapType`；默认分支直接写主 `.dat` 与 `w/<hash>.bin` 同一份 bytes（需 BBS 提供 bytes 写入口时最理想）。 | 每次保存少一次全量序列化；50 MB 影片约 **50–150 ms 主线程**。 | 中；依赖 BBS 存储 API；不改 BBS 时只能省一次 `toData`。 | 大影片保存计时；主 `.dat` 与工作副本内容一致。 |
| P13 | P3 | `sweepUploadAggregates` 在每个 server tick 都复制 `pendingUploadAggregates` 的 keySet（`TogetherServerNetwork.java:2366-2377`）。 | 改成 `ScheduledExecutorService` 每 500 ms 扫一次；或只在有 aggregate 时调度。 | 少量分配；tick 更干净。 | 低。 | 上传聚合仍能 3 s 内落账。 |
| P14 | P3 | `enqueueAssetSend` 为每个玩家创建一个 drain 线程（`TogetherServerNetwork.java:1178-1201`），空闲线程不回收。 | 用共享 `ScheduledExecutorService` + 每玩家串行链；或线程池 + 每玩家锁。 | 线程数从“玩家数”降到固定 2–4；上下文切换减少。 | 中；要保留每玩家节奏与背压。 | 10 人同时下载，jstack 看线程数；顺序与限速不变。 |
| P15 | P2 | `readSlice` 的 `while (buffer.hasRemaining() && (read = ch.read(...)) >= 0)` 在 `read==0` 时会空转；且每个 slice 重新开 `FileChannel`（`TogetherServerNetwork.java:1260-1276`）。 | `read==0` 时让出/重试；每任务复用一个 channel，或 `FileChannel.read` 循环带小睡。 | 避免极端情况下主线程忙等；减少 open/close。 | 低。 | 人为制造慢磁盘/并发删除，确认不空转。 |
| P16 | P2 | 合作录制收尾先对每个参与者 `broadcastFilmSavedToAll`，再 `broadcastFilmSaved`（`CoopSessionManager.java:626-634`），可能重复发同一部影片。 | 合并收件人集合，去重后一次 collection send。 | 收尾时少发 K 份全量影片。 | 低。 | 3 人合作录制，确认每人都收到且只收一次语义（重复包不致命，但浪费）。 |

### 2.3 客户端帧率 / 内存

| ID | 优先级 | 问题与证据 | 建议 | 预计收益 | 风险/工作量 | 验证 |
|---|---|---|---|---|---|---|
| C1 | **P0** | **提交图每帧 O(n²)**：`renderEdges` 对每个节点遍历父边（`UICommitGraph.java:656-677`）；`segment` 为每条边再扫描**全部节点**找遮挡带并排序（685-733）。300 节点约 90k 次/帧；多影片 1000 节点约 1M 次/帧，还伴随 `new int[]` 分配。 | 预计算每条固定 x/y 线上的遮挡区间（布局时缓存）；按视口裁剪不可见行/边；`segment` 不再分配数组。 | 300 节点从 ~90k/帧降到可见行×父边（通常 <5k）；1000 节点从 ~1M/帧降到 <20k。 | 中；要保证连线截断视觉不变。 | 300/1000 节点 F3 看帧时间；截图对比连线。 |
| C2 | P1 | **每帧为每个节点分配 watcher 列表**：`renderNodes` 对每个可见节点调 `watchers()`，内部 `commitWatchers` + `commitPositions` 各新建列表，再合并去重（`UICommitGraph.java:823-884`；`TogetherClientNetwork.java:3090-3125`）。 | 在 `c_commit_selections` / `c_player_commit_positions` 到达时构建反向索引 `film→commit→List<UUID>`；渲染只读。 | 每帧从数百次 list 分配降到 0；大图 GC 明显下降。 | 低/中；索引随选择/位置包更新。 | 10 人选中同一影片，开提交图看分配/帧时间。 |
| C3 | P2 | `renderNodes` 每帧做 `shortId + message` 拼接，并对每条文本跑 `fit()` 逐字符 `getWidth`（`UICommitGraph.java:794`、1034-1047）；header 每帧拼 `"· " + branch`（597）。 | 文本/宽度按 `(commitId, width)` 缓存；branch 字符串缓存；`fit` 二分而不是逐字符。 | 每帧少几百次字符串/字体测量；大图更顺。 | 低。 | 300 节点帧时间；文本截断视觉一致。 |
| C4 | P1 | **未提交改动每秒在渲染线程全量 diff**：`UICommitInfo.render` 当编辑版本变化且距上次 >1 s 时 `refresh()`（`UICommitInfo.java:519-532`），`buildWorking` 做 `film.toData()` + `FilmDiffBuilder.diff` 全量（242）。大影片会每秒卡 0.2–1 s。 | 主线程只抓一份不可变快照（或序列化 bytes），diff 放后台线程；完成后 `client.execute` 换 `lines`；同一时间只允许一个 diff。 | 消除大影片每秒一次的渲染线程长卡顿；提交面板保持可交互。 | 中；要注意 `Film`/`MapType` 的线程安全，快照后只读。 | 50 MB 影片编辑时开提交页，记录帧时间；确认 diff 内容正确。 |
| C5 | P1 | **一次同步走多遍资产树**：manifest 处理里 `Files.walk` 做下载/上传 diff（`TogetherClientNetwork.java:1209`），结束又 `sendAssetInventory` 走一遍（2213）；`purgeStaleTransferTemps`（2143）和 `AssetAutoSyncWatcher.registerAll`（125）在进服时也各走一遍。680 文件/82 MB 时还能接受，上万文件时进服明显变慢。 | 建一个 `AssetIndex`（path→size,mtime,sha1），一次扫描供 diff/inventory/清理/监听复用；后续增量更新。 | 进服全树 walk 从 3–4 次降到 1 次；上万文件时省数秒磁盘 I/O。 | 中；缓存一致性（外部改动）要处理。 | 10k 文件进服计时；外部改文件后能上传。 |
| C6 | P1 | **影片清单每次全量 SHA-1**：`sendFilmInventory` 对 `BBSMod.getFilms().getKeys()` 每部 `sha1OfLocalFile`，没有 `(mtime,size)` 缓存（`TogetherClientNetwork.java:2281-2309`）；进服和影片列表变化都会触发。 | 复用/新增影片文件 hash 缓存；或直接使用服务端 inventory 对比结果，客户端只报 mtime/size。 | 50 部 × 5 MB 进服少读 250 MB；进服更快。 | 低/中；影片保存后 mtime 变化要失效。 | 50 部影片进服计时；保存一部后下次应上传。 |
| C7 | P2 | 每次回放选择/重进都用 `new Thread` + `Thread.sleep` 延迟重发（`BBSTogetherFix33Client.java:85-104`，一次开影片 3 个线程；`BBSTogetherFix28Client.java:264-284`）。 | 用一个共享 `ScheduledExecutorService`，按 filmId 去重/取消旧任务。 | 线程 churn 降低；重复包减少。 | 低。 | 快速开关影片 20 次，jstack 看线程峰值；选择状态正确。 |
| C8 | P3 | `BBSTogetherFix28Client.getClientPacketCrusher()` 每次反射取静态字段（286-290）。 | 首次反射后缓存 `ClientPacketCrusher` 引用。 | 每次发包少一次反射查找。 | 低。 | 功能回归。 |
| C9 | P1 | **BBS 基类重组分片时双缓冲**：`PacketCrusher.receive` 每个 chunk `new byte[size]`，`ByteArrayOutputStream(total*30 KB)` 预分配，最后 `toByteArray()` 再复制（`lib/.../PacketCrusher.java:30-55`）。100 MB 载荷峰值约 200 MB；且 `chunks` 没有超时/上限，客户端中途丢包会永久占住半包内存（`TogetherClientNetwork` 的 `crusher` 是全局单例）。 | 在 `CompressedClientPacketCrusher`/`CompressedServerPacketCrusher` 重写 `receive`：按 `total*BUFFER_SIZE` 预分配一次 `byte[]`，`buf.readBytes(target, offset, size)` 直接写入；加 60 s 超时/最大在途字节；不完整包定时清理。 | 100 MB 载荷峰值 **~200 MB → ~100 MB**；去掉每 chunk 分配与最终复制；消除半包泄漏。 | 中；要兼容空 payload 哨兵 `{69}` 与现有 header；测试大包/丢包。 | 100 MB 影片传输时看 heap 峰值；模拟丢一个 chunk，60 s 后内存释放。 |
| C10 | P2 | `PacketCompression.encode` 对不需要压缩的小包也复制一份 `new byte[len+1]`（`PacketCompression.java:60-64`）；光标/颜色包每次都多一次分配。 | 在 crusher 写 chunk 时先写 flag，再写原 bytes；或提供 `encodeInto`。 | 每个小包少一次 byte[] 分配；高频光标路径更省 GC。 | 中；要改 crusher 的 buffer 写法。 | 10 人光标压测，分配率对比。 |
| C11 | P2 | 客户端 `filmCache` 最多 8 部完整 `MapType`（`TogetherClientNetwork.java:124-134`）；大影片 8×50 MB=400 MB。 | 降到 3–4 部，或按总字节数限制；缓存压缩后的 bytes，打开时再解析。 | 客户端堆上限可控；减少 OOM 风险。 | 低/中；重开影片可能多一点解析时间。 | 连续打开 10 部大影片，看 heap；重开速度可接受。 |

### 2.4 正确性 / 兼容性 / 发布

| ID | 优先级 | 问题与证据 | 建议 | 预计收益 | 风险/工作量 | 验证 |
|---|---|---|---|---|---|---|
| X1 | **P0** | **新旧版本包格式不兼容**：`c_cursor_positions` 新版在开头多一个 `boolean full`，客户端无保护地 `readBoolean()` 再 `readInt()`（`TogetherClientNetwork.java:766-768`）；旧服务端空快照只有 4 字节，会 `IndexOutOfBoundsException`（历史日志已出现过）。`c_replay_selections` 末尾新增 `colorCount` 也是无保护读取（821-835）。 | ① 不改旧 channel 的字节布局；新增 `c_cursor_positions_v2` / `c_replay_selections_v2`，客户端两个都注册，旧服务端走旧解析；② 或加 `c_protocol` 握手，按版本选择；③ 所有新字段读取加 `readableBytes()` 保护 + try/catch 丢弃坏包。 | 1.7.9 客户端可连未升级的 1.7.7/1.7.8 服务端而不崩；升级顺序不再强制“同一天全服换”。 | 中；要同时保留旧/新发送路径，服务端按 `canSend` 选择。 | 版本矩阵：新 client × 1.7.7/1.7.8/1.7.9 server；旧 client × 新 server；空光标/空选择/正常数据各测。 |
| X2 | P1 | `fabric.mod.json:39` 声明 `"bbs": ">=2.6"`，但 `gradle.properties:13` 是 `2.7-1.20.1`，且多处按 2.7 编译/注入（`ServerNetworkBbsOpMixin.java:60-62` 只对 2.6 做特判）。 | 二选一：① 明确只支持 2.7，把依赖改为 `>=2.7`；② 保留 2.6，增加 2.6 构建/启动 smoke test，并逐一核对 2.7 API。 | 避免 2.6 用户安装后 `NoSuchMethodError`/mixin 失败；元数据与真实支持范围一致。 | 低（改元数据）/中（真支持 2.6）。 | 2.6、2.7 各启动一次；`/bbstogether diag`；mixin 无 "Scanned 0 target"。 |
| X3 | **P0（发布）** | 构建 1.7.9，`dist/` 最新 1.7.6，用户实例 1.7.7；README 说“客户端与服务端必须同版本”。 | 发布 1.7.9 时同时更新 `dist/`、测试实例、远程服务端；保留 2 个 `.bak`；在 README 写清升级顺序。 | 避免混版本崩溃/资产异常；用户拿到的就是验证过的 jar。 | 低；流程问题。 | 校验 sha1/大小；客户端/服务端 `/bbstogether diag` 版本一致。 |
| X4 | P1 | 三个元数据文件非原子写：`BehaviorLogManager.java:688`、`AssetLockManager.java:579`、`BbsOpManager.java:452` 直接 `Files.write`/`FileWriter` 覆盖；崩溃/断电可能留下半截 JSON/BIN。 | 统一 `temp file + Files.move(ATOMIC_MOVE)`；写前备份上一版。 | 避免权限/锁/审计数据损坏导致启动回退或丢失。 | 低。 | 写入时强杀进程，重启后文件可解析或回退到备份。 |
| X5 | P1 | `AssetLockManager.java:449`、`BbsOpManager.java:346` 用 `FileReader` 默认平台编码；Java 17 在中文 Windows 上可能是 GBK，玩家名/资产名会乱码；写端 `FileWriter` 同样。 | 统一 `StandardCharsets.UTF_8`；读写都显式指定。 | 跨平台/跨 JVM 编码一致；中文名不乱码。 | 低。 | 用中文玩家名/资产名写入后重启读取。 |
| X6 | P1 | 上传路径校验偏弱：`AssetPaths.isPublishable` 只查 `..`、开头 `/`、临时后缀（`AssetPaths.java:26-32`）；`handleAssetUpload` 也只查 `..` 和开头 `/`（`TogetherServerNetwork.java:1001-1003`），未拒绝 Windows 盘符/反斜杠/绝对路径；`seq/seqTotal` 未做上下界与一致性校验。 | 统一 `resolve + normalize + startsWith(assetsRoot)`；拒绝 `:`、`\`、空段；`seqTotal` 限制 1–4096、`seq` 在范围内、`totalSize` 与分片累计一致。 | 防止路径逃逸/异常写盘；降低恶意或损坏包的影响。 | 低/中；注意合法 Windows 文件名。 | 单测恶意路径（`C:/x`、`..\x`、`a/../../x`）；正常上传回归。 |
| X7 | P2 | `TogetherClientNetwork.setup()` 无条件 `isBBSTogetherOnServer = true`（473），未检测服务端是否安装/是否支持该通道；连 vanilla/无 mod 服务端仍会发 sync/manifest/film inventory。 | 用 `ClientPlayNetworking.canSend(...)` 或握手包判断；不支持时关闭相关 UI/自动同步。 | 减少无谓发送/日志；对无 mod 服务端更友好。 | 低。 | 连 vanilla 服务端，确认无报错/无卡顿。 |
| X8 | P2 | 多处 count 直接来自包且无上限：光标 `selCount`（635-639）、资产请求 `count`（945-949）、清单 keys 等。 | 统一上限（如 selection 4096、请求 4096、清单 200k）并校验剩余字节。 | 防大包/恶意包导致超大分配。 | 低。 | 构造超大 count 包，确认被拒绝而非 OOM。 |
| X9 | P2 | 没有协议版本号/能力位；靠“同版本”约定。 | 增加 `c_protocol`（int version + 能力 bitmask）在 join 时下发；客户端据此隐藏/禁用不支持的功能。 | 为 X1 提供长期方案；后续功能可灰度。 | 中；要兼容旧服务端（无包时按旧版处理）。 | 新旧组合矩阵。 |
| X10 | P1 | `TogetherSettings.java:20-21` 注册了 `cdnUrl`/`cdnToken`，但全项目没有任何读取；F6 的 CDN 按钮读的是 BBS 自己的 `BBSSettings.cdnUrl/cdnToken`（`UIUtilityOverlayPanel.java:135,170,175`）。设置页存在两套同名“CDN 地址/令牌”。 | 要么实现 Together 的 CDN 传输（见第 3 节），要么把 Together 的这两项标为“未启用/预留”或从 UI 移除；同时给 F6 的 CDN 加“BBS 自带，与 Together 同步无关”的提示。 | 消除“填了没效果/填错一套”的困惑；减少支持成本。 | 低。 | 设置页文案/行为一致；两套配置各填一次确认哪个生效。 |
| X11 | **P0（误删/覆盖风险）** | F6 的 CDN 上传是 BBS 的整树 LWW 同步：`pushChangedFiles` 会删除所有“CDN 有、本地没有”的远端文件（`CDNAssetSyncService.java:273-293`）；它不知道 Together 的 `asset_sync_max_mb` 超限占位文件，也不过滤 `.bbspart`/`.bbsup`。因此：本地资产不全、超限占位、传输中的临时文件都可能让 F6 上传**删掉或覆盖 CDN 上的真文件**。F6 下载也会直接 `Files.copy` 到目标（`:220-223`），无临时文件/原子替换/哈希校验，失败会留半文件。 | 短期给 F6 上传加保护：① 本地没有的远端路径默认不删；② 跳过 Together 临时文件、跳过超限占位（`OversizedAssets`/同尺寸占位检测）；③ 先备份/确认清单再删。中期把 F6 的 CDN 按钮改为调用第 3 节统一的 `AssetTransport`/发布任务，不再直接全树 LWW。 | 避免一次误点清空 CDN 资产；避免占位文件覆盖真模型。 | 中；需要产品确认“CDN 是镜像还是可写源”。 | 空本地 assets 点 F6 上传，CDN 不得减少；有超限占位的模型点上传，CDN 原文件 hash 不变；下载中断后本地不应出现被当作正式资产的文件。 |
| X12 | P1 | F6 CDN 与 Together 自动同步没有互斥：F6 下载直接写 `assets/`，`AssetAutoSyncWatcher` 只过滤 Together 自己的写（`SelfWriteGuard`，`AssetAutoSyncWatcher.java:109`），会把 F6 下载当成“本地编辑”；随后 Together 可能把 CDN 内容上传到游戏服，覆盖服务端权威版本。反向也一样：Together 同步/上传期间点 F6 上传，可能把半套/临时文件推到 CDN。 | ① F6 CDN 写入后通知 Together 标记为 self-write 并触发一次“只下载不反向上传”的校准；② 或两者共用同一个 `AssetIndex` 锁/单飞；③ 最省事：Together 启用时禁用 F6 的 CDN 按钮，或反过来。 | 避免 CDN 旧内容被自动同步“洗”进游戏服；避免并发写同一资产。 | 中；要理清谁是权威。 | 先在服务端放 A，CDN 放 B；F6 下载 B 后确认不会自动把 B 上传覆盖 A；并发点两边按钮不产生半文件。 |

### 2.5 工程 / 构建 / 可维护性

| ID | 优先级 | 问题与证据 | 建议 | 预计收益 | 风险/工作量 | 验证 |
|---|---|---|---|---|---|---|
| E1 | P2 | `build.gradle:2` 使用 `fabric-loom 1.15-SNAPSHOT`；快照会漂移，构建不可复现。日志显示实际解析到 1.15.5。 | 固定为 `1.15.5`（或团队验证过的版本）；定期手动升级。 | 构建可复现；避免某天 snapshot 变更导致编译/remap 差异。 | 低。 | 两次 clean build 产物 sha256 一致（除时间戳）。 |
| E2 | P3 | `gradle.properties` 未启用 configuration cache；每次配置阶段仍有开销。 | 试启 `org.gradle.configuration-cache=true`；Loom 若不兼容则保持。 | 构建配置阶段约 −20%～−40%（项目规模小，收益有限）。 | 低；可能有插件不兼容。 | `gradlew build` 两次；配置缓存报告无问题。 |
| E3 | P1 | 目前只有人工构建；JSON/locale/jar 体积/mixin 注册没有自动检查。 | 加一个 Gradle `check` 任务：JSON 可解析、所有 `CoopL10n` 静态键存在、jar entry 数/大小预算、mixin 源文件与 json 一一对应。 | 防止 S1/S3 误删、体积回涨、mixin 漏注册。 | 低。 | CI 跑 `gradlew check`；故意删一个键应失败。 |
| E4 | P2 | `test` 为 `NO-SOURCE`；关键路径（路径校验、diff、commit id、packet 编解码）没有单测。 | 先给纯逻辑类加 JUnit：`FilmDiffBuilder`、`FilmCommitStore.hash`、`AssetPaths`、`PacketCompression`、`TokenBucket`。 | 回归成本下降；优化 P9/C9 时更有底。 | 中；需要引入测试依赖/源集。 | `gradlew test` 通过。 |
| E5 | P2 | BBS 依赖走 `mavenLocal()`（`build.gradle:22-24`），新机器没有本地发布就构建失败。 | 在 README/CI 写清 `publishToMavenLocal` 步骤；或把 BBS jar 放 `libs/` 用 `flatDir`（注意许可证/体积）。 | 新人/CI 可复现。 | 低。 | 干净环境按文档构建。 |
| E6 | P3 | `README.md` 的“当前版本/配置表/CDN 预留”与代码状态有偏差（见 X2/X3/X10）。 | 发布时同步更新版本、依赖范围、CDN 状态；加“混版本会怎样”的说明。 | 减少用户误配。 | 低。 | 文档 review。 |

---

## 3. CDN 适配：把 1.7.9 的资产同步能力接到“未改动的 CDN 内容”上

### 3.1 现在到底有什么

**BBS 2.7 的 CDN 契约**（`lib/bbs-fs-2.7-1.20.1/src/client/java/mchorse/bbs_mod/utils/resources/CDNAssetSyncService.java`）：

| 接口 | 方法 | 请求 | 响应 | 现状 |
|---|---|---|---|---|
| 清单 | `GET /files` | 无参数 | `[{path,size,sha1}, ...]` | 无 mtime、无分页、无 ETag、无 gzip 协商 |
| 下载 | `POST /file` | `path=...`（form） | 整个文件字节 | 无 Range/断点、无临时文件 |
| 上传 | `POST /upload` | multipart `path` + `file`，头 `X-Token` | 200 | 客户端 `Files.readAllBytes` 整文件进内存 |
| 删除 | `POST /delete` | `path=...`（form），头 `X-Token` | 200/204/404 | 直接删，无 tombstone |

> **注意 F6 的 CDN 按钮**：截图里的「CDN / 下载 / 上传」是 BBS 2.7 自带的 `UIUtilityOverlayPanel`，不是 Together Link 的「下载全部模型/上传全部模型」。它读 `BBSSettings.cdnUrl/cdnToken`（`bbs.json`），而 Together 的 `cdnUrl/cdnToken` 在 `bbs_together.json`，两套配置互不相通。F6 的上传还是**整树 LWW**：会删除 CDN 上本地没有的文件；Together 的超限占位、`.bbspart`/`.bbsup` 临时文件、部分资产都可能触发误删/覆盖。详见 X11/X12。

### 3.1.1 设置里的两套「CDN 服务器」

| 设置位置 | 配置文件 | 谁读取 | 当前实际效果 |
|---|---|---|---|
| BBS 设置 → CDN → URL / Token | `config/bbs/settings/bbs.json` 的 `cdn.url` / `cdn.token` | F6 的 CDN 下载/上传（`UIUtilityOverlayPanel.java:135,170,175`）→ `CDNAssetSyncService` | **只影响 F6**；整树 LWW；Together 的 manifest/自动同步/权限完全不走它 |
| Together Link 设置 → 资源同步 → CDN 地址 / 访问令牌 | `config/bbs/settings/bbs_together.json` 的 `cdn.url` / `cdn.token` | **没有任何读取点** | **完全没接线**；填了等于没填 |
| Together Link 设置 → 资源同步 → 启用下载/上传/接收推送/进度条 | 同一个 `bbs_together.json` 的 `cdn.download/upload/receive/progress_bar` | `TogetherSettings` + 资产同步代码 | 只控制**游戏服包通道**的同步，和 CDN 无关 |

**所以“设置中的 CDN 服务器”目前有三种填法，结果都不同：**

- **只填 BBS 的 URL/Token**：F6 能连 CDN；Together 的自动同步仍然走游戏服，CDN 不参与。用户会以为“设置了 CDN，Together 同步就会走 CDN”，实际不会。
- **只填 Together 的 URL/Token**：完全没效果；F6 仍然读 BBS 的空配置。
- **两套都填**：F6 写 CDN，Together 写游戏服，形成两个源；再叠加 F6 的整树删除和 Together 的 watcher，容易出现“CDN 旧内容被自动同步进游戏服”或“游戏服资产被 F6 删掉”的不一致。

**结论：有适配问题，而且不是“字段没对上”这么简单。** 需要先决定 CDN 是“只读镜像”还是“可写源”，再把两套设置合并成一套；在当前代码下，Together 的 CDN 字段是死配置，F6 的 CDN 是另一套独立实现，二者都没有接入 1.7.9 的 `sha1:size:mtime`、自动同步、权限/锁/tombstone/审计。

**Together Link 1.7.9 的资产能力**（已实现，走游戏服包通道）：

- 服务端权威清单：`sha1:size:mtime`，大小上限、临时文件过滤、内容指纹 `unchanged`；
- 客户端 diff：下载/上传单方向仲裁（mtime 容差 3 s）、自动同步、WatchService 本地监听；
- 传输：30 KB 分片、1 MB/4 MB 批次、GZip（>1 MB）、令牌桶限速、断线重试；
- 权限/安全：BBS 0/1/2 级、域白名单、资产锁、模型删除 tombstone、行为审计；
- 推送：`c_assets_changed` + 每玩家持有清单 `s_asset_report`/delta。

### 3.2 能力对照（未改动 CDN 时）

| 新能力 | 未改动 CDN 是否支持 | 不改 CDN 的适配做法 | 缺口/结论 |
|---|---|---|---|
| `sha1:size:mtime` 清单 | 只有 `path,size,sha1` | 适配器把 CDN 条目合成为 `sha1:size:0`；能从游戏服清单拿到 mtime 时用游戏服的；CDN-only 时 `mtime=0` | **mtime 冲突仲裁失效**；CDN-only 只能“服务器/CDN 胜”，不能用本地更新时间判断。 |
| 按哈希下载 | 支持（`/files` + `/file`） | 用 CDN 清单和游戏服清单取交集；只有 hash+size 都一致才从 CDN 下；否则回退游戏服 | **推荐主路径**：只读镜像/下载分流。 |
| 分片/断点续传 | 不支持（整文件） | 客户端流式写 `.bbspart`，边下边 SHA-1，完成后原子改名；失败整文件重试 | 无断点，大文件失败要重下；但不会把半个文件当正式资产。 |
| 上传 | 支持 multipart，但只有一个静态 `X-Token` | **上传仍走游戏服**（权限/锁/tombstone/审计都在那里）；CDN 上传只给服务器/运维的“发布任务”用 | 客户端直传 CDN 会绕过 BBS 等级 0/1/2、资产锁、tombstone、行为日志；**不建议**。 |
| 删除 | 支持 `/delete` | 只允许服务器/运维删除；客户端不直删；服务端保留 tombstone，CDN 发布时跳过 tombstone | 客户端直删可被滥用；CDN 无法区分“模型被删除”与“文件丢失”。 |
| 推送 | 不支持 | 保留游戏服 `c_assets_changed`；CDN-only 时按 TTL（如 60 s）轮询 `/files`，有 `ETag`/`Last-Modified` 就用条件请求 | 无游戏服时实时性下降；不阻塞进服。 |
| 大小上限 | 不支持（服务端不拦） | 客户端按 `asset_sync_max_mb` 过滤；服务端清单权威；CDN 只传已批准的路径 | CDN 侧无法阻止别人上传超大文件；需要发布任务侧校验。 |
| 权限/锁 | 不支持 | 游戏服在 manifest/URL 签发前校验；CDN 只服务“已批准”的 hash | 旧 CDN 没有 per-user token/签名；**无法做真正的按玩家授权**。 |
| 行为审计 | 不支持 | 下载/上传仍由游戏服记录；CDN 下载在客户端补报 `s_asset_report`/行为（可选） | 纯 CDN 流量无法在服务端精确审计；只能客户端自报。 |
| 进度/UI | 无 | 复用 `AssetSyncTracker`，HTTP 流式回调进度；失败回退不弹大错误 | 可行。 |
| 路径安全 | 弱：`assets.resolve(remotePath)` 直接拼接（`CDNAssetSyncService.java:158-159`、275-276） | 适配器 `normalize + reject .. / absolute / drive / backslash`，并校验 `startsWith(assetsRoot)` | **必须在客户端适配层修正**，不要直接复用 BBS 的路径解析。 |
| 内容未更新 | 旧文件可能缺/旧 | 服务器/运维做一次性 `publish`：按 sha1 补传缺失/变更；**不删除未知路径**；最后写 sidecar manifest | 避免把 CDN 上别人放的文件误删；避免旧内容被当权威。 |
| 配置语义 | `cdnUrl`/`cdnToken` 已注册但从未读取（`TogetherSettings.java:20-21`、49-51） | 实现适配器后启用；或在 UI 标“预留/未启用” | 目前填了没效果。 |
| F6 按钮归属 | BBS 自带，读 `BBSSettings.cdnUrl/cdnToken`（`UIUtilityOverlayPanel.java:135,170,175`），与 Together 配置互不相通 | 把 F6 标为“BBS 自带/CDN 直连”，或让 Together 也读同一套配置；长期统一到一个 transport | **用户最容易填错配置**；两套 CDN 语义不一致。 |
| F6 整树 LWW 删除 | `pushChangedFiles` 删除 CDN 上本地不存在的路径（`CDNAssetSyncService.java:273-293`） | 默认不删未知路径；只删“上次发布清单”中的路径；加发布确认 | **可能清空 CDN**；与 Together 的超限占位/临时文件叠加更危险。 |
| F6 与自动同步并发 | F6 直接写 `assets/`；Together 的 watcher 不识别 F6 写入（只有 `SelfWriteGuard` 过滤 Together 自己的写，`AssetAutoSyncWatcher.java:109`） | F6 写后标记 self-write 并只做下行校准；或 Together 启用时禁用 F6 按钮 | CDN 旧内容可能被自动上传到游戏服，覆盖权威版本。 |

### 3.3 推荐架构：服务器权威 + CDN 只读镜像

```
                    ┌──────────────────────────────┐
                    │  游戏服（权威）                │
                    │  - 权限/锁/tombstone/审计      │
                    │  - manifest: sha1:size:mtime  │
                    │  - c_assets_changed / 持有清单 │
                    └──────────────┬───────────────┘
                                   │ 控制面（小包）
                                   ▼
┌──────────────┐   1) manifest    ┌──────────────┐
│ 客户端        │◄────────────────►│ 游戏服        │
│              │   2) 选源         └──────────────┘
│  AssetSource │
│  ┌─────────┐ │   3a) 命中且 hash 一致   ┌──────────────┐
│  │ CDN     │ │◄─────────────────────────│ CDN 只读镜像  │
│  │ transport│ │      GET /file            │ /files /file │
│  └─────────┘ │                          └──────────────┘
│  ┌─────────┐ │   3b) 未命中/hash 不符/超时
│  │ Server  │ │◄───────────────────────── 回退游戏服分片通道
│  └─────────┘ │
└──────────────┘
```

**规则（建议直接写进实现）：**

1. **权威源永远是游戏服**：路径集合、sha1、size、mtime、权限、锁、tombstone 都以游戏服为准。CDN 只提供字节。
2. **CDN 下载前置条件**：`cdnUrl` 非空、`assetSyncDownload=true`、游戏服清单里该条目存在、CDN `/files` 的 `sha1+size` 与游戏服一致。任一不满足 → 回退游戏服通道。
3. **CDN 不参与写**：`/upload`、`/delete` 只由游戏服/运维的“发布任务”调用；客户端永远不拿可写 token。这样 BBS 等级、锁、tombstone、审计不会被绕过。
4. **下载校验**：流式写 `.bbspart`，边写边 SHA-1；完成后比对游戏服清单的 sha1；再 `ATOMIC_MOVE` 到目标。失败删除临时文件，回退游戏服。
5. **失败不阻塞**：CDN 连接超时（建议 10 s connect / 60 s read）、404、hash 不符都只记 debug，不弹大错误；下一次自动同步再试。
6. **清单缓存**：CDN `/files` 缓存 30–60 s；有 `ETag`/`Last-Modified` 用条件请求；没有就 TTL。不要每次进服都拉全量。
7. **发布任务**：服务器/运维侧执行；按 sha1 补传缺失/变更；**只删除“上次发布清单里存在且本次本地不存在”的路径**；未知路径一律不删；最后写 sidecar（见下）。
8. **sidecar（可选）**：在 CDN 写一个保留路径（如 `._bbs_together/manifest.json`），内容是 `[{path,size,sha1,mtime}]`；旧 CDN 只是存字节，不需要改。客户端优先用游戏服 manifest；没有游戏服时才读 sidecar；sidecar 不存在就退回 `/files` 的 `sha1:size:0`。**不要**让 sidecar 进入 BBS 资产目录/清单（`AssetPaths.isPublishable` 增加保留前缀过滤）。

**建议的代码落点（不现在改，只列设计）：**

- 新包 `mchorse.bbs_together.sync.transport`：
  - `AssetTransport`：`manifest()`, `download(path, consumer)`, `upload(path, bytes)`, `delete(path)`；
  - `ServerAssetTransport`：包装现有 crusher/包；
  - `CdnAssetTransport`：包装 `HttpClient`，实现 `/files`、`/file`、`/upload`、`/delete`；
  - `AssetSource`：按上述规则选源 + 回退；
  - `CdnPublisher`：服务器/运维发布任务（不放客户端）。
- `TogetherSettings` 增加 `cdn_mode`（off/mirror/readonly）或复用 `cdnUrl` 非空即启用只读镜像；默认 off，升级无感。
- `AssetSyncTracker` 增加字节进度回调（HTTP 流式）；`SyncBarRenderer` 不用改。
- 行为日志：CDN 下载成功后仍由客户端上报“下载”条目（可选，注明来源 CDN）。

### 3.4 如果一定要“双向 CDN”

只有在**CDN 服务端也能改**的前提下才建议；需要新增：

- per-user 短期签名 URL / token（服务端签发，校验 BBS 等级/锁/tombstone）；
- `mtime` 字段或 `HEAD` 的 `Last-Modified`；
- 分片/Range 或至少断点；
- 删除 tombstone / 审计回调；
- 服务端发布/回滚 API。

在“CDN 不改”约束下，这些做不到；强行双向会把现有权限模型打穿，不建议。

### 3.5 “CDN 内容还没更新”的一次性迁移步骤

1. 游戏服导出权威清单（path,size,sha1,mtime）。
2. `GET /files` 拿 CDN 现状。
3. 对每个权威条目：CDN 缺失或 sha1 不同 → `POST /upload`（服务器/运维执行，串行/限速）。
4. CDN 有、权威没有的路径：**默认不删**；只在“上次发布清单”里出现过的才删。
5. 全部成功后写 sidecar manifest（如启用）。
6. 客户端先按游戏服 manifest 校验；CDN 只做字节源；失败的条目自动回退游戏服。
7. 之后每次发布增量执行 2–5；发布任务记录日志（谁、何时、传了哪些 hash）。

---

## 4. 建议实施顺序

### 阶段 0：1.7.9.x 热修（1–2 天，低风险）

- **P1** 资产分片跳过 gzip（表 2.2 的 P1 项）
- **P2** 影片广播改 collection send（P2）
- **P3** 影片清单 SHA-1 移出主线程（P3）
- **P4** 影片快照/工作副本加上限或空闲清理（P4）
- **C1** 提交图边绘制裁剪 + 去分配（C1）
- **X1** 光标/回放包旧格式兼容（至少 try/catch + 长度保护；最好 v2 channel）
- **X11** F6 CDN 上传防误删/防占位覆盖（至少“本地没有的远端不删”+跳过临时/占位文件）
- **X3** 发布 1.7.9 到 dist/测试服/远程服（X3）
- **S1** locale 裁剪（可同批，纯资源）

预期：主线程秒级卡顿消失；多人同分支保存不再 N 倍 CPU；大提交图恢复帧率；旧服可连新客户端。

### 阶段 1：1.7.10（1 周）

- P5 manifest 缓存、P6 分片读取移出主线程、P7 光标分支索引、P9 提交单次序列化、P11 行为增量、P12 保存单次序列化；
- C2 watcher 反向索引、C4 未提交 diff 后台化、C5 资产一次扫描、C6 影片 hash 缓存、C9 crusher 重组；
- X2 依赖范围、X4 原子写、X5 编码、X6 路径校验、X9 协议握手；
- S2 发布调试信息、S3 日志/死代码、S4 广播 helper；
- E3 自动检查。

### 阶段 2：1.8 / CDN（2–4 周，按 CDN 确认后排期）

- CDN 只读镜像（`CdnAssetTransport` + `AssetSource` + 回退）；
- 发布任务/一次性迁移 + sidecar；
- P10/P13/P14/P15/P16、C3/C7/C8/C10/C11、S5/S6/S7/S8、E1/E2/E4/E5。

---

## 5. 验证清单（改代码前先定，改完逐项打勾）

**内容/构建**

- [ ] `gradlew clean build --no-build-cache` 成功；
- [ ] 所有 `src/**/*.json` 可解析；无重复 key；
- [ ] 50 个 mixin 源文件与两个 json 一一对应；`required/defaultRequire` 未被误改；
- [ ] 静态 `L10n.lang` 键都能在 `en_us.json` 找到（动态拼接的 `bbsop.*`/`behaviors.*` 前缀除外）；
- [ ] `CoopL10n.key` 静态键都能在 `assets/bbs_together/lang/en_us.json` 找到；
- [ ] jar 大小不超过约定预算（建议 620 KB；超过则 CI 失败）。

**混版本矩阵**

| 客户端 | 服务端 | 期望 |
|---|---|---|
| 1.7.9 | 1.7.9 | 全功能 |
| 1.7.9 | 1.7.8 / 1.7.7 | 不崩；新功能隐藏/降级；光标/选择正常 |
| 1.7.8 / 1.7.7 | 1.7.9 | 不崩；旧包格式仍被识别；新功能不可见 |
| 1.7.9 | vanilla/无 mod | 不崩；无自动同步请求/日志刷屏 |

**性能场景**

- [ ] 2 人 / 10 人同分支编辑，20 MB 影片保存：记录 MSPT、帧时间、保存耗时；
- [ ] 80 MB 单文件下载：记录 tick 峰值、总时长、内存峰值；
- [ ] 10k 文件 / 1 GB 资产：记录进服 manifest、首次 inventory、自动同步耗时；
- [ ] 50 部影片 × 5 MB：首次进服/再次进服耗时；
- [ ] 300 / 1000 节点提交图：记录 FPS 与每帧分配；
- [ ] 提交页打开 + 编辑 1 分钟：记录每秒 diff 的帧时间；
- [ ] 20 部 × 3 分支切换 2 小时：GC 后 heap 是否稳定（P4）。

**CDN 故障注入**

- [ ] CDN 404 / 超时 / 返回错误 hash / 半文件：自动回退游戏服，资产最终一致；
- [ ] CDN 清单过期（有旧文件）：不覆盖本地新文件，不把旧文件当权威；
- [ ] CDN 有未知文件：发布任务不删除；客户端不下载未在游戏服清单中的路径；
- [ ] 发布任务中断：重跑可续；sidecar 最后写；不会把半套内容标记为完整。

**F6 CDN 专项**

- [ ] 本地 assets 为空/只有一部分时点 F6 上传：CDN 不得删除本地没有的远端文件；
- [ ] 有超限占位/`.bbspart`/`.bbsup` 时点 F6 上传：CDN 原文件 hash 不变，临时文件不被上传；
- [ ] F6 下载中断/返回错误 hash：本地不出现被当作正式资产的半文件；
- [ ] F6 下载后 Together 自动同步不把 CDN 内容反向覆盖到游戏服；
- [ ] 两套 CDN 配置（BBS 的 `bbs.json` / Together 的 `bbs_together.json`）各填一次，确认 F6 与 Together 分别读哪一套。

**功能回归**

- [ ] 资产上传/下载/自动同步/进度条；
- [ ] 影片保存、分支切换、提交/退回/压缩/改色/重命名；
- [ ] 合作录制邀请/房间/合并；
- [ ] BBS 0/1/2 权限与域白名单；
- [ ] 资产锁（模型/影片/模型方块）与删除请求/tombstone；
- [ ] 行为面板/OP 管理/审计；
- [ ] 单机/局域网（`isIntegratedServerRunning` 跳过 pull）。

---

## 6. 待确认（决定第 3 节能不能落字段级）

1. **“CDN 服务器内容”具体指什么？** 是 BBS 的 `/files`、`/file`、`/upload`、`/delete` HTTP 服务，还是另一套对象存储/CDN（S3/R2/OSS/自建）？有仓库/接口文档/部署路径吗？
2. **CDN 是否允许写？** 如果只能读，方案就是“只读镜像 + 下载分流”；如果允许写，是否接受“客户端不直传、只由服务器/运维发布”？
3. **“还没改动的服务器”是指 CDN 服务，还是仍跑 1.7.7/1.7.8 的游戏服？** 如果是后者，X1/X9 的优先级要再提前。
4. **BBS 2.6 还要不要支持？** 决定 X2 是改元数据还是补 2.6 构建。
5. **体积/性能目标**：jar 是否必须回到 600 KB 以内？主线程 MSPT/帧时间有具体上限吗？
6. **能否新增协议 channel（v2）并要求服务端升级？** 如果服务端长期不升级，只能做客户端侧兼容解析。

---

## 7. 备注：本次已核验的内容

- `gradlew clean build --no-build-cache` 成功（主源码 compileJava 执行、client 重新编译；日志有 unchecked 警告，但无错误）。
- `src/**/*.json` 全部可解析；语言文件无重复 key。
- 50 个 mixin 源文件与 `bbs-together.client.mixins.json`（42）+ `bbs-together.mixins.json`（8）完全对应。
- 静态 `L10n.lang` 键除动态前缀外均在 `en_us.json`；`CoopL10n.key` 静态键均在 client `lang/en_us.json`。
- 本次评估开始时工作区 `git status` 干净；本次没有改任何源码/配置，只新增本报告（因此现在会多一个未跟踪文件）。
- 文中所有行号以当前 `master`（e61d155）为准；如果先做其他改动，行号会漂移。

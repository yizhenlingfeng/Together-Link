# BBS Together 2.1 连接稳定性与资产同步优化表

> 基线：`main` / `e5a9e4b` / `mod_version=2.1`（1.20.1–1.20.4 通用构建 + 1.21.11 实验构建）
> 方法：静态代码审计，逐条在源码中定位（见 §6 证据索引）。
> 实施状态（2026-11-19）：**阶段 0 第 1 条、阶段 1 第 1/3/4 条、阶段 2 第 2–6 条已落地**——`fits(0)` 放行、删除整包前置检查；上传改为服务端回执 `CLIENT_ASSET_UPLOAD_ACK`，客户端等到回执才 `done`，瞬时失败重试 3 次，重复上传幂等；清单（资产/影片）加 `CLIENT_ASSET_INVENTORY_ACK`，`lastReportedSha1`/`lastFilmInventorySent` 改为收到回执后才推进；服务端发不出的资产回 `CLIENT_ASSET_REJECTED`（队列满/读片失败），客户端有界重试后置错；`TransferLog` 按实际发出的片数记成败。协议号升到 5（客户端按 `serverProtocolVersion() >= 5` 决定是否等清单回执，≥4 决定是否等上传回执）。阶段 2 把影片保存/录制/commit 的解析、合并、序列化、写盘搬到 `FILM_IO_EXECUTOR`（先落盘再广播），资产片读盘搬到 drain 线程，影片清单批量化，`lastSavedFilmSnapshots`/`assetHashCache`/客户端清单快照改为有界 LRU，服务端 6 处 + 客户端 4 处 netty 线程解析移入后台池。`build` / `build12111` / `build1204` 三个目标均 BUILD SUCCESSFUL。未实施：>64 MiB 载荷应用层分页（阶段 2 首项）、下载完成回执、`FilmBranchStore.WORKING` LRU、`ASSET_IO_EXECUTOR` 满时回执——原因见 §4 与 §7。
> 说明：表中「默认配置下」指 `asset_send_batch_kb=1024`、`asset_upload_batch_kb=4096`、`asset_sync_max_mb=256`、`asset_sync_receive=false`、`asset_auto_sync=false`。标注「需实测」的条目来自代码推演，需按 §5 在测试服确认。

---

## 0. 结论速览

| 序 | 结论 | 级别 |
|---|---|---|
| 1 | **7c9617d 的 `PacketSizeGuard` 拦在了错误的层**：`PacketCrusher` 本来就会把任意长度切成「16 字节头 + ≤30,000 字节」的包（最大 30,016），而 `sendGuarded`/`sendCrusher` 却在**分片之前**用整包长度和 32,767 比较。默认 1 MiB 下载片 / 4 MiB 上传片、整部影片、manifest、commit 图、hint 批量全部被静默丢弃——这是当前所有「资产/影片同步不上」的第一原因。**（阶段 0 已修复，见 §4）** | **P0** |
| 2 | `PacketSizeGuard.fits(0)` 返回 false，而 `sendUndoSync` 永远发 `new byte[0]`，`sendCoopRespond`/`sendCommitCreate` 的空分支也发 0 字节。**撤销/重做 100% 到不了服务端**，合作拒绝、非 live 提交同样丢失。（撤销的真实数据在尾部 metadata 里，服务端注释也写明期望 null，所以只需放行 0 长度。）**（阶段 0 已修复）** | **P0** |
| 3 | 即使去掉前置拦截，**影片/提交仍缺少应用层分片**：整包上限是客户端→服务端聚合 64 MiB（`SafeServerPacketCrusher.MAX_TOTAL_BYTES`，按压缩后计）、服务端→客户端解压 256 MiB（`PacketCompression.MAX_DECOMPRESSED`），而 `maxAssetBytes()` 是 2 GiB、`asset_sync_max_mb` 默认 256。大影片会在「看似成功」的分片之后被整包丢弃。 | **P0** |
| 4 | **资产上传没有端到端 ACK**：原实现客户端循环结束就 `AssetSyncTracker.done()`，服务端 `finalizeUpload` 不回包，丢包/拒写/校验失败一律表现为「界面已完成，服务器没有文件」。**阶段 1 已修**：上传有逐文件回执 `CLIENT_ASSET_UPLOAD_ACK`；资产/影片清单有 `CLIENT_ASSET_INVENTORY_ACK`，客户端收到回执后才推进「服务端已知」快照；服务端发不出的资产回 `CLIENT_ASSET_REJECTED`。**仍缺**：下载完成 ACK（现由 20 s stall watchdog + 3 次重试兜底，属显式兜底而非回执）。 | **P0（已修）** |
| 5 | 真正会「掉出服务器」的不是 crusher，而是：**服务端主线程长任务**（整片影片 merge/序列化/写盘、每片 1 MiB 读盘、每部影片一个主线程任务）、**netty 线程整片反序列化**、**无界内存**（`FilmBranchStore.WORKING/LAST_SAVED`、`lastSavedFilmSnapshots`、32 个在途 × 64 MiB）、**协议版本不校验**。 | P1 |
| 6 | 另有 **29 处直接 `TogetherServerNetworking.send`** 绕过 crusher（资产锁、影片/回放选择、影片屏幕、光标快照、玩家颜色等）。1.21.11 上 >32,767 的包在配置阶段/代理链路会被直接拒（游戏阶段上限更高，见 §2.1 附注），这些路径才是「该分片却没分片」的地方；1.20.1 的 CustomPayload 上限在 1 MiB 量级，风险阈值高得多，但仍应在统一层解决。 | P1/P2 |
| 7 | 结论顺序：**先修 C1–C4（能同步），再修线程/内存（不卡不炸），最后补协议与直发路径（不混连、不断连）。** | — |

---

## 1. 审计基线

| 项 | 值 |
|---|---|
| 仓库 | `D:\Desktop\Mod\源码\bbs-together` |
| 分支 / HEAD | `main` / `e5a9e4b feat(ui): 完善影片节点界面与文档` |
| mod 版本 | `mod_version=2.1`（`gradle.properties`） |
| 代码规模 | `src/main/java` + `src/client/java` + `java-1.20` / `java-1.21.11` 薄覆盖层，共 189 个 mod Java 文件、1,506,282 B |
| 最大两个类 | `TogetherServerNetwork.java` 189,648 B（3,694 行）；`TogetherClientNetwork.java` 205,309 B（4,272 行） |
| 本次改动 | 阶段 0 第 1 条 + 阶段 1 第 1、3、4 条（4 个文件）：`PacketSizeGuard.fits` 放行 0；删除 `CompressedServerPacketCrusher.sendGuarded` 与 `TogetherClientNetwork.sendCrusher` 的整包前置检查；新增上传回执 `CLIENT_ASSET_UPLOAD_ACK`、清单回执 `CLIENT_ASSET_INVENTORY_ACK`、资产拒绝回执 `CLIENT_ASSET_REJECTED`（`PROTOCOL_VERSION` 3→4→5）；客户端等待回执/失败分类/重试/幂等，清单状态改为回执后推进；`TransferLog` 按实际发送片数记成败；三个构建目标通过 |

优先级定义：

- **P0**：默认配置下必现的同步失效 / 数据丢失 / 直接踢人；应在下一个 patch 修。
- **P1**：特定规模（大影片、多人、长跑）下高概率出现；2.1.x 内修。
- **P2**：健壮性、性能、可维护性；可排期。

---

## 2. 问题地图

### 2.1 32,767 字节：守护层拦在了错误的位置

`PacketCrusher.send()` 的行为（`lib/bbs-fs-2.7-1.20.1/src/main/java/mchorse/bbs_mod/network/PacketCrusher.java:73-108`）：把输入字节按 `BUFFER_SIZE=30_000` 切片，每片加 16 字节头后单独发送。**线上每个包的载荷因此恒定 ≤30,016 字节**，无论原始数据是 1 KiB 还是 500 MiB。

`CompressedServerPacketCrusher.sendBuffer` / `CompressedClientPacketCrusher.sendBuffer` 检查的正是这个「最终片」长度，永远通过。

而 7c9617d 新增的检查发生在此之前：

```
sendGuarded(整包 1,048,576 B)
  ├─ PacketSizeGuard.fits(1048576) → false → 直接 return   ← 问题在这里
  └─（本应）crusher.send → 切成 35 片，每片 ≤30,016 B → 全部合法
```

- 服务端 14 处 `sendGuarded` 调用都走同一个整包前置检查（`TogetherServerNetwork.java:367, 996, 1325, 1423, 1887, 1996, 2024, 2043, 2482, 2490, 2724, 3392, 3504, 3553`）；其中携带 manifest、整部影片、commit 图/索引/diff/head、行为列表的调用在默认配置下必被丢弃。
- 客户端 `sendCrusher` 两个重载同样在分片前检查（`TogetherClientNetwork.java:2071-2086`，阶段 0 已删除），上传 4 MiB 片、整部影片保存、commit 全灭。
- `sendGuarded(BaseType)` 还会先 `DataStorageUtils.writeToBytes(data)` 只为量长度，然后 `send` 里再序列化一次（`CompressedServerPacketCrusher.java:48-63`）。

**判定：这是把「防断连」做成了「防发送」。** crusher 路径原本不存在 >32,767 的包，前置检查没有保护任何东西，只造成静默丢失；真正可能撞上版本/阶段上限的是 §3.4 的 P2 行（29 处绕过 crusher 的直发）。

> **阶段 0 已移除该前置检查（2026-11-19）。** 本节保留为回归背景：若日后又在 `sendGuarded`/`sendCrusher` 里看到整包长度比较，即为此问题复发。

> 附：CustomPayload 的上限随版本、连接阶段与链路而变。1.20.1 的 `CustomPayloadS2CPacket` 有 `MAX_PAYLOAD_SIZE` 字段（yarn 文档可见；历史上常见报错文案是 "Payload may not be larger than 1048576 bytes"）。1.21.x 的社区实测显示：**配置阶段**发 >32,767 会被踢，**游戏阶段** >1,048,576 才会被踢（[PacketEvents #1565](https://github.com/retrooper/packetevents/issues/1565)，代理链路下的观测）。因此 1.20.1 构建上这个 guard 连「必要性」都不成立；修复时也不要按版本写死 32767，统一以「分片后单片 ≤30,016」为准即可。

### 2.2 会「接收错误 / 掉线」的四类真实来源

| 类别 | 机制 | 代表位置 |
|---|---|---|
| **服务端主线程长任务** | 单次 tick 内做整片 merge + 两次 `toData` + 深比较 + 同步写盘 + 逐收件人再序列化；每片 1 MiB 读盘并序列化；每部影片一个主线程任务。多人叠加后 MSPT 可达数百 ms～数秒，表现为全服卡顿、keepalive 延迟、watchdog 告警。 | `handleFilmSaveSync`、`sendTaskInBatches`、`handleFilmInventory` |
| **netty 线程长任务** | 收包回调里直接 `DataStorageUtils.readFromBytes` 整片；客户端只有一个网络线程，大影片解析期间无法处理其他包。实时编辑 delta 也走这条路，频率远高于影片保存。 | 服务端 7 处、客户端 2 处（§3.3 T2） |
| **无界内存** | 分支缓存持有完整 `Film` 对象、`LAST_SAVED` 持有整片 `byte[]`、`lastSavedFilmSnapshots` 每个 film+branch 一份整片、32 个在途传输 × 64 MiB 上限、256 MiB 解压上限。长跑 OOM → 服务端整机掉线 / 客户端崩溃。 | §3.3 M1–M5 |
| **协议/解析不匹配** | `PROTOCOL_VERSION=4` 只写进 hello，从未比较；1.20.1 与 1.21.11 的 payload 形状不同，混连会解错字段抛异常，netty 直接断连。 | `handleHello`、`TogetherClientNetwork:583-595` |

### 2.3 资产同步为什么「看起来成功」

要做到「服务器与玩家资产一致」，至少要满足 7 件事，当前状态：

| # | 条件 | 现状 |
|---|---|---|
| 1 | 每个整包都能到达（或明确失败） | ✅ 阶段 0 后任意长度都由 crusher 切成 ≤30,016 B；仅 >64 MiB（C3）仍会整包失败 |
| 2 | 空载荷有明确语义 | ✅ 阶段 0：`fits(0)` 放行，`{69}` 哨兵即 null |
| 3 | 有端到端 ACK | ⚠️ 上传 ✅（`CLIENT_ASSET_UPLOAD_ACK` 含错误码）、清单 ✅（`CLIENT_ASSET_INVENTORY_ACK`）、资产发不出 ✅（`CLIENT_ASSET_REJECTED`）；**下载完成仍无回执**，靠 20 s stall watchdog + 3 次重试兜底 |
| 4 | 失败可重试、状态机只在成功后推进 | ✅ 上传回执后才 `done`；清单回执后才推进 `lastReportedSha1`/`lastFilmInventorySent`；瞬时错误退避重试（上传 3 次、清单 20 s 内轮询、下载拒绝 2 次） |
| 5 | 大对象分页/分片 | ⚠️ 传输层分片 ✅（crusher）；**应用层分页 ❌**（manifest / inventory / hint / 影片 / commit 仍整包，只在 >64 MiB 时才真正不够用） |
| 6 | 写盘原子 + 冲突可检测 | ⚠️ 基本有（`.bbsup`/`.bbsuptmp`/原子移动/CAS mtime），但 mtime 精确毫秒比较与客户端 3 s 容差不一致，且临时文件在异常路径不清理 |
| 7 | 有界队列不静默丢 | ⚠️ `enqueueAssetSend`(4096) 与发片失败已回执 ✅；`ASSET_IO_EXECUTOR`(128, DiscardPolicy) / `ASSET_WRITE_EXECUTOR`(2048, DiscardPolicy) 仍静默（阶段 2） |

---

## 3. 优化表

> 列含义：**位置**＝证据行；**默认表现**＝按当前默认配置的后果；**建议**＝改动方向（阶段 0 与阶段 1 第 1/3/4 条已实施，其余待排期）；**验收**＝可执行判据。

### 3.1 P0：恢复「能同步」

| 编号 | 位置 | 问题（证据） | 默认表现 | 建议 | 验收 |
|---|---|---|---|---|---|
| **C1** ✅ | `CompressedServerPacketCrusher.java:32-47`（原整包检查，阶段 0 已删）；`TogetherClientNetwork.java:2071-2086`；`PacketSizeGuard.java:24-31` | 整包长度前置检查发生在 `PacketCrusher.send` 分片之前（`BUFFER_SIZE=30_000`，单片最大 30,016）。默认批大小 1 MiB / 4 MiB，必然 >32,767。 | 资产清单、资产下载片、上传片、hint 批量、整部影片、`CLIENT_FILM_DATA_SYNC`、commit 图/索引/diff、行为列表全部被丢弃；日志出现 `Dropped oversized payload for <channel> (... bytes > 32767)`。 | 删除整包前置检查，只保留 `sendBuffer` 的逐片检查（≤30,016）。**阶段 0 已删除前置检查。** 原计划的 `boolean` 返回值：上传侧已由 C4 的 ACK 取代（ACK 才是真信号）；下载/广播侧仍应在阶段 1 第 4 条让 `TransferLog` 依据实际发送结果。 | 1 MiB / 4 MiB 载荷能完整到达；无 `Dropped oversized payload` 日志；9 MB 资产上传+下载 SHA-1 一致。 |
| **C2** ✅ | `PacketSizeGuard.java:24-31`；`TogetherClientNetwork.java:2590-2614, 2092-2113, 3369-3383`；`TogetherServerNetwork.java:3418-3457` | `fits(0)` 为 false；`sendUndoSync` 恒发 `new byte[0]`；`sendCoopRespond` 空 film/异常发 0 字节；`sendCommitCreate` 非 live 发 0 字节。 | 撤销/重做永远不到服务端；合作拒绝静默丢失；非 live 提交丢失。 | `fits(int)` 改为 `length >= 0 && length <= MAX_PAYLOAD`；0 字节交给 `PacketCrusher` 的 `{69}` 哨兵；服务端 `handleUndoSync` 保持「null payload + 尾部 metadata」的现有契约并加注释。 | 连续撤销/重做 20 次服务端均收到；双人合作接受/拒绝各一次；非 live 提交出现在列表。 |
| **C3**（阶段 2 首项） | `SafeServerPacketCrusher.java:21-25`；`PacketCompression.java:20-23`；`TogetherServerNetwork.java:276-283` | 即使修好 C1，整包仍受三个硬上限：客户端→服务端聚合 64 MiB（`MAX_TOTAL_BYTES`，按压缩后计，另有 `MAX_CHUNKS=8192`）、两端解压 256 MiB（`MAX_DECOMPRESSED`）、`maxAssetBytes()`=2 GiB；上传另有 `seqTotal≤4096`（片数，默认 4 MiB/片）。三个上限与默认 `asset_sync_max_mb=256` 互不对齐。 | 压缩后仍 >64 MiB 的影片保存传到一半被拒（60 s 后传输过期）；解压后 >256 MiB 的影片打开失败；上传片数 >4096 的文件被拒。**阶段 0 后这条只在「单部影片压缩后 >64 MiB」时触发，不再是日常路径。** | 影片/提交/分支状态走应用层分片（4–8 MiB/片，带序号与总长度）；或改为流式（边读边发）；服务端聚齐后再 `fromData`；把「整包上限」与「单片上限」写进协议常量并输出 reject 日志；上限与 `asset_sync_max_mb` 对齐。**已并入阶段 2 首项**（与 netty 线程改造同批做，避免两次改同一条链路）。 | 64 MiB / 128 MiB / 256 MiB 影片各保存+打开一次；最终 SHA-1 一致；无 OOM、无 reject 日志。 |
| **C4** ✅ | `TogetherServerNetwork.java:111-114, 140, 1092-1109, 1113-1136, 1160-1331, 1617-1730`；`TogetherClientNetwork.java:159-190, 2337-2403, 2497-2600, 2602-2660` | 原：上传循环结束无条件 `AssetSyncTracker.done()`；`finalizeUpload` 不回包；清单发送前先推进 `lastReportedSha1`/`lastFilmInventorySent`。**阶段 1 已实现完整回执链**：①上传 `CLIENT_ASSET_UPLOAD_ACK`（path、sha1、ok、错误码），落盘后回成功、20 个终止拒绝分支各带错误码、权限拒绝走账户级失败，客户端等回执 ≤60 s、瞬时错误重试 3 次、重复上传幂等；②清单 `CLIENT_ASSET_INVENTORY_ACK`（reportId、kind、entries），资产报表、增量报表、影片清单三条路径都带 `reportId` 并在服务端应用后回执，客户端收到才更新 `lastReportedSha1`/`lastFilmInventorySent`；③资产发不出时 `CLIENT_ASSET_REJECTED`（`queue_full`/`send_failed`）。旧协议客户端不写 `reportId`，服务端按 0 处理并跳过回执，双向兼容。 | 上传/清单的「假成功」已消除：丢片、拒写、校验失败、队列满都会显式失败或重试，不再出现「界面已完成、服务器没有」。下载完成仍靠 watchdog 兜底（无回执）。 | 下载完成回执可并入阶段 2（与线程改造一起做）。 | 人为丢 1 片/服务端拒写/队列打满：客户端显示失败并自动重试；成功时两侧 SHA-1 一致；旧服务端下行为与旧版一致。 |

### 3.2 P1：资产/影片同步正确性

| 编号 | 位置 | 问题 | 影响 | 建议 | 验收 |
|---|---|---|---|---|---|
| **S1** | `TogetherServerNetwork.java:962-1020` | manifest 是全量 `MapType(path → sha1:size:mtime)` 整包发送；`unchanged` 也带 header。 | 文件数 ~500+ 或路径较长时整包超过 64 MiB 才会失败（阶段 0 后 32 KiB 不再是门槛）；主要问题是每次都全量重算 + 全量传输。 | manifest 按 500 条/28 KB 分页 + 页码/总数；或发紧凑数组 + gzip；全局缓存 + 指纹失效；客户端逐页合并。**与 C3 一起并入阶段 2。** | 1000/5000 文件进服，客户端清单完整；第二次进服只收 `unchanged`。 |
| **S2** | `TogetherServerNetwork.java:1287-1333` | hint 把一段时间内所有变更 path 合成一个 `MapType` 整包发送。 | 一次上传几百个文件时整包丢失，其他客户端收不到提示，直到下次 manifest。 | 改为「资产版本已变」小通知 + 客户端拉 manifest；或分页。 | 一次上传 500 文件，所有在线客户端在 1 个通知周期内看到新资产。 |
| **S3** ✅ | `TogetherServerNetwork.java:1418-1427, 1456-1520` | 每玩家队列上限 4096，满则 `assetPendingDropped++` 后 return（客户端永远等不到文件）；`sendTaskInBatches` 在 `TransferLog.complete` 前不校验实际发送结果（读片失败/离线也算「完成」）。 | 队列满/读失败/发片被丢时，客户端缺片且服务端统计显示成功。 | 队列满回 `CLIENT_ASSET_REJECTED`（`queue_full`）；每片在 `server.execute` 里记成功数，最后一片完成后按「实际发出片数」记 `complete` 或 `failed`，失败再回 `send_failed`；客户端有界重试 2 次后置错。 | 1000 文件批量下载无静默丢失；`/bbstogether` 统计与实际一致。 |
| **S4** | `TogetherServerNetwork.java:1454-1470` | `readSlice` 每次重新 `FileChannel.open` + `ByteBuffer.allocate`；`read==0` 时空转（`length` 目前恒 >0，属防御性）。 | 大量分配/句柄；慢盘下主线程空转（C5/T1 的放大器）。 | 在 drain 线程读好 `byte[]` 再交给主线程发送；复用 channel/buffer；区分 `0` 与 `-1`。 | 5 人同时下载 80 MB，MSPT 峰值可控。 |
| **S5** | `TogetherServerNetwork.java:1096-1340` | 上传 `totalSize` 未校验 `<0`；`sha1` 为空时跳过完整性校验；异常路径不清临时文件。 | 异常客户端可写负数大小/绕过校验；失败上传留 `.bbsup`/`.bbsuptmp` 占盘并被下次误用。 | `totalSize >= 0` 且 ≤ `maxAssetBytes`/`syncMaxBytes`；空 sha1 仅旧协议放行并记录；所有失败路径 `finally` 清理；上传 session 60 s 超时。 | 伪造 `totalSize=-1`/空 sha1/中途断开：被拒且无残留；下次上传不受影响。 |
| **S6** | `TogetherServerNetwork.java:1758-1773`；`TogetherClientNetwork.java:333` | 服务端 CAS 用精确毫秒 `mtime == baseMtime`，客户端容差却是 3,000 ms。 | 文件系统精度/复制工具导致合法上传被拒（`stale base`）或并发覆盖判断失真。 | CAS 改用 `baseSha1 + baseSize + mtime(±2 s)`；冲突返回明确错误码让客户端重拉 manifest。 | 跨文件系统复制后上传成功；并发修改返回冲突而不是静默覆盖。 |
| **S7** | `TogetherClientNetwork.java:1491-1624, 1635-1656`；`TogetherServerNetwork.java:256-265` | 服务端 `ASSET_WRITE_EXECUTOR` 单线程、`LinkedBlockingQueue(2048)`、DiscardPolicy；队列满静默丢片。**注**：阶段 1 后即便被丢弃，服务端也会在 `finalizeUpload` 之前因「片不齐」退回 `incomplete` 或最终超时，客户端不再永久以为成功；但真正的「丢弃」仍未消除。 | 上传高峰中间片被丢，`.bbsup` 永久不完整（客户端会重传，代价是全文件重传）。 | 有界队列 + `CallerRunsPolicy` 或按玩家串行；任务的字节量计入预算；拒绝时直接回 ACK 错误。 | 10 人同时上传 20 MB：全部成功，队列峰值有指标，无残留。 |
| **S8**（回执 ✅，分页 ⏳） | `TogetherClientNetwork.java:2532-2610, 2612-2680`；`TogetherServerNetwork.java:1617-1730` | 清单全量/增量仍整包发送（无页码），但**阶段 1 已加回执与状态后置**：报表带 `reportId`，服务端应用后回 `CLIENT_ASSET_INVENTORY_ACK`，客户端收到才推进 `lastReportedSha1`；影片清单同理（`lastFilmInventorySent` 只在确认后更新，另有 `lastFilmInventoryAttempt` 专做 3 s 防抖，且改为单飞，避免每次保存事件都全量哈希一遍影片列表）。 | 清单丢失不再造成「客户端认为已上报、服务端没有」的永久分歧；大资产库仍受 C3 的 64 MiB 上限。 | 清单分页（500 条/页）+ 每页 ACK 并入阶段 1 第 2 条专项。 | 1000 文件首次上报，服务端完整；人为中断一次报表，下一次扫描会重发同一批差异。 |

### 3.3 P1：线程、序列化与内存

| 编号 | 位置 | 问题 | 影响 | 建议 | 验收 |
|---|---|---|---|---|---|
| **T1** ✅ | ~~`TogetherServerNetwork.java:1916-1983`~~ → 现 `handleFilmSaveSync` / `handleMasterRecordingDone` / `handleRecordingFinished` | 主线程做 `stored.toData()`×2、`incomingFilm.fromData`、`mergeFilms`、`collectChangedClips`、`saveBranchNow`（同步写盘）、`broadcastFilmSaved`（逐收件人再序列化）。**阶段 2 已修**：解析/合并/序列化/写盘移到 `FILM_IO_EXECUTOR`（单线程，按 film+branch 加 `filmMergeLocks`），主线程只做引用替换与广播，且顺序改为先落盘再广播。 | 大影片保存卡 tick 数百 ms～数秒；多人同时保存叠乘；watchdog/超时风险。 | 剩余：`broadcastFilmSaved` 仍是逐收件人序列化（T4）。 | 50 MB 影片保存 MSPT <50 ms；无 watchdog；重进内容完整。 |
| **T2** ✅ | `TogetherServerNetwork.java` 的 `handleAssetReport` / `handleAssetReportDelta` / `handleFilmSaveSync` / `handleRecordingFinished` / `handleMasterRecordingDone` / `handleCommitCreate` / `handleFilmDataSync`；`TogetherClientNetwork.java` 的 `handleFilmBranchStatePacket` / `handleFilmSavedSyncPacket` / `handleFilmDataSyncPacket` / `handleAssetManifestPacket` | netty 线程直接 `DataStorageUtils.readFromBytes` 整片。**阶段 2 已修**：服务端 6 处移入 `ASSET_IO_EXECUTOR`、客户端 4 处移入新增的 `FILM_PARSE_EXECUTOR`（`BBSTogether-FilmParse`），全部 try/catch 记日志而不是让异常吞掉连接；netty 线程现在只读 header。 | 大包解析阻塞该连接的事件循环；客户端单网络线程被占，keepalive 延迟，极端时 30 s 读超时断连。 | 剩余：`ASSET_IO_EXECUTOR`（128、DiscardPolicy）满时仍静默丢弃；单包上限（16 MiB）未加。 | 50 MB 影片保存时其他玩家延迟 <100 ms；netty 线程无长任务。 |
| **T3** ✅ | `TogetherServerNetwork.java` 的 `handleFilmInventory` | `handleFilmInventory` 对每部影片 `server.execute` 一个任务，任务内 `sha1OfPathCached` + `workingData().toData()` + 整片发送。**阶段 2 已修**：分支判断/SHA-1/序列化全在 `ASSET_IO_EXECUTOR`，收集后一次 `server.execute` 批量发送。 | 50 部影片进服 = 50 个主线程重任务，累计数秒；多人同时进服叠乘。 | 已完成；`broadcastFilmSavedToAll` 的整片构造仍在该批任务内预先做，但不再是逐影片主线程任务。 | 50 部影片、3 人同时进服，MSPT 峰值可控，清单完整。 |
| **T4**（部分已修） | `TogetherServerNetwork.java:366-371, 1886-1914`；`TogetherClientNetwork.java:2071-2086` | 原：`sendGuarded(BaseType)` / `sendCrusher(BaseType)` 先 `writeToBytes` 量长度、再序列化一次（阶段 0 已删掉这次重复序列化）；仍存：`broadcastFilmSaved` 对每个收件人重复序列化。 | N 个同分支玩家 = N 次完整序列化 + gzip；大影片每多一人多 100–300 ms 主线程。 | 序列化一次得到 `byte[]`；`PacketCrusher.send(Collection)` 一次发给所有收件人；长度检查改成「分片后」。 | 3 人同分支保存 20 MB：序列化 1 次，三人都收到。 |
| **M1** | `FilmBranchStore.java:47-55` | `WORKING`（`Map<String, Map<String, Film>>`）、`PLAYER_BRANCHES`、`DIRTY` 均为无上限静态集合（`LAST_SAVED` 实为每分支 32 字节 SHA-256 摘要，不是整片字节）。 | 影片库大/分支多时堆线性增长；依赖 `unload()` / `forgetFilm()` 显式释放。 | **阶段 2 未做**（改动牵涉保存/回滚/丢弃判定，风险高于收益）：按需加载 + LRU；无引用/无人编辑的分支保存后释放。 | 100 部影片打开/关闭/切分支 2 小时，GC 后 heap 稳定。 |
| **M2** ✅ | `TogetherServerNetwork.java` 的 `lastSavedFilmSnapshots` | 每个 film+branch 保存一份完整 `MapType`，仅在删除影片/重命名/关服时清理。**阶段 2 已修**：access-order `LinkedHashMap` LRU，上限 32；淘汰只影响下一次 `before` 快照的来源（回退为 `stored.toData()`），不丢数据；`renameBranchKeys` 与删除路径已加锁。 | 20 部 × 3 分支 × 10 MB ≈ 600 MB。 | 剩余：可按字节预算而不是条数淘汰。 | 打开/保存 40+ 部影片后 heap 不随影片数增长。 |
| **M3**（部分已修） | `TogetherServerNetwork.java` 的 `assetHashCache`；`SafeServerPacketCrusher.java:21-25`；`PacketCompression.java:23` | `assetHashCache` 无上限。**阶段 2 已修**：LRU 上限 20000 条（淘汰只导致重算 SHA-1）。仍存：32 个在途传输 × 64 MiB ≈ 2 GiB 上限；解压上限 256 MiB；`expire` 只在收到新包时执行。 | 恶意/异常客户端可占满服务端内存；半包长期占用。 | 阶段 3：全局字节预算 + 定时 `expire`；单玩家在途上限。 | 50 个半开传输，服务端 heap 不超预算；断线后 60 s 内清理。 |
| **M4**（部分已修） | `TogetherClientNetwork.java` 的 `filmCache` / `lastServerSha1Map` / `lastServerSizeMap` / `lastServerMtimeMap` / `ignoredTransfers` | `filmCache` 最多 8 部整片（有界）；服务端清单快照原先无上限。**阶段 2 已修**：三张清单 map 改为 `BoundedMap`（上限 60000 条），淘汰只让下一次 diff 多校验一个文件。仍存：`OPEN_SINKS`、`PENDING_DOWNLOADS`、`downloadRetries`、`ignoredTransfers` 在日常路径上有清理但无硬上限。 | 长跑内存上涨；超大街区式资产库快照膨胀。 | 阶段 3：`OPEN_SINKS` 超时关闭；统一按字节预算。 | 连续打开 10 部大影片 + 5000 文件资产库，heap 稳定。 |

### 3.4 P2：协议、直发路径与健壮性

| 编号 | 位置 | 问题 | 建议 | 验收 |
|---|---|---|---|---|
| **P1** | `TogetherServerNetwork.java:145, 1634-1645, 1703`；`TogetherClientNetwork.java:607, 624-636, 699-701` | `PROTOCOL_VERSION=5` 只写进 hello，服务端**仍不比较**；客户端只把它当特性门（`>=4` 等上传回执、`>=5` 等清单回执），不做兼容性拒绝。 | 双向比较；不兼容时发 `CLIENT_INCOMPATIBLE` 并断开/只读；1.21.11 用独立协议号；日志记录版本。 | 1.20.1 客户端连 1.21.11 服务端：明确提示，不崩溃、不静默解错包。 |
| **P2** | 29 处 `TogetherServerNetworking.send`（`:356, 688, 760, 778, 817, 891, 928, 943, 1283, 1346, 1547, 1552, 1775, 1790, 1911, 2099, 2137, 2500, 2510, 2517, 2863, 3003, 3004, 3041, 3155, 3243, 3246, 3268, 3604`） | 完全绕过 crusher：资产锁名单（1000 把锁 ≈150 KB）、影片/回放选择（玩家数 × 片段数）、光标快照、影片屏幕、玩家颜色。按 §2.1 附注，1.21.11 上 >32,767 在配置阶段/代理链路会被拒，1.20.1 需接近 1 MiB 才触发——两者都应由统一层兜住。 | 统一走 crusher 或按 28 KB 分页；同一 payload 只序列化一次；名单压缩/差分；超限先裁剪并提示。 | 20 人 + 1000 锁 + 50 选择：无断连；抓包每包 <32,768（1.21.11）。 |
| **P3** | `PacketCrusher.java:80` | `total = max((int)ceil(len / 30000f), 1)` 用 float，>16 MB 时可能少算 1 片。 | 整数 `(len + 29999) / 30000`，`long` 中间量。 | 16/32/64 MB 载荷分片数正确、尾部字节一致。 |
| **P4** | `PacketCrusher.java:30-56` | 客户端 `receive` 信任 header：`new byte[size]`、`ByteArrayOutputStream(total * 30000)`，无上限/无超时/无校验；`counter` 在循环结束后自增且 `send` 不同步（服务端全部在 server 线程发送，目前无碰撞，但协议本身不防）。 | 复用 `SafeServerPacketCrusher` 的校验逻辑；加在途上限与 60 s 超时；`send` 加锁或每传输独立 id。 | 伪造 size/total/index：被拒且不断连；两个线程并发发送内容正确。 |
| **P5** | `PacketCompression.java:70-104` | gzip 解压失败返回 `new byte[0]`，上层 `readFromBytes` 会抛异常（netty 线程）→ 断连而非重试。 | 返回明确的 `null`/异常类型，由接收方转成「请求重传」或「断开并记录原因」。 | 人为损坏 gzip 载荷：客户端提示并重连，不抛未捕获异常。 |
| **P6** ✅ | `TogetherServerNetwork.java:1323-1341` | `finalizeUpload` 原先只做缓存/统计/提示，不回上传结果。 | 已由 C4 覆盖：落盘后回 `CLIENT_ASSET_UPLOAD_ACK` 成功；拒绝分支各自回错误码。 | 同 C4（联机验证 ACK 成对）。 |
| **P7**（阶段 1 新增） | `TogetherServerNetwork.java:1466-1530`；`TogetherClientNetwork.java:2637-2700, 2711-2770` | 新增回执链路自身的风险：①上传/清单回执都走 `server.execute` + `sendGuarded`，若服务端主线程被 T1/T2 长任务长时间占用，回执会延迟，客户端可能先超时（上传 60 s / 清单 20 s）而服务端其实已落盘。②回执是「尽力而为」的一发，没有重发。 | 短期内表现为「客户端报超时但服务端其实成功」；阶段 2 解决主线程长任务后自然缓解。 | 已有缓解：上传超时分类为瞬时错误并重试，重试命中服务端 `changed=false` 时仍回 success（幂等），最终结果正确；清单超时只是不推进快照，下一次扫描重报。后续可给回执加一次重发或让客户端「超时后再查一次清单版本」。 | 制造 5 s 主线程卡顿（如保存超大影片）后上传小资产：客户端最多报一次重试，最终成功且服务端文件正确。 |

---

## 4. 修复路线（阶段 0、1、2、3 第 1–3 条已实施）

### 阶段 0：恢复同步（P0）——**已实施 2026-11-19**

1. ✅ `fits(0)` 放行；删除 `sendGuarded`/`sendCrusher` 的整包前置检查（保留 `sendBuffer` 逐片检查）。改后任意大小载荷都由 crusher 切成 ≤30,016 B 的片，0 字节走 `{69}` 哨兵。
2. ✅ 布尔返回值与状态后置：上传侧由阶段 1 的 ACK 取代（ACK 才是「对端已落盘」的信号）；下载/广播侧的 `TransferLog` 判定已并入阶段 1 第 4 条。
3. ❌ 回归用例见 §5.1 / §5.2（1 MiB 资产片、4 MiB 上传片、0 字节 undo 哨兵、整部影片保存）——**未跑**，见 §7 第 1 条。
4. 说明：原第 4 条「把批大小调到 30 KB 绕过 guard」的临时验证手段在阶段 0 修复后不再需要。

### 阶段 1：端到端一致（P0/P1）——**第 1、3、4 条已实施 2026-11-19**

1. ✅ **上传 ACK + 重试 + 幂等**：`CLIENT_ASSET_UPLOAD_ACK`（path、sha1、ok、错误码）；服务端在 `finalizeUpload` 落盘后与 20 个终止拒绝分支回执（权限拒绝为账户级失败）；客户端等回执 ≤60 s、瞬时错误（`ack_timeout/io_error/interrupted/missing_part/stale_base`）重试 3 次（2 s×n）、其余错误码直接失败，成功后 `AssetSyncTracker.done` 与 `lastServerSha1Map` 才推进；同一 sha1 重复上传服务端亦回 success（幂等）。下载完成 ACK 仍待做（下载已有 20 s stall watchdog + 3 次重试，属显式兜底）。
2. ⏳ **应用层分页/流式（>64 MiB 载荷）**——本阶段唯一未做项，理由：
   - 阶段 0 修复后，crusher 已能把任意长度切到 ≤30,016 B，**32,767 这条线不再是瓶颈**；剩余上限是 `MAX_TOTAL_BYTES=64 MiB`（单次整包，按压缩后计）与 `MAX_DECOMPRESSED=256 MiB`。这两条只在「单部影片 >64 MiB（gzip 后）」时触发，属于 C3 的结构性改造。
   - 正确做法要么改 crusher 的整包聚合契约（服务端聚齐后才回调），要么把影片/提交改成真正的分片协议。两种都要动 `lib/` 里的 `PacketCrusher` 或重写 4 条影片通道的编解码，改动面比阶段 1 其余三条大一个量级，且与阶段 2 的「netty 线程只读 header」直接耦合（分片组装必须在后台线程做）。
   - 因此把它并入**阶段 2 首项**，和线程改造一起做，避免两次改同一条链路。若运营侧已知存在 >64 MiB 影片，可先用 `asset_sync_max_mb`/单文件上限挡住，让客户端看到明确拒绝而不是「传到一半失败」。
3. ✅ **清单 ACK + 状态后置**：资产报表/增量报表/影片清单都带 `reportId`，服务端 `ackInventoryReport` 应用后回 `CLIENT_ASSET_INVENTORY_ACK`（reportId、kind、entries）；客户端 `awaitReportAck` ≤20 s，收到才推进 `lastReportedSha1`（`deltaSupported` 时用发送前快照 `putAll`），影片清单同理且改单飞 + `lastFilmInventoryAttempt` 专做 3 s 防抖，失败 5 s 后重试。
4. ✅ **队列拒绝回执 + 真实统计**：队列满回 `CLIENT_ASSET_REJECTED(queue_full)`；`sendTaskInBatches` 按实际 `sendGuarded` 成功的片数决定 `TransferLog.complete` 还是 `failed`，失败再回 `send_failed`；客户端有界重试 2 次（2 s×n），超限后置 `AssetSyncTracker.error` 并计数。

### 阶段 2：线程、内存与大数据（P1，1–2 周）——**第 2–6 条已实施 2026-11-19**

1. ⏳ **（首项）C3 应用层分页/流式**：影片/提交/分支状态改为带序号与总长的分片协议（或边读边发），把 64 MiB 整包上限从日常路径上移除。**本轮未做**，理由同阶段 1 第 2 条（要动 `lib/` 的 `PacketCrusher` 聚合契约或重写 4 条影片通道）。当前状态：任意大小载荷都能安全分片发出（阶段 0），单部影片压缩后 >64 MiB 仍是硬门槛。
2. ✅ `handleFilmSaveSync` 解析/合并/序列化/写盘全部移到 `FILM_IO_EXECUTOR`（单线程 `BBSTogether-FilmIO`），主线程只做引用替换与广播；新增 `filmMergeLocks`（按 film+branch）保证同分支不交错；**先落盘再广播**，查看端不会收到服务端尚未保存的状态。`handleMasterRecordingDone` / `handleRecordingFinished` 同批改造；`mergeFilms` 签名改为 `(Film, Film, UUID, boolean)` 以便在无 `ServerPlayerEntity` 的线程上调用。
3. ✅ `sendTaskInBatches` 的 `readSlice` 移到 drain 线程，主线程只做 `sendGuarded`；`readSlice` 修 `read==0` 死循环（连续 4 次 0 字节读取即放弃该片）。
4. ✅ `handleFilmInventory` 批量化：分支判断 / SHA-1 / `workingData` 全在 `ASSET_IO_EXECUTOR` 完成，收集成列表后**一次** `server.execute` 批量发送，不再是「每部影片一个主线程任务」。
5. ✅ 缓存有界：`lastSavedFilmSnapshots` 改为 access-order LRU（上限 32 个 film+branch）、`assetHashCache` 改为 LRU（上限 20000 条）、客户端 `lastServerSha1Map/SizeMap/MtimeMap` 改为 `BoundedMap`（上限 60000 条）；`renameBranchKeys` / `applyFilmDeleteRuntime` 对 LRU map 加锁迭代。`FilmBranchStore.WORKING` 仍未加 LRU（依赖 `unload`/`forgetFilm`，属阶段 2 尾巴，见 §7）。
6. ✅ netty 线程只读 header：服务端 7 处 `readFromBytes` 中 6 处（资产报表、增量报表、影片保存、录制结束、master done、commit 创建、实时编辑 delta）移到 `ASSET_IO_EXECUTOR` 并 try/catch 记日志；客户端新增 `FILM_PARSE_EXECUTOR`（单线程 `BBSTogether-FilmParse`），影片分支状态、saved-sync、data-sync、资产 manifest 的解码全部移到该池。`ASSET_IO_EXECUTOR` 满时仍静默丢弃（未做）。
7. ⏳ 下载完成回执（补齐 C4 最后一块）——未做，下载仍靠 20 s stall watchdog + 3 次重试兜底。

### 阶段 3：协议与直发（P2，随版本）——**第 1–3 条已实施 2026-11-19**

1. ✅ **协议版本双向比较**：新增 `MIN_SUPPORTED_PROTOCOL = 3`（`TogetherServerNetwork.java` 常量区）。`handleHello` 现在把客户端协议存入 `playerProtocols` 后与 `PROTOCOL_VERSION` 双向比较：客户端更新时记日志「running in the common subset」，更旧时记「sync receipts are disabled for this player」，并继续按旧行为降级而不主动断开（Fabric 对未注册 channel 的容错未实测，硬踢的风险大于降级）。协议号仍为 5：3→4 上传 ACK、4→5 清单/拒绝回执，本阶段未再改报文结构，只补齐了版本比较与日志。
2. ✅ **增长型直发统一走 crusher**：新增 `sendChunked(player, channel, buf)`（把已构建的 `PacketByteBuf` 转 `byte[]` 交给 `crusherFor(player).sendGuarded`，可被分片）。已替换所有「随玩家数/锁数/选择项增长」的直发：`CLIENT_PLAYER_COLORS`、`CLIENT_CURSOR_POSITIONS`（3 处：光标快照、光标移除、聚合广播）、`CLIENT_FILM_SELECTIONS`、`CLIENT_REPLAY_SELECTIONS`、`CLIENT_REPLAY_FORM_LOCKS`、`CLIENT_FILM_SCREENS`、`CLIENT_ASSET_LOCKS`、`CLIENT_COMMIT_SELECTIONS`、`CLIENT_PLAYER_COMMIT_POSITIONS`。剩余直发均为定长小包（单/双字符串、布尔、若干 int，或空包 `CLIENT_ASSETS_CHANGED`/`CLIENT_FILM_LIST_CHANGED`/`CLIENT_SERVER_LIMITS`/`CLIENT_HELLO`），不会随规模增长，保持直发以避免无谓分片开销。
3. ✅ **接收端整数分片 / 校验 / 超时**：`CompressedClientPacketCrusher.receive` 改为与服务端 `SafeServerPacketCrusher` 同构的加固实现——校验 `id/total/index/size/readableBytes`、`MAX_CHUNKS=8192`、`MAX_OPEN_TRANSFERS=32`（超限淘汰最旧）、`MAX_TOTAL_BYTES=64 MiB`、初始缓冲按 `min(total*30_000, 1 MiB)` 封顶、`TRANSFER_TIMEOUT_MS=60 s` 定时 `expire`、`{69}`→null 哨兵；`reset` 清空三张表。此前基类 `PacketCrusher.receive` 直接信任头部（`new byte[size]`、`ByteArrayOutputStream(total*30_000)`），恶意/损坏的服务器数据可请求超大分配。发送侧 `PacketCrusher` 的浮点 `total` 精度问题位于外部 BBS jar，不可改；但本项目两层每片检查（`sendBuffer` 的 `PacketSizeGuard.fits`）与接收端校验已把实际风险压到最低。
4. ⏳ 上传临时文件清理、非原子文件系统降级安全——未做（`handleAssetUpload` 失败路径仍可能残留 `.bbsup`/`.bbsuptmp`，见 §7）。

---

## 5. 验收清单

### 5.1 包大小与断连

- [ ] 9 MB 资产上传：服务端文件 SHA-1 一致，客户端只有收到 ACK 才显示完成。
- [ ] 80 MB 资产下载：`CLIENT_ASSET_FILE` 每片 ≤30,016 B；`.bbspart` 顺序正确；最终 SHA-1 一致。
- [ ] 60 MB 影片打开：客户端渲染完整，日志无 `Dropped oversized payload`（旧文案）或 `Dropped oversized chunk`（新文案）。
- [ ] 50 MB 影片保存：所有同分支玩家收到完整影片；MSPT <50 ms。
- [ ] 1000 文件 manifest / inventory：服务端完整收到（分页未做，靠 64 MiB 上限；此用例同时验证上限是否够用）。
- [ ] 20 次连续 >30 KB 的实时编辑 delta：全部到达且顺序正确。
- [ ] 20 人 + 1000 资产锁 + 50 选择：无断连，抓包每包 <32,768（1.21.11）。

### 5.1b 回执链路（阶段 1 新增，重点）

- [ ] **上传成功回执**：改一个 9 MB 资产并保存 → 服务端 `logs/latest.log` 里客户端显示完成的同时，服务端目标文件 mtime 已更新；客户端 `[BBS Assets]` 无 `ack_timeout`。
- [ ] **上传拒绝回执**：用无上传权限的账号（或把 `assetSyncUpload` 关掉再手动触发）上传 → 客户端弹出拒绝并**不**显示完成；服务端日志有 `upload.rejected`。
- [ ] **上传中断重试**：上传大文件时用防火墙掐断 3 s 再放行 → 客户端日志出现 `Upload attempt N failed ... retrying`，最终仍成功；服务端无 `.bbsup` 残留（`assets/` 下搜索）。
- [ ] **幂等**：不改文件，反复保存同一资产 3 次（强制重传）→ 服务端第二次起 `changed=false`，客户端仍收到成功回执，不报错、不重传无限循环。
- [ ] **清单回执**：进服后立刻 `/bbstogether`（或看日志）确认服务端收到资产报表；然后把服务端 `assets/` 里某个文件改名（模拟服务端清单与客户端不一致）→ 客户端下一次自动同步能把差异再报一次（说明 `lastReportedSha1` 没有「先推进后丢包」）。
- [ ] **影片清单回执**：连续保存影片 5 次（间隔 <3 s）→ 客户端不会每次都全量重算影片列表（日志里 `BBSTogether-FilmInventory` 出现次数明显少于保存次数）。
- [ ] **队列拒绝**：让一台客户端一次性请求 5000+ 文件（或把 `asset_send_interval_ms` 调大制造积压）→ 客户端日志出现 `Server deferred <path> (queue_full), retry N`，恢复后自动补齐；服务端 `/bbstogether` 的下载统计与实际一致（不再虚报完成）。
- [ ] **旧服务端兼容**：把服务端换回协议 4 的构建（或临时把 `PROTOCOL_VERSION` 改回 4）→ 客户端不等待上传/清单回执，行为与 2.1 原版一致，不卡在「等待服务器确认」。

### 5.1c 线程与内存（阶段 2 新增）

- [ ] **保存不卡服**：开 spark/tick 计时，保存 50 MB 影片 → 主线程无 >100 ms 单 tick；`BBSTogether-FilmIO` 线程在日志外可见（jstack 或 spark 线程列表）。
- [ ] **保存即持久**：保存后立刻（<5 s，去抖窗口内）让另一个玩家重新打开该影片 → 看到的是刚保存的内容（证明已先落盘再广播）。
- [ ] **实时编辑不卡 netty**：两人同分支拖关键帧 30 s，第三人连续走动/聊天 → 无卡顿、无 `Timed out`、服务端控制台无 `Malformed film delta`。
- [ ] **进服不刷屏**：50 部影片的服务器，3 人同时进服 → 服务端日志 `Saved film` 批量出现而非每部一个 tick 尖峰；MSPT 峰值可接受。
- [ ] **内存有界**：打开 40 部不同影片（含跨分支）后查看 heap → `lastSavedFilmSnapshots` 只保留最近 32 个（可用 spark 对象直方图确认 `MapType` 数量不随影片数线性增长）。
- [ ] **资产库快照**：进服 3 次、每次自动同步 → 客户端 heap 不随进服次数增长（`BoundedMap` 生效）。
- [ ] **读片健壮**：同步过程中在服务端覆盖某个正在发的文件（制造短读）→ 客户端重试或明确失败，服务端无 `read==0` 死循环（该线程 CPU 不 100%）。

### 5.2 空载荷与特殊路径

- [ ] 撤销/重做 20 次：服务端每次都收到（payload 为 null，metadata 完整）。
- [ ] 合作接受/拒绝（含 film==null 分支）：服务端收到。
- [ ] 非 live 提交创建：提交出现在列表。
- [ ] 0 字节资产：客户端不上传，服务端无残留临时文件。

### 5.3 并发与冲突

- [ ] A、B 同时改同一资产：先到者成功，后者收到明确冲突并重新拉 manifest，不静默覆盖。
- [ ] A、B 同时保存同一影片不同分支：互不覆盖。
- [ ] 5 人同时进服：每人 manifest 完整，`ASSET_IO_EXECUTOR` 无静默丢弃。
- [ ] 10 人同时上传 20 MB：无 `.bbsup` 残留，heap 峰值可控。
- [ ] 断网 10 s 后恢复：上传/下载自动续传成功（阶段 1 后应表现为：重试成功，或明确失败后下一次扫描自动补报）。

### 5.4 协议与长跑

- [ ] 1.20.1 ↔ 1.21.11 混连：明确拒绝，不崩溃。
- [ ] 2 小时多人编辑 + 资产同步：GC 后 heap 稳定。
- [ ] 100 部影片打开/关闭/提交页：内存稳定。

---

## 6. 证据索引

> 行号为 2026-11-19 阶段 0/1 实施后的快照；后续改动后请按函数名检索（`ackAssetUpload`、`handleAssetUploadAck`、`sendAssetUploadWithRetry` 等）。

| 文件 | 关键行 | 用途 |
|---|---|---|
| `src/main/java/mchorse/bbs_together/network/PacketSizeGuard.java` | 18-39 | `MAX_PAYLOAD=32767`；`fits` 放行 0（阶段 0）；`warn` 现仅用于逐片检查 |
| `src/main/java/mchorse/bbs_together/network/CompressedServerPacketCrusher.java` | 16-30, 32-47, 49-59 | 逐片检查 / `sendGuarded`（阶段 0：整包检查与 BaseType 双序列化已删）/ 压缩 |
| `src/client/java/mchorse/bbs_together/network/CompressedClientPacketCrusher.java` | 18-43 | 客户端逐片检查与压缩 |
| `lib/bbs-fs-2.7-1.20.1/src/main/java/mchorse/bbs_mod/network/PacketCrusher.java` | 19, 30-56, 73-108 | 30,000 B 分片、`{69}` 哨兵、float 分片数、无校验的 receive |
| `lib/bbs-fs-2.7-1.20.1/src/main/java/mchorse/bbs_mod/network/SafeServerPacketCrusher.java` | 21-25, 32-89 | 8192 片 / 32 在途 / 64 MiB / 60 s / 校验 |
| `src/main/java/mchorse/bbs_together/network/PacketCompression.java` | 18-23, 28-104 | 1 MiB 阈值、256 MiB 解压上限、损坏包返回空数组 |
| `src/main/java/mchorse/bbs_together/network/TogetherServerNetwork.java` | 114, 116, 145, 224, 657, 1426-1434, 1466-1530, 1634-1698, 1703, 1735, 1772, 1823 | 清单回执/拒绝回执 channel、协议号 5、`playerProtocols`、队列满拒绝、按真实片数记 `TransferLog`、`supportsSyncReceipts`、`ackInventoryReport`、`rejectAssetSend`、三条清单路径回执 |
| `src/main/java/mchorse/bbs_together/network/TogetherServerNetwork.java` | 110, 139, 199, 230, 244-265, 276-283, 310-390, 411-427, 948-1006, 1036-1064, 1096-1340, 1287-1354, 1356-1470, 1601-1609, 1615-1661 | 上传回执 channel、协议号、缓存、线程池、批大小、分支状态、manifest、请求、上传、hint、下发、hello、报表、影片清单 |
| 同上（上传回执） | 1092-1109, 1113-1136, 1160-1331 | `noticeUploadRejected` 账户级失败、`ackAssetUpload` 发送器、20 个拒绝码与 `finalizeUpload` 成功回执 |
| 同上 | 1886-1983, 1995-2050, 2270-2315, 2468-2517, 2838-2872, 3344-3457 | 影片保存/同步、commit 索引/diff/head、commit 创建、资产锁、delta 转发与撤销 |
| `src/client/java/mchorse/bbs_together/network/TogetherClientNetwork.java` | 164-203, 483, 511-512, 595-602, 1455, 2383-2410, 2415-2500, 2613-2695, 2711-2770, 3205 | `pendingReports`/`PendingReport`/`nextReportId`/超时常量、`assetSendRetries`、两个新回执注册、reset 清理、上传重试调用点、`supportsInventoryAck`/`awaitReportAck`/`handleInventoryAck`/`handleAssetRejected`、清单状态后置、影片清单单飞与防抖 |
| `src/client/java/mchorse/bbs_together/network/TogetherClientNetwork.java` | 124-134, 1491-1656, 2071-2113, 2196-2375, 2590-2614, 2668-2679, 3369-3383, 3610-3639, 4221-4246 | 缓存、下载写入、发送器、上传+等回执+重试、撤销、合作、提交、请求分片（阶段 1 后行号已更新） |
| 同上（上传回执辅助） | 159-190, 2305-2340, 2342-2375 | `uploadAcks`/`UploadAck`/`UploadRejectedException`/超时常量、ACK 分发、重试与失败分类 |
| `src/main/java/mchorse/bbs_together/TogetherSettings.java` | 52-104 | 同步开关、批大小、限速、上限 |
| `src/main/java/mchorse/bbs_together/commits/FilmBranchStore.java` | 47-55, 305-310, 374 起 | `WORKING`/`PLAYER_BRANCHES`/`DIRTY` 无上限（M1 未做，靠 `unload`/`forgetFilm` 释放）；`LAST_SAVED` 只存 32 字节摘要；整片序列化 + 同步写盘 |
| `src/main/java/mchorse/bbs_together/commits/FilmCommitStore.java` | 53 | `MAX_COMMITS=300` |
| `src/main/java/mchorse/bbs_together/commits/FilmCommit.java` | 101-126 | 提交条目体量（无内联快照，但 300 条仍可 >32 KB） |
| `src/main/java/mchorse/bbs_together/network/TogetherServerNetwork.java`（阶段 2） | `filmMergeLocks`、`FILM_IO_EXECUTOR`、`sendTaskInBatches`、`readSlice`、`handleFilmSaveSync`、`handleMasterRecordingDone`、`handleRecordingFinished`、`handleFilmInventory`、`handleCommitCreate`、`applyFilmDataSync`、`lastSavedFilmSnapshots`（LRU 32）、`assetHashCache`（LRU 20000）、`renameBranchKeys` | 按 film+branch 的合并锁、单线程影片 I/O 池（"BBSTogether-FilmIO"）、drain 线程读盘与 `read==0` 修复、影片清单批量化、commit/delta 后台解析、两处 LRU、LRU 加锁迭代 |
| `src/client/java/mchorse/bbs_together/network/TogetherClientNetwork.java`（阶段 2） | `FILM_PARSE_EXECUTOR`（"BBSTogether-FilmParse"）、`BoundedMap`、`lastServerSha1Map/SizeMap/MtimeMap`、`applyAssetManifest`、`handleFilmBranchStatePacket`、`handleFilmSavedSyncPacket`、`handleFilmDataSyncPacket` | 客户端解析池、有界清单快照（60000）、manifest 解码后台化、三条影片通道的 netty 解析移出 |

---

## 7. 未决与边界

1. **静态审计 + 构建验证，未在测试服压测。** C1/C2、C4 的上传/清单/拒绝回执已实施并由三次构建证明可编译；同步是否真正恢复、「回执是否成对」、T1–T3 的「是否真的踢人」取决于影片大小、磁盘与人数，需按 §5 实测。
2. **CustomPayload 上限随版本/阶段/链路而变**（1.20.1 为 1 MiB 量级；1.21.x 配置阶段 32,767、游戏阶段 1,048,576，代理链路另有约束，见 §2.1 附注）。修复应以「分片后单片 ≤30,016」为准，不要按版本写死。
3. **`asset_sync_max_mb=0` 的语义**：代码按「不限」处理（`syncMaxBytes()` 返回 0，上传处 `>0` 才检查），但 UI/文档未明确，需要产品确认。
4. **上传片数上限与整包上限不对齐**：`handleAssetUpload` 的 `seqTotal≤4096` 按默认 4 MiB/片名义可达 16 GiB，但客户端→服务端单次传输实际先被 `MAX_TOTAL_BYTES=64 MiB` 截断（C3）；`asset_sync_max_mb=256` 与 64 MiB / 256 MiB 两个硬上限也不一致，需要产品层面拍一个数（建议入站上限与出站一致，或改流式传输）。这一条与阶段 1 第 2 条（应用层分页）是同一件事，已并入阶段 2。
5. **CDN 路径未展开**：旧表已指出 CDN 只作只读分发、不能当权威源；当前配置是否仍有两套（`bbs.json` vs `bbs_together.json`）需单独审计。
6. **`DiscardPolicy` 是取舍还是缺陷**：本表按「不能静默丢资产」处理；若产品接受丢弃，也必须给客户端回执——阶段 1 后队列满与发片失败都已回 `CLIENT_ASSET_REJECTED`；阶段 3 已把接收端 `CompressedClientPacketCrusher` 的丢弃改为可观测（校验失败/超时直接丢弃分片，不再尝试超大分配）。仍存：`ASSET_IO_EXECUTOR`（manifest/影片清单构建，128 队列 + DiscardPolicy）与 `FILM_IO_EXECUTOR`（256 队列 + DiscardPolicy）满时仍静默丢弃，见第 10 条。
7. 目前共改 5 个源文件（阶段 0：`PacketSizeGuard.java`、`CompressedServerPacketCrusher.java`、`TogetherClientNetwork.java`；阶段 1/2：`TogetherServerNetwork.java` 及 `TogetherClientNetwork.java` 的增量），未改配置与构建脚本；构建按 `build`、`build12111`、`build1204` 分开逐条跑（串在一条命令里会触发 included build 路径冲突），三条均 BUILD SUCCESSFUL。本机沙箱下 `Files.isWritable` 恒为 false 会让 Loom 拆包失败，构建需在完整权限下运行。
8. **协议号 3→4→5，阶段 3 已补双向比较**：`handleHello` 现在读客户端协议后与 `PROTOCOL_VERSION` 双向比较并记日志（更新/更旧的客户端各一条），`MIN_SUPPORTED_PROTOCOL=3`；仍不主动断开，因为 Fabric 对未注册 channel 的具体行为（忽略/告警/断开）尚未实测，硬踢的风险大于降级。新客户端连旧服务端仍按 `serverProtocolVersion()` 走旧逻辑（<5 不等回执）。严格的混版本双向保证仍需实测。
9. **回执链路的时序假设需实测验证**：清单回执等待发生在后台线程（`BBSTogether-AssetInventory`，最长 20 s），上传回执等待发生在上传工作线程（最长 60 s），两者都不占用客户端主线程；阶段 2 已把长任务移出服务端主线程，回执延迟风险随之下降，但仍需实测。
10. **`ASSET_IO_EXECUTOR` / `FILM_IO_EXECUTOR` 仍是 DiscardPolicy**：`FILM_IO_EXECUTOR`（1 线程、256 队列）满时直接丢弃影片保存/录制任务，表现为「保存无声丢失」。默认并发下（每部影片的保存去抖 5 s、最多 256 排队）几乎不可达，但极端刷屏保存时需要改成「拒绝并回执」或「阻塞调用方」。阶段 3 未改这一点，留作后续。
11. **合并锁的粒度是 film+branch**：同一影片的不同分支可并行保存，同一分支严格串行且顺序为「解析→合并→写盘→广播」。若未来引入多分支同时写同一 `Film` 实例的路径，需要重新检查 `FilmBranchStore.working` 的引用语义。
12. **⚠️ 客户端改动丢失事件（2026-11-19 20:50:41）**：`src/client/java/mchorse/bbs_together/network/TogetherClientNetwork.java` 被回退为 HEAD 原始内容（205,309 B / 4271 行 / 全 CRLF，SHA-256 `9BC05C27DEC1C19FBE03156747F602B89B53CB2F7587DAA7FE1188986D73999C`），**阶段 0 的 `sendCrusher` 修复、阶段 1 的上传/清单 ACK 与重试、阶段 2 的 `FILM_PARSE_EXECUTOR`/`BoundedMap`/manifest 后台化全部丢失**。服务端 `TogetherServerNetwork.java`（212,349 B）与阶段 0 的 `PacketSizeGuard.java`/`CompressedServerPacketCrusher.java` 完好。发生时间与同时段的关键帧锁编辑重合，判断是编辑器按旧缓冲区覆盖保存所致。
    影响：该时间点之后的三次 `BUILD SUCCESSFUL` 复用了 20:49:43 的残留 class，**不能作为源码正确性的证据**；服务端此时会向不识别 `c_asset_upload_ack`/`c_asset_inventory_ack` 的客户端发包，功能上退化为「服务端单方面升级」。
    恢复判据：`scripts/check-client-restore.ps1` 应输出 `RESULT = PASS`，即 19 个符号（`uploadAcks`、`UploadAck`、`UploadRejectedException`、`handleAssetUploadAck`、`sendAssetUploadWithRetry`、`isTransientUploadCode`、`pendingReports`、`PendingReport`、`awaitReportAck`、`handleInventoryAck`、`handleAssetRejected`、`assetSendRetries`、`supportsInventoryAck`、`lastFilmInventoryAttempt`、`filmInventoryInFlight`、`FILM_PARSE_EXECUTOR`、`BoundedMap`、`LAST_SERVER_MANIFEST_MAX`、`applyAssetManifest`）全部存在，且 `PacketSizeGuard\.fits` 出现次数为 0。恢复后**必须重跑三个构建**并在干净构建上验证（先 `--stop`，必要时删除 `build*/classes` 对应目录），否则又会读到残留 class。
    **处置（2026-11-19 23:08）**：用户选择「重新实现阶段 1+2」，已按本表设计在新树上重写客户端全部改动；服务端阶段 1/2 亦为同批重写。三个目标 `build` / `build12111` / `build1204` 分开重跑均 BUILD SUCCESSFUL，且已核对产物 class 时间戳（`TogetherClientNetwork$UploadAck.class` 等生成于源码 mtime 之后）与 jar 内容，确认非残留 class 复用。`scripts/check-client-restore.ps1` 已在验证后删除。
13. **「Dropped oversized chunk for s_film_save_sync (32903/33091 bytes)」根因（2026-11-19 23:15）**：该日志来自用户 22:11 的旧客户端。数值恰好略高于 32,767 而非远超，说明它不是分片后的单片，而是**整包前置检查**在分片前对 `film.toData()` 的整包长度做判定后整体丢弃（约 33 KB 的影片）。这正是阶段 0 本应删除、但在「阶段 1+2 重写」时客户端漏删的两处 `sendCrusher` 整包检查（`TogetherClientNetwork.java` 原 `:2201`/`:2213`）。**已补删**：两个 `sendCrusher` 重载现在只保留 `synchronized (crusher)`，不再做整包判定；整包大小由 `Compressed*PacketCrusher.sendBuffer` 对**分片后的单片**把关（单片 = 16 字节头 + ≤30,000 数据 = ≤30,016，远低于 32,767）。
14. **「尾部元数据撑爆末片」这一同源隐患（2026-11-19 23:15）**：`PacketCrusher.send` 把 consumer 写的尾部元数据**整段追加到最后一帧**，因此当某通道把变长数据放在尾部时，末片 = 16 头 + 单片数据 + 尾部，可能超过 32,767 被丢弃。排查后：
    - `s_undo_sync`（原 `TogetherClientNetwork.java:2967`）把整个撤销增量（多个 clip、含 `data`/`expected` 序列化）放在尾部、payload 为空 → **高概率触发**。**已修**：客户端把撤销布局序列化进**被分片的 payload**（`PacketByteBufs` 拼装后取 `byte[]`），尾部为空；服务端 `handleUndoSync` 改为从 `bytes`（payload）读取布局，`bytes == null` 直接返回。
    - `s_commit_create`（原 `:3775`）尾部写用户提交信息，无长度上限。**已修**：客户端把 message 截断到服务端实际只读的 240 字符，避免与满片 payload 叠加越界。
    - `s_film_data_sync` / `s_film_save_sync` / `s_master_done` / `s_recording_finished` / `s_coop_respond` / 资产上传：尾部只有 filmId/branch/path/若干 int（≤~200 B），数据体本身在 payload 中分片，安全。
    结论：修复后不存在「单片 + 尾部 > 32,767」的可达路径；如需进一步兜底，可在 `send()` 前把任意变长数据一律走 payload，但当前各通道已满足。
    上述修复后 `build`/`build12111`/`build1204` 均 BUILD SUCCESSFUL（产物 `together-link-2.1-1.20.1-1.20.4.jar` 871,406 B / `-1.21.11.jar` 876,248 B / `-1.20.4-verify.jar` 871,293 B，23:16）。
15. **阶段 3 实施（2026-11-19 23:48）**：① 协议双向比较：`handleHello` 读客户端协议后与 `PROTOCOL_VERSION` 比较并记录日志（`MIN_SUPPORTED_PROTOCOL=3`，不硬踢）；② 增长型直发改走 `sendChunked`：`CLIENT_PLAYER_COLORS`、`CLIENT_CURSOR_POSITIONS`（3 处）、`CLIENT_FILM_SELECTIONS`、`CLIENT_REPLAY_SELECTIONS`、`CLIENT_REPLAY_FORM_LOCKS`、`CLIENT_FILM_SCREENS`、`CLIENT_ASSET_LOCKS`、`CLIENT_COMMIT_SELECTIONS`、`CLIENT_PLAYER_COMMIT_POSITIONS`，其余定长小包保持直发；③ `CompressedClientPacketCrusher.receive` 加固为与服务端同构（头部校验、`MAX_CHUNKS=8192`、`MAX_OPEN_TRANSFERS=32`、`MAX_TOTAL_BYTES=64 MiB`、初始缓冲封顶、60 s 超时、`{69}` 哨兵），`reset` 清表。三个目标 `build`/`build12111`/`build1204` 分开重跑均 BUILD SUCCESSFUL（`together-link-2.1-1.20.1-1.20.4.jar` 873,365 B / `-1.21.11.jar` 878,203 B / `-1.20.4-verify.jar` 873,250 B）。README（中/英）已同步本轮修复与 v2.1 更新日志。阶段 3 第 4 条（上传临时文件清理 / 非原子文件系统降级）仍未做。

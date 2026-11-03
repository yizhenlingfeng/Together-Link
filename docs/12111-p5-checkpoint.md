# Together Link 1.21.11 — P5 传输可观测性检查点

日期：2026-11-03

## 范围

P5 的目标是让 1.21.11 兼容目标的资产传输具备可观测性，并验证大资产传输路径。本轮完成：

- 结构化 egress / ingress 传输日志；
- 传输计数器与 `/bbstogether diag` 的传输指标行；
- 大资产（9 MB）下载实测；
- 传输汇总日志首次触发时机修复；
- 服务端 ingress 预算（`asset_ingress_kbps`，默认 0 = 不限制）。

本轮不改变资产同步协议。客户端上传限速仍由 `TogetherSettings.asset_upload_kbps` 控制；服务端新增的 `asset_ingress_kbps` 是不可被客户端绕过的服务端入口总闸。

## 代码变更

### `TransferLog`

新增 `src/main/java/mchorse/bbs_together/network/TransferLog.java`：

- SLF4J logger 名称：`bbs_together/transfer`。
- 计数器：egress completed / failed / bytes / budget-wait，ingress completed / bytes。
- `begin(direction, player, path, bytes)` 返回开始时间戳。
- `complete(...)`：普通传输记 DEBUG；耗时 ≥ 2 s、大小 ≥ 8 MB 或预算等待 ≥ 500 ms 时记 INFO。
- `failed(...)`：WARN，带异常类型和消息。
- `ingress(...)`：接受的变更上传记 INFO，内容未变的重复上传记 DEBUG。
- `transfer-summary` 最多每 60 s 一条，包含累计完成数、失败数、字节数、平均 KiB/s 与预算等待毫秒。
- `reset()` 随服务端状态重置调用。

格式示例：

```text
transfer-complete direction=egress player=<uuid> path=p5-large-test.txt bytes=9437184 elapsed_ms=84 budget_wait_ms=0
transfer-summary egress_completed=12 egress_failed=0 ingress_completed=0 bytes=9567441 avg_kib_s=72 budget_wait_ms=0
```

### 接入点

`src/main/java/mchorse/bbs_together/network/TogetherServerNetwork.java`：

- `resetState()` 调用 `TransferLog.reset()`；
- `diagLines()` 新增 `bbs_together.diag.transfer`；
- `sendTaskInBatches()` 在切片前 `begin`，循环后 `complete`，被中断时 `failed`，并累计 `transferBudgetWaitNs`；
- `finalizeUpload()` 在完成后调用 `ingress`。

语言文件新增 `bbs_together.diag.transfer`：

- `src/client/resources/assets/bbs_together/lang/en_us.json`
- `src/client/resources/assets/bbs_together/lang/zh_cn.json`

### 服务端 ingress 预算

新增 `src/main/java/mchorse/bbs_together/network/AssetIngressBudget.java`，与 `AssetEgressBudget` 共用 `TokenBucket` 思路。配置项：

```text
server.asset_ingress_kbps = 0   # KiB/s，0 = 不限制
```

`handleAssetUpload` 在异步写入线程里、校验通过后、落盘前调用 `AssetIngressBudget.reserve(bytes.length)`；非零等待用 `TimeUnit.NANOSECONDS.sleep` 消化。因为写入线程只有一个，这个预算天然是全局串行的，不会阻塞服务端主线程。客户端 `asset_upload_kbps` 仍保留，作为协作式客户端的第一道节流；服务端预算是防改包/恶意客户端的最后一道总闸。

### 首次汇总修复

`TransferLog.LAST_SUMMARY_AT` 原先初始化为 `System.currentTimeMillis()`。类在首次传输时才加载，因此首次 `complete()` 后 `now - last == 0`，首条汇总被吞掉。改为 `0L` 后，首次完成即可输出汇总；`reset()` 仍把时间设为当前时间，避免重置后立刻刷一条空汇总。

另外 `ingressBegin()` 会在首个上传切片到达时写入 `FIRST_START_NS`，`ingress()` 只负责完成计数。这样“只有上传、没有下载”的会话也能得到有意义的平均吞吐，而不是把完成瞬间当成开始时间。

## 验证

### 构建

```powershell
.\gradlew.bat build --console=plain
.\gradlew.bat build1204 --console=plain
.\gradlew.bat build12111 --console=plain
```

三个目标均 `BUILD SUCCESSFUL`。PowerShell 可能因 Gradle 写 stderr 显示退出码 1，以日志末尾的 `BUILD SUCCESSFUL` 为准。

产物：

| 产物 | 大小 | SHA-256 |
|---|---:|---|
| `build/libs/together-link-2.0.3-1.20.1-1.20.4.jar` | 756,922 | `3BBB76579AAB19363E19700CB2B11A8016A8F8FC2BBFFCBACD4801035AD5D8A0` |
| `build-1.20.4/libs/together-link-2.0.3-1.20.4-verify.jar` | 757,560 | `E73B03532E2CE97881D1A8AE47E6610A1AAFDA68CB093C0F3763020D31CB8F65` |
| `build-1.21.11/libs/together-link-2.0.3-1.21.11.jar` | 758,121 | `C7CD70DD96960AC04B787F2AF82C8E05690A7C7A35DA3F92259DCC0CF4572FFD` |

### 启动冒烟

新增 `asset_ingress_kbps` 后启动 1.21.11 服务端，`Done (0.476s)!`，44 个 mod 正常加载，设置项注册无异常。

### 服务端 ingress 实测

`run-1.21.11-server/config/bbs/settings/bbs_together.json` 设置 `asset_ingress_kbps=1024`，客户端放置 2 MiB 的 `p5-ingress-test.bin` 并由 `P5Ingress` 账号上传。服务端观察到 `AssetIngressBudget.reserve(2097152)` 返回约 1750 ms 等待，实际写完/完成日志比上传开始晚约 2 s，`transfer-ingress ... bytes=2097152 result=accepted` 正常输出，说明全局 ingress 闸门确实生效。

> 注意：PowerShell 写 JSON 设置文件时必须使用 UTF-8 **无 BOM**。带 BOM 时 BBS 的 `DataToString.read` 会静默失败，`bbs_together.json` 整体回落默认值（`asset_ingress_kbps` 会读成 0），表现为“设置了但不限速”。本次实测踩到过这个坑，已用 `UTF8Encoding(false)` 重写后验证。

上面的 1750 ms 证据来自 `ingressBegin()` 指标修正之前的构建；`ingressBegin()` 只改首次开始时间戳，不改变 `AssetIngressBudget.reserve` 的限速逻辑。修正后三个目标已重新构建通过，未再次跑上传。

### 大资产实测

1. 启动 1.21.11 开发服务器：

   ```powershell
   .\gradlew.bat runServer12111 --console=plain
   ```

2. 在 `run-1.21.11-server/config/bbs/assets/` 放置 9 MB 的 `p5-large-test.txt`，删除客户端同名文件。

3. 启动客户端并直连：

   ```powershell
   .\gradlew.bat runClient "-Pmcver=1.21.11" "--args=--quickPlayMultiplayer 127.0.0.1:25565"
   ```

4. 客户端日志：

   ```text
   [BBS Assets] Downloaded: p5-large-test.txt
   ```

5. 服务端日志：

   ```text
   transfer-complete direction=egress ... path=p5-large-test.txt bytes=9437184 elapsed_ms=84 budget_wait_ms=0
   transfer-summary egress_completed=12 egress_failed=0 ingress_completed=0 bytes=9567441 avg_kib_s=72 budget_wait_ms=0
   ```

结论：大资产走 `TogetherServerNetwork.sendTaskInBatches`，完成日志与汇总日志均正常；同一批小文件也被累计进汇总。

### 两个客户端

`build.gradle` 额外注册了 `client2` 运行配置，使用独立目录 `run-1.21.11-client-b/`，避免两个客户端争用同一批 processed-mod jar。嵌套任务会把 `-PjoinServer=` 原样转发给内层 Gradle：

```powershell
.\gradlew.bat runServer12111 --console=plain
.\gradlew.bat runClient12111  '-PjoinServer=127.0.0.1:25565'
.\gradlew.bat runClient12111B '-PjoinServer=127.0.0.1:25565'
```

第二次启动会自动生成 `run-1.21.11-client-b/`。2026-11-03 23:31:54 / 23:32:31 实测两个客户端（Player458、Player779）先后加入同一服务端，保持连接超过 12 秒，服务端无 payload/ClassCast/encode/mixin 错误；这证明双客户端入口和独立 run 目录可用。30 分钟长连接与锁竞争仍未压测。

## 已知限制

- 汇总最多每 60 s 一条；普通传输本身是 DEBUG，需要看单文件明细时把 `bbs_together/transfer` 调到 DEBUG。
- 服务端 ingress 预算已实现为全局串行总闸（`asset_ingress_kbps`），未做按玩家配额；写入线程只有一个，因此单个大上传会短暂占用所有上传带宽，符合“总闸”定位。
- 两个客户端入口（`runClient12111B` / `runClient2`）与 `-PjoinServer` 转发已实测加入成功；30 分钟长连接、持续资产同步与锁竞争仍未测。
- 1.21.11 仍是实验性兼容目标，不承诺与 1.20 世界存档互通。

## 相关文件

- `src/main/java/mchorse/bbs_together/network/TransferLog.java`
- `src/main/java/mchorse/bbs_together/network/AssetIngressBudget.java`
- `src/main/java/mchorse/bbs_together/network/TogetherServerNetwork.java`
- `src/main/java/mchorse/bbs_together/TogetherSettings.java`
- `src/client/resources/assets/bbs_together/lang/en_us.json`
- `src/client/resources/assets/bbs_together/lang/zh_cn.json`

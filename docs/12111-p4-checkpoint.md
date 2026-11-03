# 1.21.11 P4 网络 / 稳定性加固检查点

日期：2026-11-03
范围：P4——启动期 payload 注册、分块传输总字节上限、1.20 网络回归、重连验证。
状态：**P4 代码改动完成，三个构建通过；1.21.11 启动期注册与 1.20.4 加入/重连均通过。长时间双客户端、大影片/大资产、锁竞争与限流仍属于后续压力测试。**

## 1. 本轮完成内容

### 1.1 启动期注册全部 payload

此前 `TogetherPayload` 只在第一次 `register`/`send` 时懒注册。首次连接时如果服务端先发 S2C 包，而服务端还没注册对应 codec，会出现 `TogetherPayload cannot be cast to UnknownCustomPayload`。P4 改为启动期统一注册：

- `src/main/java-1.21.11/mchorse/bbs_together/network/TogetherPayloads.java`
  - 新增 `registerAll(Class<?> channelHolder)`：遍历该类的 `static final Identifier` 字段，逐个 `ensureRegistered`。
  - `ensureRegistered` 仍同时注册 `PayloadTypeRegistry.playC2S()` 与 `playS2C()`，并用 `HashSet<Identifier>` 幂等；这样客户端、服务端和集成服务器都不会重复注册。
- `TogetherServerNetworking.preRegister()` 注册 `TogetherServerNetwork` 的全部 channel；`TogetherClientNetworking.preRegister()` 注册 `TogetherClientNetwork` 的全部 channel。
- 1.20 的 `src/main/java-1.20/.../TogetherServerNetworking.java`、`src/client/java-1.20/.../TogetherClientNetworking.java` 提供空 `preRegister()`，保持 1.20 构建不变。
- `TogetherServerNetwork.setup()`、`TogetherClientNetwork.setup()` 的第一条语句改为 `preRegister()`。

### 1.2 分块传输总字节上限

`src/main/java/mchorse/bbs_together/network/SafeServerPacketCrusher.java`：

- `MAX_CHUNKS = 8192`。
- `MAX_OPEN_TRANSFERS = 32`（原 64）。
- `MAX_TOTAL_BYTES = 64L * 1024L * 1024L`（64 MB）。
- `TRANSFER_TIMEOUT_MS = 60_000L`。
- `INITIAL_BUFFER_CAP = 1024 * 1024`（原 4 MB）。
- 新增 `Map<Integer, Long> safeBytes`：每个 chunk 累加已接收字节；`received + size > MAX_TOTAL_BYTES` 时丢弃整个 transfer，防止只按 `total * BUFFER_SIZE` 预分配造成内存放大。
- 新增 `discard(int id)`；完成、淘汰、超时、`reset()` 都清理 `safeBytes`，避免重连后残留计数。

### 1.3 构建结果

| 命令 | 结果 | 产物 |
|---|---|---|
| `.\gradlew.bat build` | BUILD SUCCESSFUL | `build/libs/together-link-2.0.2-1.20.1-1.20.4.jar`，751,870 bytes |
| `.\gradlew.bat build1204` | BUILD SUCCESSFUL | `build-1.20.4/libs/together-link-2.0.2-1.20.4-verify.jar`，752,474 bytes |
| `.\gradlew.bat build12111` | BUILD SUCCESSFUL | `build-1.21.11/libs/together-link-2.0.2-1.21.11.jar`，753,097 bytes |

SHA256：

- 1.20.1 通用：`E6BDA7724B0EBC0115F36BC5946CF48328ABBA1F213ECBC863683D069D4EA6AD`
- 1.20.4 验证：`B5526E9E28EA4188F4369728A272BD70019C41BE25F79E57809EDB0FD7F8B472`
- 1.21.11：`93063AC1F0D8936CDDCD64FDD8F50C0C3378EF5BE6DFB03D47D8DA1B33C1132E`

## 2. 运行验证

### 2.1 1.21.11 启动期注册 + 加入

```powershell
.\gradlew.bat runServer12111
.\gradlew.bat runClient '-Pmcver=1.21.11' '--args=--quickPlayMultiplayer 127.0.0.1:25565'
```

结果：

- 服务端 44 mods，`Done (0.524s)!`。
- 客户端 52 mods，dashboard 面板全部构建；`Player292` 在 01:45:42 加入。
- 服务端/客户端均无 `Unknown payload`、`ClassCastException`、`Failed to encode packet`。
- 客户端 01:47:29 正常 `Stopping!`；服务端 01:47:28 记录 `Player292 lost connection: Disconnected`，没有 crash。

### 2.2 1.20.4 网络回归 + 重连

1.20.1 的 `runClient` 和 `runServer` 共用 `run/`，同时启动会锁住 `run/.fabric/processedMods/bbs_chatsync-*.jar`，这是开发环境目录冲突，不是代码错误。网络回归改用 1.20.4 的独立运行目录：

```powershell
.\gradlew.bat runServer1204
.\gradlew.bat runClient '-Pmcver=1.20.4' '--args=--quickPlayMultiplayer 127.0.0.1:25565'
```

结果：

- 服务端 1.20.4、45 mods，`Done (5.616s)!`。
- 首次加入：`Player643 logged in` / `Player643 joined the game`，01:55:03。
- 断开：01:57:35 `Player643 lost connection: Disconnected`。
- 重连：`Player962` 01:58:38 加入；客户端重新下载 94 个资产，dashboard 正常；01:59:40 正常断开。
- 双端均无 `Unknown payload`、`ClassCastException`、`Failed to encode packet`。
- 客户端出现的 `[BBS Together] 上传被拒绝：你的 BBS 权限是只读（等级 0）` 是预期权限提示（该测试账号未被 `/bbsop` 授权），不是网络错误。
- BBS 模型加载器的 `FileNotFoundException` / `DataParser` 解析报错来自测试资产本身（缺 `.vox` 或 `.bbs.json` 内容不完整），属于 BBS 侧内容问题，未导致 Together 网络失败。

### 2.3 1.20.1 说明

- 1.20.1 构建通过，P3 阶段 1.20.1 客户端已进入集成世界。
- 1.20.1 专用服务器 + 客户端**不能同时用同一个 `run/` 目录**；要做 1.20.1 双端连接，需要给其中一个进程单独的运行目录，或先停服再测客户端。当前用 1.20.4 双端连接覆盖共享网络适配层。

## 3. 测试方式（交给用户）

1. 三个构建分开跑：

```powershell
.\gradlew.bat build
.\gradlew.bat build1204
.\gradlew.bat build12111
```

2. 1.21.11 双端：

```powershell
.\gradlew.bat runServer12111
.\gradlew.bat runClient '-Pmcver=1.21.11' '--args=--quickPlayMultiplayer 127.0.0.1:25565'
```

检查 dashboard、影片、模型方块、锁定、提交、coop 面板；断开再连一次；确认 `latest.log` 没有 payload/ClassCastException。

3. 1.20.4 回归：

```powershell
.\gradlew.bat runServer1204
.\gradlew.bat runClient '-Pmcver=1.20.4' '--args=--quickPlayMultiplayer 127.0.0.1:25565'
```

4. 长时间/压力测试（尚未做，建议下一步）：
   - 两个客户端同时在线 30 分钟；
   - 大影片、大模型资产上传/下载；
   - 锁竞争、提交同步、断线重连后状态恢复；
   - 观察服务端 `SafeServerPacketCrusher` 是否触发 64 MB 上限或 60 秒超时。

## 4. 已知限制

- 启动期注册目前只扫描 `TogetherServerNetwork` / `TogetherClientNetwork` 里的 `static final Identifier` 字段；如果后续新增 channel 不在这些类里，需要显式加入 `registerAll` 的 holder。
- `MAX_TOTAL_BYTES` 是单玩家、单 transfer 的上限，不是全局带宽限制；全局出站限速由 `AssetEgressBudget` 提供（默认 0 = 不限速），入站上传限速由 P5 的 `AssetIngressBudget` 提供（`asset_ingress_kbps`，默认 0）。
- 1.20.1 双端同时运行仍受共享 `run/` 目录限制。
- 1.20 与 1.21.11 的世界存档互通仍未承诺。
- P3 的锁定徽章、玩家隐藏、关键帧高亮、真实皮肤头像仍需人工视觉验收。

## 5. 下一步

- 两客户端 30 分钟、锁竞争、提交同步的压力测试。
- P5 已完成结构化传输日志与传输指标（见 `docs/12111-p5-checkpoint.md`）；服务端 ingress 限速已在 P5 实现（`asset_ingress_kbps`，默认 0）。
- 1.20.1 独立 run 目录的双端回归（可选）。
- 根据压力测试结果再决定是否需要调低 `MAX_TOTAL_BYTES` 或增加并发 transfer 上限。

## 6. 追加修复：1.21.11 Mixin 配置未声明

- 现象：模型方块面板的“锁定编辑”一直不显示；面板本身和 `UIModelBlockLockSection` 均存在，服务端 BBS 等级已设为 2。
- 根因：`src/main/resources-1.21.11/fabric.mod.json` 只声明了入口点，没有声明 `mixins`。Fabric 因此从未加载 `bbs-together.mixins.json` / `bbs-together.client.mixins.json`，1.21.11 下 10 个 main + 43 个 client Mixin 实际全部未生效；`UIModelBlockPanelBbsOpMixin` 自然没有插入锁定区。
- 修复：在 `fabric.mod.json` 增加：
  ```json
  "mixins": [
      "bbs-together.mixins.json",
      { "config": "bbs-together.client.mixins.json", "environment": "client" }
  ]
  ```
  同时保留 `UIModelBlockPanelBbsOpMixin` 的刷新修复：权限等级或锁数量变化时，已打开的面板会重新 `refresh()` 锁定区。
- 验证：启用后客户端启动日志出现 `[TogetherDebug] lock section ctor...` 与 `lock refresh connected=true level=0 ...`，证明 Mixin 已实际注入；服务端可正常启动，客户端可正常加入。调试输出已删除。
- 影响：P3 的锁定徽章、玩家隐藏、关键帧高亮、皮肤头像等功能此前是在 Mixin 未加载的状态下观察的，需在 Mixin 真正生效后重新做视觉验收。
- 最终构建：1.20.1 `752,048` B、1.20.4 `752,647` B、1.21.11 `753,321` B；均 BUILD SUCCESSFUL。

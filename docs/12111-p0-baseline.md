# 1.21.11 内容适配 P0 基线报告

日期：2026-11-02
阶段：P0（基线冻结 + 编译探测）
状态：完成，未修改仓库源码

## 1. 基线

- 分支：`main`
- 基线提交：`de0a201`
- 基线标签：`pre-12111-content`
- 工作区原本已有未提交改动（英文化注释、1.21.11 框架文件、`docs/i18n.md` 等），P0 未提交、未覆盖这些改动。
- 基线构建（分开执行）：
  - `.\gradlew.bat build`：成功，1.20.1 universal jar。
  - `.\gradlew.bat build1204`：成功，1.20.4 验证 jar。
  - `.\gradlew.bat build12111`：成功，1.21.11 框架 jar。
- 注意：`build build1204 build12111` 不能放在同一条命令里。嵌套 `GradleBuild` 会报
  `Included build ... has build path :bbs-together which is the same as included build ...`，
  这是任务结构问题，不是编译失败。三个构建必须分开跑。

## 2. 编译探测方法

在临时副本中做，避免污染仓库：

1. 复制 `src/`、`gradle/`、`build.gradle`、`settings.gradle`、`gradle.properties`、wrapper 到
   `%TEMP%\bbs-together-probe-12111`。
2. 临时把 1.21.11 的 source dirs 从“仅 stub”改成“common + overlay”。
3. 删除临时副本里的 4 个框架 stub 入口点（避免与 common 入口点重复）。
4. 执行：

```powershell
.\gradlew.bat compileJava '-Pmcver=1.21.11'
```

结果：**main 侧 100 个编译错误**。`compileClientJava` 依赖 `compileJava`，因此 client 侧错误清单尚未暴露。

## 3. 主要错误分类

受影响文件包括：

- `src/main/java/mchorse/bbs_together/network/TogetherServerNetwork.java`
- `src/main/java/mchorse/bbs_together/bbsop/BbsOpManager.java`
- `src/main/java/mchorse/bbs_together/BBSTogetherFix28.java`
- `src/main/java/mchorse/bbs_together/BBSTogetherFix28Server.java`
- `src/main/java/mchorse/bbs_together/BBSTogetherMod.java`
- `src/main/java/mchorse/bbs_together/behaviors/BehaviorLogManager.java`
- `src/main/java/mchorse/bbs_together/locks/AssetLockManager.java`
- `src/main/java/mchorse/bbs_together/mixin/BBSCommandsMixin.java`
- `src/main/java/mchorse/bbs_together/network/CoopSessionManager.java`
- `src/main/java/mchorse/bbs_together/network/ModelBlockPacketGuard.java`

分类：

1. `Identifier(String, String)` 已是 private，旧构造器全部失效（103 处）。
2. `GameProfile` 在 1.21.11 中变为 record，旧 `getName()` / `getId()` 不存在。
3. `PlayerManager` 的 OP 接口改为 `PlayerConfigEntry`。
4. `MinecraftServer.getUserCache()` 移除。
5. `ServerPlayerEntity.getServer()` / `getWorld()` 移除。
6. `ServerCommandSource.hasPermissionLevel(int)` 移除。
7. 旧版 `ServerPlayNetworking` / `ClientPlayNetworking` 的 `send` 与 `registerGlobalReceiver` 签名不兼容。
8. `BlockEntity.toInitialChunkDataNbt()` 现在需要 `RegistryWrapper.WrapperLookup`。

## 4. 1.21.11 API 替换表

| 1.20 用法 | 1.21.11 用法 | 备注 |
|---|---|---|
| `new Identifier(ns, path)` | `Identifier.of(ns, path)` | 1.20.1 也支持 `Identifier.of`，可全量机械替换 |
| `GameProfile.getName()` | `GameProfile.name()` | 1.21.11 使用 authlib 7 record |
| `GameProfile.getId()` | `GameProfile.id()` | 同上 |
| `PlayerManager.isOperator(GameProfile)` | `isOperator(PlayerConfigEntry)` | 用 `new PlayerConfigEntry(profile)` 包装 |
| `addToOperators/removeFromOperators(GameProfile)` | 同上，传 `PlayerConfigEntry` | 同上 |
| `MinecraftServer.getUserCache()` | `server.getApiServices().nameToIdCache()` | 返回 `Optional<PlayerConfigEntry>` |
| `ServerPlayerEntity.getServer()` | `player.getEntityWorld().getServer()` | 或 `ServerWorld.getServer()` |
| `ServerPlayerEntity.getWorld()` | `player.getEntityWorld()` | |
| `ServerCommandSource.hasPermissionLevel(int)` | `CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK)` | 作为 `requires(...)` 的 predicate |
| `BlockEntity.toInitialChunkDataNbt()` | `toInitialChunkDataNbt(entity.getEntityWorld().getRegistryManager())` | `DynamicRegistryManager` 继承 `WrapperLookup` |
| `ServerPlayNetworking.send(player, id, buf)` | `ServerPlayNetworking.send(player, TogetherPayload)` | 需 payload 适配层 |
| `ServerPlayNetworking.registerGlobalReceiver(Identifier, ...)` | `registerGlobalReceiver(CustomPayload.Id, ...)` | 需提前 `PayloadTypeRegistry` 注册 |
| `ClientPlayNetworking.send(id, buf)` | `ClientPlayNetworking.send(TogetherPayload)` | 同上 |
| `ClientPlayNetworking.registerGlobalReceiver(Identifier, ...)` | `registerGlobalReceiver(CustomPayload.Id, ...)` | 同上 |
| `ClientPlayNetworking.canSend(Identifier)` | `ClientPlayNetworking.canSend(CustomPayload.Id)` | 同上 |

## 5. 网络适配层设计

为了不重写 103 个接收器 lambda 和 76 个发送点，采用“同签名包装器”：

- `TogetherServerNetworking.register(Identifier, Receiver)`
  - `Receiver` 保持旧的 5 参数形状：
    `(MinecraftServer, ServerPlayerEntity, ServerPlayNetworkHandler, PacketByteBuf, PacketSender)`
- `TogetherClientNetworking.register(Identifier, Receiver)`
  - `Receiver` 保持旧的 4 参数形状：
    `(MinecraftClient, ClientPlayNetworkHandler, PacketByteBuf, PacketSender)`
- 发送：`send(...)` / `canSend(...)`
- 1.20 实现直接委托旧 Fabric API。
- 1.21.11 overlay 实现：
  - `TogetherPayload` 复刻 BBS 的 `BufPayload` record；
  - `PayloadTypeRegistry` 在注册接收器时同步注册；
  - 发送时 `TogetherPayload.from(buf, id)`。

调用点只需把类名替换成包装器，业务 lambda 与 `PacketByteBuf` 处理保持原样。

## 6. 对计划的影响

- P2 从“纯机械替换”增加为“机械替换 + API 兼容层”，预计多 2–4 人日。
- 总估算从 31–47 人日调整为约 34–52 人日。
- P1 的目标调整为：source set 叠加、排除未移植文件、1.21.11 mixin 配置、P0 报告；
  不再承诺 P1 单独编译通过。
- 编译通过的目标放到 P2 末尾，作为第一个可交给用户测试的联机检查点。

## 7. 下一步

1. 修改 `build.gradle`：1.21.11 使用 common + overlay + exclusion list。
2. 删除 4 个 1.21.11 框架 stub 入口点。
3. 增加 1.21.11 专用 mixin JSON（先排除已知未移植的 4 个 red 文件）。
4. 增加 `PlayerCompat`、`TogetherServerNetworking`、`TogetherClientNetworking`、`TogetherPayload`。
5. 全量替换 `Identifier` 构造器与旧网络调用。
6. 逐个修复 main 编译错误，再暴露 client 错误，直到 `build12111` 通过。

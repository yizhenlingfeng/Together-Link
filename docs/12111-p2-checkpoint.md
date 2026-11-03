# 1.21.11 P1/P2 编译与联机检查点

日期：2026-11-02
范围：P1（公共源码 + 1.21.11 overlay 构建骨架）与 P2（网络 / API 机械迁移）
状态：**编译通过，客户端可启动，专用服务器可启动并成功加入**。P3 的渲染/Mixin 还原尚未开始。

## 1. 本轮完成内容

### 构建骨架

- `src/main/java`、`src/client/java` 继续作为 1.20 共享源码。
- 新增 `src/main/java-1.20`、`src/client/java-1.20`：只放 1.20 专用适配器。
- 新增 `src/main/java-1.21.11`、`src/client/java-1.21.11`：1.21.11 的薄 overlay。
- 1.21.11 构建使用：共享源码 + overlay；1.20 构建使用：共享源码 + `java-1.20`。
- 1.21.11 使用独立 mixin 配置：
  - `src/main/resources-1.21.11/bbs-together.mixins.json`（10 个主端 Mixin）
  - `src/client/resources-1.21.11/bbs-together.client.mixins.json`（41 个客户端 Mixin）
  - `compatibilityLevel` 由构建替换为 `JAVA_21`。
- 四个 framework-only 入口 stub 已删除，1.21.11 直接使用公共入口类。

### 网络层

- 新增 `TogetherPayload`：1.21.11 的 `CustomPayload` 包装，内部仍是 `PacketByteBuf` 字节。
- 新增 `TogetherPayloads`：按 channel 注册 C2S/S2C codec，使用 `HashSet` 防重复。
- 新增 `TogetherServerNetworking` / `TogetherClientNetworking`：
  - 1.20 实现位于 `java-1.20`，委托旧 Fabric networking API。
  - 1.21.11 实现位于 overlay，使用 payload API，但保留旧的 receiver lambda 形状。
- 已替换：
  - 103 处 `registerGlobalReceiver` → facade。
  - 76 处 `send` → facade。
  - 103 处 `new Identifier(...)` → `Identifier.of(...)`。
- `CompressedServerPacketCrusher` / `CompressedClientPacketCrusher` 重写 `sendBuffer`，避免 BBS 的 `BufPayload` 被错误用于 Together channel。
- payload 类型在首次 `register` / `send` 时懒注册；联机测试已通过。P4 会改成启动期统一注册，避免首包时序风险。

### API 桥接

- `PlayerCompat`：`GameProfile`（authlib 7 record）、`PlayerConfigEntry`、OP 查询/设置、`UserCache` / `NameToIdCache`、`ServerWorld`。
- `CommandCompat`：`hasPermissionLevel` → `PermissionCheck.allows`。
- `NbtCompat`：`toInitialChunkDataNbt` 的 `RegistryWrapper.WrapperLookup` 参数。
- `ClientCompat`：1.21.11 overlay 直接调用 `getDebugHud().shouldShowDebugHud()`、`getSkinTextures().body().texturePath()`、`getRenderTickCounter().getDynamicDeltaTicks()`。
- 1.20 的反射版 `ClientCompat`、旧版 `PlayerCompat`/`CommandCompat`/`NbtCompat` 已移到 `java-1.20`。

### 暂时排除的渲染 / Mixin

以下文件尚未移植，1.21.11 不编译、不从 mixin JSON 加载：

- `ModelBlockEntityRendererBbsOpMixin`：1.21.11 改为 `ModelBlockRenderState` 渲染。
- `MorphRendererMixin`：`renderPlayer` 已不存在，需要改为 `collectPlayer`。
- `ModelBlockLockRenderer`：immediate-mode `RenderSystem` / `Tessellator` 路径已移除。
- `UIKeyframeDopeSheetMixin`：`BufferBuilder, Matrix4f` → `VertexConsumer, Matrix3x2fc`。

因此本检查点中：

- 模型方块锁定徽章不显示。
- 回放/影片中的玩家隐藏逻辑不生效。
- 关键帧 dope sheet 的高亮不生效。
- `UIPlayerFaceRenderer` 使用 1.21.11 overlay 占位版本，玩家头像显示占位图标。

这些是 P3 的内容，不代表构建失败。

## 2. 已验证结果

| 项目 | 结果 |
|---|---|
| `.\gradlew.bat build`（1.20.1） | BUILD SUCCESSFUL |
| `.\gradlew.bat build1204`（1.20.4） | BUILD SUCCESSFUL |
| `.\gradlew.bat build12111`（1.21.11） | BUILD SUCCESSFUL |
| 1.21.11 开发客户端 | 启动到主菜单，资源包加载正常 |
| 1.21.11 专用服务器 | 启动完成 `Done (...)!`，无 Mixin 应用失败 |
| 1.21.11 客户端加入服务器 | 成功加入；服务端日志 `Player968 joined the game` |
| 加入后的网络 | 无 `Unknown payload` / `ClassCastException`；BBS dashboard 面板正常构建 |

产物：

- `build/libs/together-link-2.0.2-1.20.1-1.20.4.jar`（751,540 bytes）
- `build-1.20.4/libs/together-link-2.0.2-1.20.4-verify.jar`（752,173 bytes）
- `build-1.21.11/libs/together-link-2.0.2-1.21.11.jar`（741,920 bytes）

## 3. 测试方式

三个构建必须**分开执行**，不要放进同一个 Gradle 调用（`build12111` 是嵌套 GradleBuild，合并运行会报 included build 同路径）。

```powershell
.\gradlew.bat build
.\gradlew.bat build1204
.\gradlew.bat build12111
```

### 3.1 客户端冒烟测试

```powershell
.\gradlew.bat runClient12111
```

检查：

1. 主菜单出现，无崩溃。
2. Mods 列表里 `bbs_together 2.0.2-1.21.11` 已加载。
3. 新建或进入一个世界。
4. 打开 BBS dashboard，确认 film / model blocks / particles / model editor / textures / audio / add-ons 面板能打开。
5. 执行 `/bbstogether diag`，确认命令注册和权限判断不报错。
6. 打开资产锁定、行为日志、提交/分支、coop 面板；本轮先看能否打开，不要求所有交互完整。

### 3.2 专用服务器联机测试

首次运行需要：

```powershell
# run-1.21.11-server/eula.txt
eula=true
```

开发环境建议离线模式：

```properties
# run-1.21.11-server/server.properties
online-mode=false
```

启动：

```powershell
.\gradlew.bat runServer12111
```

另开一个终端，快速连接：

```powershell
.\gradlew.bat runClient '-Pmcver=1.21.11' '--args=--quickPlayMultiplayer 127.0.0.1:25565'
```

或在游戏内 Multiplayer → Direct Connect → `127.0.0.1:25565`。

检查：

1. 能进入服务器，服务端出现 `joined the game`。
2. 客户端和服务端日志都没有 `Unknown payload`、`ClassCastException`、`Failed to encode packet`。
3. 打开 dashboard，确认面板数据能收到。
4. 打开 film 列表、提交/分支面板、资产锁定面板、行为日志面板。
5. 退出服务器再重新加入，确认没有卡在 “Syncing” 或重复注册异常。
6. 如果方便，用两个客户端同时加入，测试资产锁 / coop 邀请。

### 3.3 需要反馈的日志

如果有问题，请提供：

- `run-1.21.11-client/logs/latest.log`
- `run-1.21.11-server/logs/latest.log`
- `run-1.21.11-client/crash-reports/` 或 `run-1.21.11-server/crash-reports/` 下的新文件
- 具体操作步骤和界面截图

## 4. 已知限制 / P3 前的预期

- 模型方块锁定徽章、玩家隐藏、关键帧高亮、真实玩家头像不可用。
- 本轮只验证“能编译、能启动、能联机、dashboard 能加载”，没有逐项验证 film 同步、大型资产上传、提交合并、coop 分支切换。
- payload 类型懒注册目前可用，但 P4 会改为启动期注册全部 S2C channel。
- 不承诺 1.20 与 1.21.11 的世界存档互通；只以 BBS 影视/资产数据做后续兼容测试。
- 1.20.1 / 1.20.4 构建仍通过，但本轮改动触及公共网络代码，P4 需要做一次 1.20 联机回归。

## 5. 下一步 P3

1. 还原 `ModelBlockEntityRendererBbsOpMixin`（`ModelBlockRenderState`）。
2. 还原 `MorphRendererMixin`（`collectPlayer` / render queue）。
3. 重写 `ModelBlockLockRenderer`（HUD 或 1.21.11 render command）。
4. 迁移 `UIKeyframeDopeSheetMixin`（`VertexConsumer` / `Matrix3x2fc`）。
5. 恢复 `UIPlayerFaceRenderer` 的真实皮肤路径。
6. 重新审计 53 个 Mixin 的注入描述符。

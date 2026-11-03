# 1.21.11 P3 渲染 / Mixin 恢复检查点

日期：2026-11-03
范围：P3——恢复 1.21.11 下此前暂时禁用的四个渲染/Mixin 功能，并恢复真实皮肤头像。
状态：**1.20.1、1.20.4、1.21.11 三个构建均通过；1.21.11 客户端可启动并成功连接专用服务器；1.20.1 客户端可启动。视觉功能需要进入游戏验收。**

## 1. 本轮完成内容

### 1.1 版本源码拆分

1.20 与 1.21.11 的 Mixin 方法签名已经分叉，不能再用同一个源文件同时编译两个版本。当前布局：

- `src/main/java`、`src/client/java`：1.20 / 1.21.11 共享主体。
- `src/main/java-1.20`、`src/client/java-1.20`：只给 1.20 构建使用的适配器、旧渲染器和旧 Mixin。
- `src/main/java-1.21.11`、`src/client/java-1.21.11`：1.21.11 的薄 overlay。
- 1.20 构建使用“共享源码 + `java-1.20`”；1.21.11 构建使用“共享源码 + overlay”。
- `build.gradle` 中 1.21.11 只排除 `mchorse/bbs_together/mixin/ModelBlockEntityRendererBbsOpMixin.java`，其余 P1/P2 排除项已经恢复。
- `MorphRendererMixin`、`UIKeyframeDopeSheetMixin` 现在各有 1.20 和 1.21.11 两个版本，分别位于 `java-1.20` 与 `java-1.21.11`，不再覆盖共享文件。
- 1.20 版本从 `git show HEAD:...` 恢复；恢复时 PowerShell 的 `Set-Content -Encoding utf8` 会写入 BOM，导致 `非法字符: '\ufeff'`。已改用 `[System.IO.File]::WriteAllText(..., New-Object System.Text.UTF8Encoding($false))` 去除。

### 1.2 模型方块锁定徽章

1.21.11 的实体渲染器只负责把渲染命令排入队列，原来的 immediate-mode billboard 不能再用。处理方式：

- 1.20 的 `src/client/java-1.20/mchorse/bbs_together/render/ModelBlockLockRenderer.java` 保留原 immediate-mode 实现。
- 新增 1.21.11 overlay `src/client/java-1.21.11/mchorse/bbs_together/render/ModelBlockLockRenderer.java`：
  - 从 `WorldRenderEvents.AFTER_ENTITIES` 运行；
  - 读取 `AssetLockClient.all()`，过滤 `MODEL_BLOCK`，解析 `dimension|x|y|z`；
  - 只绘制已加载区块、64 格内、当前玩家可见/可管理的锁；
  - 用 `AdoptedTexture.identifier(...)` 把 BBS 图标图集注册成原版 `Identifier`，通过 `RenderLayers.entityTranslucent(...)` 绘制；
  - 摄像头朝向 billboard，偏移 0.55 格避免被方块遮挡；
  - 保持旧行为：仅当 F3 调试 HUD 可见时绘制。
- 新增 `src/client/java-1.21.11/mchorse/bbs_together/Together12111Client.java`，注册 `WorldRenderEvents.AFTER_ENTITIES`。
- `src/main/resources-1.21.11/fabric.mod.json` 的 `client` entrypoints 增加 `Together12111Client`。
- `AssetLockClient` 新增 `all()`，返回实时锁视图供渲染器遍历。
- `ModelBlockEntityRendererBbsOpMixin` 仍是 1.20 专用，1.21.11 不再加载。

### 1.3 玩家隐藏

- 1.21.11 `MorphRendererMixin` 改为注入 `MorphRenderer.collectPlayer(...)` HEAD，返回 `true` 抑制该玩家渲染。
- 保留原判断：`MorphRenderer.hidePlayer`、排除本地玩家、`TogetherClientNetwork.playersInFilmScreen` 中存在该玩家。
- 1.20 的 `renderPlayer` 注入版本已恢复到 `java-1.20`。

### 1.4 关键帧高亮

- 1.21.11 `UIKeyframeDopeSheetMixin` 改为 `VertexConsumer` / `Matrix3x2fc`：
  - 注入 `renderSheetKeyframeShapes` HEAD；
  - 调用 `UIKeyframeDopeSheet.renderShape(..., VertexConsumer, Matrix3x2fc, ...)`；
  - 保留 `ITogetherDopeSheet` 外部高亮 map。
- 1.20 的 `BufferBuilder` / `Matrix4f` 版本已恢复到 `java-1.20`。

### 1.5 真实皮肤头像

- 1.21.11 `UIPlayerFaceRenderer` 不再使用占位图：
  - `texture.getGlTexture() instanceof GlTexture` 后调用 `glTexture.getGlId()`；
  - 保留 1.20 的缓存/离线路径。
- 1.20 版本仍在 `java-1.20`，使用旧反射/旧纹理 API。

## 2. 验证结果

| 项目 | 结果 |
|---|---|
| `.\gradlew.bat build`（1.20.1） | BUILD SUCCESSFUL，751,596 bytes |
| `.\gradlew.bat build1204`（1.20.4） | BUILD SUCCESSFUL，752,229 bytes |
| `.\gradlew.bat build12111`（1.21.11） | BUILD SUCCESSFUL，752,383 bytes |
| 1.21.11 客户端启动 | 主菜单正常，52 mods，无 Mixin apply 失败 |
| 1.21.11 专用服务器 | `Done (0.601s)!`，无启动异常 |
| 1.21.11 客户端加入专用服务器 | 服务端记录 `Player315 joined the game`；无 `Unknown payload` / `ClassCastException` |
| 1.20.1 客户端启动 | 进入集成世界，BBS dashboard 构建正常，无 Mixin 错误 |
| 1.21.11 Mixin 配置 | 主 10 个、客户端 43 个，`compatibilityLevel=JAVA_21` |
| 1.21.11 jar 内容 | 含 `Together12111Client`、世界渲染器、两个 1.21.11 Mixin、`UIPlayerFaceRenderer`；不含 `ModelBlockEntityRendererBbsOpMixin` |

产物 SHA256：

- `build/libs/together-link-2.0.2-1.20.1-1.20.4.jar`
  - 751,596 bytes
  - `FDCA4B096CB1E99F659FCC1A2AA5081D3238918AACA57D808008807141AC817A`
- `build-1.20.4/libs/together-link-2.0.2-1.20.4-verify.jar`
  - 752,229 bytes
  - `4858F10E47B9E7AD5ED8A659A45F763D2BCC3B4BB21C0BBD953A47F9CA9C53FB`
- `build-1.21.11/libs/together-link-2.0.2-1.21.11.jar`
  - 752,383 bytes
  - `C1F41963C792544A630FD86B3735DA7B99523214C1C24A82AD0AFDA6069B405B`

说明：本轮只验证了编译、启动、加入服务器和基础渲染循环。锁定徽章、玩家隐藏、关键帧高亮和真实皮肤头像仍需按下面的步骤做视觉验收；本轮专用服务器连接是短时连接，长时间稳定性属于 P4。

## 3. 测试方式

### 3.1 构建（必须分开执行）

```powershell
.\gradlew.bat build
.\gradlew.bat build1204
.\gradlew.bat build12111
```

不要合并成一次 Gradle 调用：`build12111` 是嵌套 `GradleBuild`，合并会报 included build 同路径冲突。

### 3.2 1.21.11 客户端启动

```powershell
.\gradlew.bat runClient12111
```

检查：

1. 主菜单出现，Mods 列表有 `bbs_together 2.0.2-1.21.11` 和 `bbs 2.7-1.21.11`。
2. 进入一个世界，无崩溃、无 `Mixin apply failed`。
3. 打开 BBS dashboard，确认 film / model blocks / particles / model editor / textures / audio / add-ons 面板可打开。

### 3.3 模型方块锁定徽章

1. 放置一个 BBS 模型方块。
2. 通过 BBS 资产锁定面板锁定该模型方块（或使用服务器已有的锁数据）。
3. 按 F3 打开调试 HUD。
4. 靠近到 64 格内，观察模型方块附近是否出现锁形徽章；旋转视角，徽章应始终朝向镜头。
5. 对照 1.20 客户端，确认位置、大小、可见距离一致。

### 3.4 玩家隐藏 / Morph 隐藏

需要两个客户端或一个客户端 + 一个生产客户端：

1. 两个玩家进入同一服务器。
2. 玩家 B 打开影片/回放界面，使 `playersInFilmScreen` 记录生效。
3. 玩家 A 观察 B：B 的玩家模型应被隐藏，而不是显示默认皮肤或残影。
4. 关闭界面后，B 的模型应恢复。

### 3.5 关键帧高亮

1. 打开影片编辑器，进入关键帧 dope sheet。
2. 打开带提交/外部高亮的影片或关键帧数据。
3. 观察对应关键帧是否出现高亮形状；切换 sheet、滚动时间轴，确认没有错位或崩溃。

### 3.6 真实皮肤头像

1. 打开 dashboard 中带玩家头像的界面（player list / 协作面板）。
2. 确认显示真实皮肤头部，而不是灰色占位图标。
3. 在离线/无皮肤缓存情况下确认仍能回退到占位图而不是崩溃。

### 3.7 服务器联机与回归

首次运行专用服务器：

```text
run-1.21.11-server/eula.txt
eula=true
```

开发环境：

```properties
run-1.21.11-server/server.properties
online-mode=false
```

启动服务器：

```powershell
.\gradlew.bat runServer12111
```

另开终端连接：

```powershell
.\gradlew.bat runClient '-Pmcver=1.21.11' '--args=--quickPlayMultiplayer 127.0.0.1:25565'
```

建议至少检查：

1. 进入服务器后 dashboard、影片、模型方块、锁定、提交、coop 面板能打开。
2. 断开再重连，锁/提交/影片状态不丢。
3. 两个客户端同时在线 30 分钟，服务端日志无 `Unknown payload`、`ClassCastException`、`Failed to encode packet`。
4. 1.20.1 / 1.20.4 客户端仍能正常进入服务器和单人世界。

### 3.8 生产客户端（Iris / Sodium）

把以下 jar 放入生产客户端的 `mods`：

```text
build-1.21.11/libs/together-link-2.0.2-1.21.11.jar
```

重点检查：

- 模型方块锁定徽章在 Iris/Sodium 下是否正常；
- 玩家隐藏是否被光影/实体渲染管线影响；
- 真实皮肤头像是否正常；
- `latest.log` 中是否有新的 Mixin / 渲染异常。

## 4. 已知限制

- 1.21.11 不再使用 `ModelBlockEntityRendererBbsOpMixin`，锁定徽章改由世界渲染事件绘制；视觉位置可能与 1.20 有细微差异。
- 徽章沿用旧行为，仅在 F3 调试 HUD 可见时显示。
- `UIKeyframeDopeSheetMixin`、`MorphRendererMixin` 目前只验证了编译、类加载和基础世界渲染；具体视觉行为需要上面的验收步骤。
- `UIPlayerFaceRenderer` 依赖 `GlTexture.getGlId()`；在 Sodium/Iris 下需要额外确认。
- payload 类型仍是在首次 register/send 时懒注册；P4 会改为启动期统一注册，并做两客户端/长时间/大文件稳定性测试。
- 不承诺 1.20 与 1.21.11 的世界存档互通；只保证 BBS 侧数据格式尽量兼容。

## 5. 下一步（P4）

- 启动期注册全部 S2C/C2S payload，去掉首包懒注册。
- 两客户端 30 分钟、重连、断线恢复、锁竞争、提交同步、影片/大资产传输测试。
- 检查压缩/分块上限与总字节上限，补 rate limit 和结构化日志。
- 跑 1.20.1 / 1.20.4 网络回归，确认共享网络适配层没有破坏旧版本。
- 更新 P3/P4 文档与 README 测试入口。

## 6. 2026-11-03 01:02 生产客户端 / 双端联机日志复核

- 开发客户端 `Player247` 与生产客户端 `yizhenlingfeng` 同时加入本地开发服务器；两人先后被设为 OP，并切换到创造模式。
- 生产客户端环境：Fabric Loader 0.19.5、Java 25、Iris 1.10.8+mc1.21.11、Sodium 0.8.15-beta.1、Fabric API 0.141.6；日志出现 `[BBS shaders] Iris integration on`，说明 BBS 的 Iris 渲染路径已启用。
- 三份日志均未出现 `Unknown payload`、`ClassCastException`、`Failed to encode packet`、`Mixin apply failed` 或崩溃。
- 两个客户端在 01:03:37 / 01:03:42 正常断开，服务器随后正常停止；没有 kick 原因、超时或异常。
- 非阻断日志：
  1. 开发客户端 00:59:52 出现过一次 BBS 预览 NPE：`UIFormEditor.getOrigin` 中 `this.editor` 为 null，BBS 丢弃该帧的 gizmo/pick stencil，但没有崩溃；属于 BBS 侧预览路径，不是 Together P3 渲染器。
  2. 服务端 01:00:36 / 01:02:35 出现 `BaseManager Failed to load "ad"/"adad" from .\world\.bbs\films\...dat`；对应影片文件不存在，属于 BBS 侧非致命错误，推测是测试时打开未保存/已删除的影片。
  3. `FormStableIds` 的 orphan track 警告、首次运行的 `categories.json` 缺失、离线模式 401/Realms 错误、Iris 更新检查超时，均为已知非阻断项。
- 结论：P3 在日志层面通过，生产客户端的 Iris/Sodium 组合没有触发 Together payload/Mixin/渲染崩溃。锁定徽章、玩家隐藏、关键帧高亮、真实皮肤头像仍需按第 3 节做人工视觉确认。

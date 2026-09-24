# BBS Together - 反编译源码项目

基于 BBS FS 2.6 为前置的 Minecraft 1.20.1 Fabric 模组源码项目。

## 项目信息

- **模组 ID**: bbs_together
- **原版本**: 1.6.16-bbs2.4-wenxu
- **Minecraft**: 1.20.1
- **前置**: BBS FS >= 2.6, Fabric API, Fabric Loader >= 0.15.6
- **Java**: 17（编译目标），JDK 21（运行 Gradle 9.2）
- **Gradle**: 9.2.0
- **Fabric Loom**: 1.15-SNAPSHOT

## 项目结构

```
bbs-together/
├── build.gradle              # Gradle 构建配置
├── settings.gradle           # 项目设置（含国内镜像）
├── gradle.properties         # 版本属性与内存配置
├── gradlew / gradlew.bat     # Gradle Wrapper（腾讯云镜像）
├── gradle/wrapper/           # Wrapper 文件
├── lib/
│   └── bbs-fs-2.6-1.20.1/   # BBS 2.6 源码子项目
├── dist/                     # 归档的旧版 jar
└── src/
    ├── main/                 # 公共/服务端源集
    └── client/               # 客户端源集
```

## 国内镜像配置

已配置以下国内镜像加速下载：

| 组件 | 镜像源 |
|------|--------|
| Gradle Distribution | `mirrors.cloud.tencent.com/gradle/` |
| Maven 仓库 | `maven.aliyun.com/repository/public` |
| Maven Central | `maven.aliyun.com/repository/central` |
| Gradle Plugin | `maven.aliyun.com/repository/gradle-plugin` |

## 构建说明

### 前置要求

- JDK 17（编译 MC 1.20.1）或 JDK 21（运行 Gradle 9.2）
- 系统已安装 JDK 21 于 `C:\Program Files\Java\jdk-21`

### 构建步骤

1. **发布 BBS 到本地 Maven**（首次或 BBS 更新后）：
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
   cd lib\bbs-fs-2.6-1.20.1
   .\gradlew.bat publishToMavenLocal
   ```

2. **构建 bbs-together**：
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
   cd ..\..
   .\gradlew.bat build
   ```

3. **运行客户端开发环境**：
   ```powershell
   .\gradlew.bat runClient
   ```

4. **运行服务端开发环境**：
   ```powershell
   .\gradlew.bat runServer
   ```

### 注意事项

- BBS 依赖通过 `mavenLocal()` 获取，需先执行 BBS 项目的 `publishToMavenLocal`
- `transitive = false` 避免引入 BBS 的 Sodium/Iris 等传递依赖
- `fabric.mod.json` 中 Java 版本要求为 `>=17`
- 客户端运行内存已配置为 3G，服务端为 2G

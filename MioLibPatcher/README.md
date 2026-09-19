# MioLibPatcher

**简体中文** | [English](README_EN.md)

一个用于 Minecraft 的 Java agent，通过 [javassist](https://www.javassist.org/) 字节码转换，修复各类模组在 Android
环境（PojavLauncher 等）下的兼容性问题。

## 功能

MioLibPatcher 在类加载时对指定类进行字节码转换，目前包含以下补丁：

| 目标类                                                                                                                                                                                                                                    | 修复内容                                                                      |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `com.mojang.text2speech.Narrator`                                                                                                                                                                                                      | 禁用 TTS 朗读，`getNarrator` 返回哑实现                                             |
| `org.lwjgl.system.Library`                                                                                                                                                                                                             | 跳过 lwjgl 的哈希校验（`checkHash`）                                               |
| `net.vulkanmod.vulkan.SystemInfo`                                                                                                                                                                                                      | CPU 信息改用系统属性 `cpu.name`，避免解析 `/proc/cpuinfo` 失败                           |
| `com.therandomlabs.randompatches.client.WindowIconHandler`                                                                                                                                                                             | 禁用窗口图标设置（避免模组崩溃）                                                          |
| `oshi.hardware.CentralProcessor$ProcessorIdentifier` / `oshi.software.os.linux.proc.CentralProcessor`、`oshi.software.os.linux.LinuxHardwareAbstractionLayer`（oshi 1.x）/ `oshi.hardware.platform.linux.LinuxCentralProcessor`（oshi 6.x） | CPU 名称改用系统属性 `cpu.name`，核心数取自 JVM 的 `-XX:ActiveProcessorCount`，跳过设备拓扑信息读取 |
| `net.caffeinemc.mods.sodium.client...` / `me.jellysquid.mods.sodium.client...` / `org.embeddedt.embeddium...` 等                                                                                                                        | Sodium/Embeddium 的 PojavLauncher 检测返回 false                               |
| `dh_sqlite.util.OSInfo` / `org.rfresh.sqlite.util.OSInfo` / `org.sqlite.util.OSInfo` / `io.netty.util.internal.PlatformDependent`                                                                                                      | `isAndroid` 返回 true（sqlite / e4mc 兼容）                                     |
| `net.fabricmc.loader.impl.gui.FabricGuiEntry`                                                                                                                                                                                          | 模组加载错误打印到日志后退出（替代 GUI）                                                    |
| `net.minecraftforge.fml.loading.ModDirTransformerDiscoverer`                                                                                                                                                                           | 启动时打印正在加载的 mod 列表                                                         |
| `com.simibubi.create.compat.pojav.PojavChecker`                                                                                                                                                                                        | Create 模组的 PojavLauncher 检测禁用                                             |
| `dev.ryanhcode.sable.physics.impl.rapier.Rapier3D`                                                                                                                                                                                     | 支持从系统属性 `sable_rapier_path` 加载 Sable 原生库                                  |
| `bre.smoothfont.FontUtils`                                                                                                                                                                                                             | 字体缩放大小索引固定为 7（该方法用于判断字体缩放大小，但存在 bug，可能导致重启后缩放变为 1，字体会变得非常模糊）              |
| `foundry.veil.impl.client.imgui.VeilImGuiImpl`                                                                                                                                                                                         | 禁用 ImGui 路径设置                                                             |
| `imgui.moulberry92.ImGui`                                                                                                                                                                                                              | ImGui 原生库支持从系统属性指定路径/文件名加载                                                |
| `org.lwjgl.openal.ALC10`                                                                                                                                                                                                               | 可选：替换 `alcGetCurrentContext` 实现（需系统属性 `miolibpatcher.alc10=true`）         |
| `org.objectweb.asm.*`（5 个 visitor 类）                                                                                                                                                                                                   | 可选：移除 visitor 构造器的 api 版本校验（修复 Applied Energistics 1，见下文说明）                     |

### 特殊说明

- **ASM 补丁**：移除 visitor 构造器的 `IllegalArgumentException` api 版本校验（修复 Applied Energistics 1
  等模组对旧 ASM 的错误用法）。**默认关闭**，仅当启动器通过系统属性
  `miolibpatcher.asmBackport=true` 显式启用时生效。补丁不检测 ASM 版本，会对游戏实际加载的 ASM 生效，
  其字节码改写仅针对 ASM 5.0.4 验证，其他版本可能受未知影响，请谨慎启用。
- **ALC10 补丁**：默认关闭，需通过系统属性 `miolibpatcher.alc10=true` 显式启用。

## 使用方法

### 构建

需要 JDK 8+ 与 Gradle（项目自带 wrapper）：

```bash
./gradlew build
```

产物位于 `build/libs/MioLibPatcher.jar`，为包含全部依赖的 fat jar。

### 作为 premain agent（推荐）

JVM 启动时通过 `-javaagent` 加载，所有目标类在首次加载时被转换：

```bash
java -javaagent:MioLibPatcher.jar -jar minecraft.jar
```

### 作为 agentmain 动态 attach

可附加到已运行的 JVM（通过 `com.sun.tools.attach.VirtualMachine` 或 `jattach`），已加载的目标类会被重新转换：

```bash
jattach <pid> load instrument=false MioLibPatcher.jar
```

注意：agentmain 只重转加载时**已存在**的类，此后新加载的类由注册的 transformer 在加载时转换。

## 系统属性配置

| 系统属性                        | 说明                                                                   |
|-----------------------------|----------------------------------------------------------------------|
| `cpu.name`                  | 替换 CPU 名称读取来源（oshi / VulkanMod）                                      |
| `sable_rapier_path`         | Sable Rapier 原生库的绝对路径                                                |
| `imgui.library.path`        | ImGui 原生库所在目录（与 `imgui.library.name` 同时指定时生效）                        |
| `imgui.library.name`        | ImGui 原生库文件名                                                         |
| `miolibpatcher.alc10`       | `true` 时启用 ALC10 补丁，默认 `false`                                       |
| `miolibpatcher.sablerapier` | `true`/`false` 强制指定是否启用 Rapier 补丁；未设置时自动检测 `sable_rapier_path` 是否已设置 |
| `miolibpatcher.asmBackport` | `true`（不区分大小写）时启用 ASM 补丁，默认关闭 |

## 开发

```bash
./gradlew build   # 编译 + 测试 + 打包
./gradlew test    # 仅运行冒烟测试
```

### 添加新的补丁

1. 实现 `BaseTransformer` 接口：
    - 单个目标类：实现 `getTargetClassName()`，返回点号分隔的类名
    - 多个目标类：覆盖 `getTargetClassNames()`
    - 在 `transform(CtClass clazz)` 中使用 javassist API 修改字节码
2. 在 `MainAgent.createTransformers()` 中注册实例
3. 在 `src/test/java/.../BaseTransformerSmokeTest.java` 添加对应的冒烟测试

冒烟测试通过 javassist 在内存中构造模拟目标类，验证转换不抛异常且能生成字节码，无需真实游戏环境。

## 注意事项

- 目标类随模组版本变化可能调整，补丁不匹配时只记录日志并保持原字节码，不会影响游戏启动
- 日志输出到标准输出，格式为 `[MioLibPatcher/INFO]` / `[MioLibPatcher/ERROR]`

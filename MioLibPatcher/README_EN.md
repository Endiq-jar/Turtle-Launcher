# MioLibPatcher

[简体中文](README.md) | **English**

A Java agent for Minecraft that fixes compatibility issues of various mods in Android environments (PojavLauncher, etc.)
via [javassist](https://www.javassist.org/) bytecode transformation.

## Features

MioLibPatcher transforms target classes at class-load time. Currently it includes the following patches:

| Target class                                                                                                                                                                                                                               | Fix                                                                                                                                                                |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `com.mojang.text2speech.Narrator`                                                                                                                                                                                                          | Disables TTS; `getNarrator` returns a dummy implementation                                                                                                         |
| `org.lwjgl.system.Library`                                                                                                                                                                                                                 | Skips lwjgl hash verification (`checkHash`)                                                                                                                        |
| `net.vulkanmod.vulkan.SystemInfo`                                                                                                                                                                                                          | Reads CPU info from system property `cpu.name` instead of parsing `/proc/cpuinfo`                                                                                  |
| `com.therandomlabs.randompatches.client.WindowIconHandler`                                                                                                                                                                                 | Disables window icon setting (avoids crashes on Linux desktop environments)                                                                                        |
| `oshi.hardware.CentralProcessor$ProcessorIdentifier` / `oshi.software.os.linux.proc.CentralProcessor`, `oshi.software.os.linux.LinuxHardwareAbstractionLayer` (oshi 1.x) / `oshi.hardware.platform.linux.LinuxCentralProcessor` (oshi 6.x) | Reads CPU name from system property `cpu.name`; core count from JVM `-XX:ActiveProcessorCount`; skips device topology probing                                      |
| `net.caffeinemc.mods.sodium.client...` / `me.jellysquid.mods.sodium.client...` / `org.embeddedt.embeddium...` etc.                                                                                                                         | Sodium/Embeddium PojavLauncher detection returns false                                                                                                             |
| `dh_sqlite.util.OSInfo` / `org.rfresh.sqlite.util.OSInfo` / `org.sqlite.util.OSInfo` / `io.netty.util.internal.PlatformDependent`                                                                                                          | `isAndroid` returns true (sqlite / e4mc compatibility)                                                                                                             |
| `net.fabricmc.loader.impl.gui.FabricGuiEntry`                                                                                                                                                                                              | Prints mod loading errors to the log and exits (replaces the GUI)                                                                                                  |
| `net.minecraftforge.fml.loading.ModDirTransformerDiscoverer`                                                                                                                                                                               | Prints the list of mods being loaded at startup                                                                                                                    |
| `com.simibubi.create.compat.pojav.PojavChecker`                                                                                                                                                                                            | Disables Create mod's PojavLauncher detection                                                                                                                      |
| `dev.ryanhcode.sable.physics.impl.rapier.Rapier3D`                                                                                                                                                                                         | Loads the Sable native library from system property `sable_rapier_path`                                                                                            |
| `bre.smoothfont.FontUtils`                                                                                                                                                                                                                 | Forces the font scale index to 7 (the method detects the font scaling size but has a bug that may reset the scale to 1 after restart, making the font very blurry) |
| `foundry.veil.impl.client.imgui.VeilImGuiImpl`                                                                                                                                                                                             | Disables ImGui path setting                                                                                                                                        |
| `imgui.moulberry92.ImGui`                                                                                                                                                                                                                  | ImGui native library can be loaded from a path/file name given by system properties                                                                                |
| `org.lwjgl.openal.ALC10`                                                                                                                                                                                                                   | Optional: replaces `alcGetCurrentContext` (requires `miolibpatcher.alc10=true`)                                                                                    |
| `org.objectweb.asm.*` (5 visitor classes)                                                                                                                                                                                                  | Optional: removes the api version check in visitor constructors (fixes Applied Energistics 1, see below)                                                                                    |

### Notes

- **ASM patch**: Removes the `IllegalArgumentException` api version check in visitor constructors (fixes mods that
  misuse older ASM APIs, such as Applied Energistics 1). **Disabled by default**; enable it explicitly with
  `miolibpatcher.asmBackport=true`. The patch does not detect the ASM version and applies to whatever ASM the game
  actually loads; its bytecode rewrite is only verified against ASM 5.0.4, so other ASM versions may be affected in
  unexpected ways. Enable it with care.
- **ALC10 patch**: Disabled by default; enable it explicitly with `miolibpatcher.alc10=true`.

## Usage

### Build

Requires JDK 8+ and Gradle (the Gradle wrapper is included):

```bash
./gradlew build
```

The output is `build/libs/MioLibPatcher.jar`, a fat jar containing all dependencies.

### As a premain agent (recommended)

Load it with `-javaagent` at JVM startup; all target classes are transformed on first load:

```bash
java -javaagent:MioLibPatcher.jar -jar minecraft.jar
```

### Dynamic attach (agentmain)

Can be attached to an already running JVM (via `com.sun.tools.attach.VirtualMachine` or `jattach`); already loaded
target classes are retransformed:

```bash
jattach <pid> load instrument=false MioLibPatcher.jar
```

Note: agentmain only retransforms classes that were **already loaded**; classes loaded afterwards are handled by the
registered transformers at load time.

## System properties

| System property             | Description                                                                                      |
|-----------------------------|--------------------------------------------------------------------------------------------------|
| `cpu.name`                  | Replaces the CPU name source (oshi / VulkanMod)                                                  |
| `sable_rapier_path`         | Absolute path of the Sable Rapier native library                                                 |
| `imgui.library.path`        | Directory of the ImGui native library (used together with `imgui.library.name`)                  |
| `imgui.library.name`        | File name of the ImGui native library                                                            |
| `miolibpatcher.alc10`       | `true` enables the ALC10 patch, default `false`                                                  |
| `miolibpatcher.sablerapier` | `true`/`false` forces the Rapier patch on/off; when unset, detects if `sable_rapier_path` is set |
| `miolibpatcher.asmBackport` | `true` (case-insensitive) enables the ASM patch; disabled by default |

## Development

```bash
./gradlew build   # compile + test + package
./gradlew test    # run smoke tests only
```

### Adding a new patch

1. Implement the `BaseTransformer` interface:
    - Single target class: implement `getTargetClassName()` and return the dot-separated class name
    - Multiple target classes: override `getTargetClassNames()`
    - Modify bytecode in `transform(CtClass clazz)` using the javassist API
2. Register an instance in `MainAgent.createTransformers()`
3. Add a matching smoke test in `src/test/java/.../BaseTransformerSmokeTest.java`

The smoke tests build simulated target classes in memory with javassist, verifying that transformation does not throw
and produces valid bytecode, so no real game environment is required.

## Notes

- Target classes may change across mod versions. When a patch does not match, it only logs the failure and keeps the
  original bytecode, so the game still starts.
- Logs are written to standard output as `[MioLibPatcher/INFO]` / `[MioLibPatcher/ERROR]`.

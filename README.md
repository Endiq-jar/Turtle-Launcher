<div align="center">

# 🐢 TurtleLauncher for Android

**Run Minecraft: Java Edition on Android.**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-orange)](#)
[![AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)

</div>

---
---

## 1. What it does

This is **Java Edition**, not Bedrock. Join the servers your PC friends use, run the mods
they run. A real OpenJDK boots inside the app process — this is not an emulator.

### Supported game version and mods

| | |
|---|---|
| **Minecraft** | **26.3 only** — other versions are hidden and rejected by install/launch guards |
| **Mod loaders** | Fabric · Forge · NeoForge · Quilt |
| **Mods and modpacks** | Search and install straight from CurseForge and Modrinth |
| **Dependencies** | Required mods are **installed alongside automatically** |

The Java runtime and SDL/LWJGL payload are **chosen automatically for Minecraft 26.3**. There is no version picker for older or newer releases in this build.

### Graphics

| Renderer | How | Best for |
|---|---|---|
| **Zink** 🌋 | Vulkan → OpenGL (Mesa) | Modern GPUs, 1.17+ · **use this for shaders** |
| **Krypton** ⚛️ | OpenGL → GLES 3.x | Broad version coverage, built in |
| **GL4ES** 🕹️ | OpenGL → GLES 2.0 | Old versions (1.12 and below), devices Zink won't run on |
| **MobileGlues** 🚀 | OpenGL 4.x → GLES 3.2 | Fastest for shaders · used by 26.3's OpenGL path |

If Zink fails it steps down on its own: **Freedreno (Adreno) → Panfrost (Mali) → GL4ES**.
You can also import a **custom Vulkan driver** (Turnip and similar) as a zip, in the
AdrenoTools format.

Shaders run on **Iris** — reflections, shadows, coloured light.

### Controls

- **On-screen buttons** — you place and size them yourself
- **Hardware keyboard** — picked up as soon as it connects
- **Mouse** — Pointer Capture, so looking around isn't bounded by the screen edge.
  Menus switch to an absolute cursor automatically; the wheel is the hotbar
- **Gamepad** — sticks and triggers included

### Multiplayer

Ordinary servers, plus **Online LAN** for playing with a friend without renting a server:
open a room, share the code, they join with it.

### Account

Microsoft sign-in and a clearly marked local/offline account path are available.
Offline accounts do not receive fake Microsoft tokens and are intended for
single-player/offline-compatible use. **No game files ship with the app** —
you download them from Mojang's own servers with your own account.

---

## 2. Running it

### What you need

| | |
|---|---|
| **Android 8.0 or later** | API 26 |
| **arm64-v8a** | Essentially any device from 2017 onward. 32-bit is **not supported** |
| **4 GB free** | App, game and a Java runtime |
| **4 GB RAM recommended** | Modpacks are a stretch below 3 GB |
| **A paid account** | Minecraft Java Edition |

> Unlike iOS, **no JIT setup is needed.** Android doesn't stop apps from generating code at
> runtime, so you install and go.

### 2-1. Install

1. Grab the latest APK from [Releases](https://github.com/Endiq-jar/TurtleLauncher/releases)
2. Android asks you to allow installs from unknown sources — allow it
3. Grant storage permission on first launch

### 2-2. First run

1. **Sign in** — avatar at the top → Microsoft account
2. **Select Minecraft 26.3** — it is the only available release
3. **Pick a loader** — vanilla, Fabric, Forge or NeoForge
4. **Download** — game files and a Java runtime; first time only, 5–15 minutes
5. **Play** — select it under the installed tab and press play

### 2-3. Turning on shaders

1. Install **Fabric** on the instance
2. Install **Iris Shaders** from the modpack browser — dependencies come along
3. Switch the renderer to **Zink**
4. In game: `Options → Video Settings → Shader Packs`

Heavy packs are a lot to ask of a phone. Start light — Complementary, or BSL on low.

### 2-4. Controls

The keyboard editor lets you place and resize the on-screen buttons. Hardware keyboards,
mice and gamepads are picked up the moment they connect — nothing to configure.

### 2-5. When it doesn't work

| Symptom | What to check |
|---|---|
| Installs, then closes immediately | Confirm the device is **arm64-v8a**. 32-bit is unsupported |
| Crashes mid-game | Out of RAM. **Lower the heap allocation** in options — counter-intuitive, but a smaller Java heap leaves room for textures. Lower the render distance too |
| A modpack won't start | With many mods the first boot takes minutes. If it still fails, try another renderer; Zink and GL4ES differ in what they tolerate |
| Shaders render garbage | Switch to **Zink**. GL4ES cannot drive shaders properly |
| A shader pack switches itself off (Mali GPUs) | Packs that use `noperspective` — Complementary and its forks — can't compile on Mali, so Iris falls back to no shaders. Turn the pack off to skip the attempt and load faster |

---

## Turtle Launcher feature migration

The root `:app` module now uses TurtleLauncher's original XML home layout and visual assets.
The original launcher shell, panels, spacing, icons, animations, English string catalog,
background picker, offline assistant, account flow (offline, ely.by, Battly, custom
authlib-injector/Yggdrasil, and Microsoft), file manager, crash-log styling, offline
skins, content-pack browser, renderer controls, Terracotta, and low-end safeguards are
kept in the root APK. The old source tree under `TurtleLauncher/` remains as the reference
implementation; Gradle exposes only `:app` as the visible/default application.

The default build is:

```bash
./gradlew :app:assembleDebug
```

The root target carries the runtime payloads needed by TurtleLauncher: Minecraft 26.3-only
SDL/LWJGL callbacks, LTW, MobileGlues, packaged renderer libraries, Terracotta,
Java runtimes, and the low-end native launch path. `TurtleLauncher/` is retained
only as the old source tree; it is not included by `settings.gradle.kts` and is
not part of the default build.

The final device parity gate still covers Microsoft skin/cape upload, renderer
packaging, ABI/device coverage, lifecycle, and upgrade behavior. Those are
runtime validation requirements, not silently claimed placeholders. The offline,
ely.by, Battly, custom Yggdrasil, and Microsoft login paths are present; online
skin/cape upload and provider-specific multi-profile edge cases remain explicitly
unsupported until tested and implemented.

## 3. Licence

**[AGPL-3.0](LICENSE)**, by obligation rather than preference.

A combined work takes the **strongest copyleft** it contains, and the Terracotta bundled
here is AGPL-3.0.

| Component | Licence | How it is used |
|---|---|---|
| **Terracotta** | **AGPL-3.0** | `libterracotta.so` + JNI binding (Online LAN) |
| PojavLauncher core | GPL-3.0 | JNI / EGL / OSMesa bridges, LWJGL and exec hooks, AWT stubs |
| ZalithLauncher 2 | GPL-3.0 | Launch flow (NeoForge classpath, Forge processors, GLFW 3.4 stubs) |
| EasyTier | LGPL-3.0 | inside Terracotta |
| libadrenotools | BSD-2-Clause | custom Vulkan driver loading |
| LWJGL (Pojav-patched) | BSD-3-Clause | `lwjgl3/` |
| OpenJDK (Temurin) | GPL-2.0 + Classpath Exception | bundled JRE |

GPL-3.0 code may be combined into an AGPL-3.0 work — GPLv3 section 13 permits exactly this
— and the result must be distributed under AGPL-3.0.

> **Correction to earlier releases.** The `LICENSE` file used to contain LGPL-3.0, and this
> project's own code was listed as "no declared license". Both were wrong: the tree already
> contained GPL-3.0 (PojavLauncher) and AGPL-3.0 (Terracotta), so LGPL-3.0 was weaker than
> the combination allowed, and leaving the project's own code undeclared left the whole work
> ambiguous.

Custom Vulkan drivers (Turnip and similar) are **not distributed by this project.** Get
them from a source you trust — official Mesa, K11MCH1/AdrenoToolsDrivers — and import them
yourself.

See [NOTICE](NOTICE) for the details.

> Minecraft is a trademark of Mojang AB. This project is not affiliated with, endorsed by,
> or connected to Mojang AB or Microsoft.

<div align="right"><a href="#-turtlelauncher-for-android">⬆ Back to top</a></div>

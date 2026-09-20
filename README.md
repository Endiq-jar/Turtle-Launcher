<h1 align="center">Turtle Launcher</h1>

<p align="center"><b>Minecraft: Java Edition for Android — Turtle style, Amethyst-powered.</b></p>

Turtle Launcher is a Minecraft: Java Edition launcher for Android. It keeps the complete
Turtle Launcher experience — download center, instances, accounts, skins/capes/chroma names,
controls, performance profiles, diagnostics, modpacks, mods, resource packs and shaders —
rebuilt on the [Amethyst](https://github.com/AngelAuraMC/Amethyst-Android) launcher core for
**Minecraft 26.3+ support** (snapshots included), LWJGL 3.4.x, SDL3 windowing/input and the
modern renderer stack.

Amethyst is itself based on [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)
and [Boardwalk](https://github.com/zhuowei/Boardwalk). Without these projects Turtle Launcher
would not exist — see [Credits](#credits--dependencies).

## Features

* **Minecraft 26.3 and newer** (plus snapshots, snapshot-4+, and older versions down to the classics)
* Mod loaders: Fabric, Quilt, Forge, NeoForge, OptiFine (via the download center), plus
  Amethyst-extras **BTA (Better Than Adventure)** and **LWJGL3ify** (old MC on modern Java)
* Modpacks: Modrinth `.mrpack`, CurseForge ZIP, import/export
* Accounts: Microsoft, local/offline (full-featured), Ely.by, Battly — with a profile page per account
* Skins, capes and chroma names, per account, with import/export
* Instances: create/duplicate/edit/repair/import/export, per-instance Java + renderer selection
* Java manager: OpenJDK 8/17/21, OpenJ9 17, GraalVM 21 — ARM64, ARM32, x86, x86_64
* Renderers: GL4ES, MobileGlues, LTW, Mesa3D/Zink, VirGL, Freedreno, RNW, VGPU, ANGLE, system GLES
* Touch, keyboard, mouse and gamepad controls with a full layout editor, import/export
* Per-version control layouts, live mouse speed editor, mod filters (from the Amethyst stack)
* Performance profiles and JVM/RAM controls, FPS overlay
* Diagnostics & troubleshooting: automated problem detection with safe repair actions
* Log management: launcher/game/crash logs, copy/share/save, upload to [mclo.gs](https://mclo.gs)
* Backups: versioned `TurtleLauncherBackup` format (settings, controls, instance configs, safe account metadata)

## Getting Turtle Launcher

Download the latest APKs from
[GitHub Actions](https://github.com/Endiq-jar/TurtleLauncher/actions/workflows/android.yml)
(both debug and release, with MD5/SHA-256 checksums, are attached to every successful build).

## Building

Requirements: JDK 21, Android SDK (with NDK), Gradle 9.6.1.

```bash
git clone https://github.com/Endiq-jar/TurtleLauncher.git
cd TurtleLauncher
gradle :app_pojavlauncher:assembleDebug
```

The APK is output to `app_pojavlauncher/build/outputs/apk/debug/`.

Build configuration notes:

* Everything is vendored in-tree (no submodules). The Java 8 runtime binpacks ship
  under `app_pojavlauncher/src/main/assets/components/jre8/`.
* Newer runtimes (17/21/25) are downloaded on demand from public release CDNs
  (AngelAuraMC OpenJDK builds, plus the Turtle JRE CDN for OpenJ9/GraalVM).
* The project runs AGP 9 in compat DSL mode (`android.newDsl=false`,
  `android.builtInKotlin=false`) together with Kotlin 2.3.20 and kapt, which is the
  combination required by the merged Kotlin (Turtle UI) + Java (Amethyst core) codebase.
* To build a single-ABI APK, pass the architecture: `gradle :app_pojavlauncher:assembleDebug -Darch=arm64`
  (one of `arm`, `arm64`, `x86`, `x86_64`).

See [docs/USERGUIDE.md](docs/USERGUIDE.md) for the full user documentation.

## Current Status

Migrated onto the Amethyst foundation. The launcher targets Minecraft `rd-132211` through
`26.3`-era versions including snapshots, keeping Amethyst's LWJGL 3.4.x / SDL3 / native stack
untouched while Turtle's features run on top of it.

## License

Turtle Launcher is licensed under the GNU LGPLv3 (as its upstream Amethyst), with the
upstream license preserved in [docs/LICENSE-Amethyst-Upstream.txt](docs/LICENSE-Amethyst-Upstream.txt)
and [LICENSE](LICENSE).

## Credits & Dependencies

* [Boardwalk](https://github.com/zhuowei/Boardwalk) (JVM Launcher): Unknown License / [Apache License 2.0](https://github.com/zhuowei/Boardwalk/blob/master/LICENSE) or GNU GPLv2.
* [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher): [GLGPL](https://github.com/PojavLauncherTeam/PojavLauncher/blob/v3_openjdk/LICENSE)
* [Amethyst](https://github.com/AngelAuraMC/Amethyst-Android) (launcher core, MC 26.x stack, LWJGL 3.4.x, SDL3): [GNU LGPLv3](https://github.com/AngelAuraMC/Amethyst-Android/blob/v3_openjdk/LICENSE)
* Android Support Libraries: [Apache License 2.0](https://android.googlesource.com/platform/prebuilts/maven_repo/android/+/master/NOTICE.txt).
* [GL4ES](https://github.com/AngelAuraMC/gl4es): [MIT License](https://github.com/ptitSeb/gl4es/blob/master/LICENSE).
* [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues): [LGPL-2.1 License](https://github.com/MobileGL-Dev/MobileGlues/blob/dev-es/LICENSE).
* [Krypton Wrapper](https://github.com/BZLZHH/NG-GL4ES): [MIT License](https://github.com/BZLZHH/NG-GL4ES/blob/main/LICENSE)
* [ANGLE](https://chromium.googlesource.com/angle/angle): [All Rights Reserved](app_pojavlauncher/src/main/assets/licenses/ANGLE_LICENSE).
* [OpenJDK](https://github.com/AngelAuraMC/openjdk-multiarch-jdk8u): [GNU GPLv2 License](https://openjdk.java.net/legal/gplv2+ce.html).
* [LWJGL3](https://github.com/AngelAuraMC/lwjgl3): [BSD-3 License](https://github.com/LWJGL/lwjgl3/blob/master/LICENSE.md).
* [LWJGLX](https://github.com/AngelAuraMC/lwjglx) (LWJGL2 API compatibility layer for LWJGL3): unknown license.
* [Mesa 3D Graphics Library](https://gitlab.freedesktop.org/mesa/mesa): [MIT License](https://docs.mesa3d.org/license.html).
* [bhook](https://github.com/bytedance/bhook) (Used for exit code trapping): [MIT license](https://github.com/bytedance/bhook/blob/main/LICENSE).
* [libepoxy](https://github.com/anholt/libepoxy): [MIT License](https://github.com/anholt/libepoxy/blob/main/COPYING).
* [virglrenderer](https://github.com/AngelAuraMC/virglrenderer): [MIT License](https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/master/COPYING).
* [OpenAL-Soft](https://github.com/kcat/openal-soft): [GNU GPLv2](app_pojavlauncher/src/main/assets/licenses/OPENAL-SOFT_GPL2)
  * [oboe](https://github.com/google/oboe): [Apache License 2.0](app_pojavlauncher/src/main/assets/licenses/OBOE_APACHE2).
  * [pfffft](https://bitbucket.org/jpommier/pffft/src/master/): [ARR](app_pojavlauncher/src/main/assets/licenses/PFFFT_LICENSE)
* [SDL3](https://github.com/libsdl-org/SDL): [zlib License](https://github.com/libsdl-org/SDL/blob/main/LICENSE.txt)
* [sdl2-compat](https://github.com/libsdl-org/sdl2-compat): [zlib License](https://github.com/libsdl-org/sdl2-compat/blob/main/LICENSE.txt)
* [TouchController](https://github.com/TouchController/TouchController) proxy client: MIT License.
* [zstd-jni](https://github.com/luben/zstd-jni): BSD-2 License.
* Thanks to [MCHeads](https://mc-heads.net) for providing Minecraft avatars.

Turtle Launcher is an independent fork. It is not affiliated with Mojang Studios or Microsoft.
Minecraft is a trademark of Mojang Studios.

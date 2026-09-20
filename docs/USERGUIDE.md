# Turtle Launcher — User Guide

Turtle Launcher lets you play Minecraft: Java Edition on Android, with the full Turtle
feature set running on the Amethyst launcher core (Minecraft 26.3+ support, LWJGL 3.4.x,
SDL3 input/windowing).

## Contents

1. [Installation](#installation)
2. [First launch](#first-launch)
3. [Installing the game](#installing-the-game)
4. [Playing](#playing)
5. [Accounts](#accounts)
6. [Instances](#instances)
7. [Mods, modpacks, resource packs, shaders](#mods-modpacks-resource-packs-shaders)
8. [Controls](#controls)
9. [Renderers](#renderers)
10. [Java manager](#java-manager)
11. [Performance](#performance)
12. [Logs & troubleshooting](#logs--troubleshooting)
13. [Backups, import/export](#backups-importexport)
14. [Offline use](#offline-use)

## Installation

1. Download the APK from the
   [GitHub Actions builds](https://github.com/Endiq-jar/TurtleLauncher/actions/workflows/android.yml)
   (verify the `.md5`/`.sha256` checksums if you like).
2. Allow installing from this source when Android asks.
3. Open Turtle Launcher.

Requirements: Android 8.0+ is recommended. About 300 MB of free storage for the launcher
plus roughly 1–2 GB per installed game version and runtime.

## First launch

On first start Turtle Launcher will:

* Prepare its data folder,
* Unpack the bundled Java 8 runtime (used for old game versions),
* Ask for notification permission so downloads can continue in the background,
* Ask for storage access where the Android version requires it.

Nothing user-facing from the upstream projects is removed: attribution for Amethyst,
PojavLauncher and Boardwalk lives in *Settings → About*.

## Installing the game

* Home → **Play** opens the version selector if no version is installed yet.
* The **Download Center** offers game versions (including 26.3, snapshots, and historical
  versions), mod loaders, mods, modpacks, Java runtimes, renderers, resource packs and shaders.
* Every download shows progress, size, speed, remaining time, and can be retried or cancelled.

Old versions run on Java 8 (bundled). Newer versions (1.17+) automatically use a modern
runtime — Turtle downloads OpenJDK 17/21/25 on demand, and offers OpenJ9 17 and GraalVM 21
in the Java manager. Minecraft 26.x requires Java 25, which the launcher selects automatically.

Extra loaders from the Amethyst stack are available too:

* **BTA (Better Than Adventure)** — the retro Minecraft fork, installable directly from
  the download center.
* **LWJGL3ify** — runs old modded versions (1.7.x era) on modern Java runtimes.

## Playing

* Pick an account and a version/instance on the home screen — both are shown prominently.
* Press **Play**. The game window opens with your control layout.
* During play: keyboard/mouse/touch/gamepad input, virtual mouse, fullscreen, resolution
  scaling, and the in-game menu are all available.
* The FPS overlay can be enabled in *Settings → Video*.

## Accounts

* **Microsoft**: sign in with the browser-based OAuth flow. Tokens are stored locally and
  refreshed automatically; the launcher never asks for your password.
* **Local / offline**: full support — username, UUID, skin, cape and chroma name. No
  Microsoft check is bypassed; offline accounts simply do not use Microsoft auth.
* **Ely.by / Battly**: supported where the service is reachable; if an API is unavailable
  the launcher says so instead of pretending to sign you in.
* Every account has a profile page: username, UUID, type, skin, cape, chroma name, auth
  state, last used, and quick actions (Play / Edit / Skin / Cape / Chroma / Export / Remove).
* Switching accounts is instant from the home screen; the current account is always obvious.

### Account import/export

Exports contain account type, username, UUID, metadata and skin/cape/chroma configuration.
**Passwords and Microsoft tokens are never exported in plaintext.** After importing on
another device, Microsoft accounts re-authenticate through the normal sign-in flow.

## Instances

Create, duplicate, rename, edit, repair, import, export, and launch instances. Each instance
keeps its own version, Java runtime, renderer, JVM arguments, mods, resource packs, shaders,
saves and config, with direct folder access from the launcher. Repairing an instance never
touches your worlds.

## Mods, modpacks, resource packs, shaders

* **Mods** (Modrinth & CurseForge): install, remove, enable/disable, update, dependency info,
  version and loader compatibility checks, with the Amethyst mod filter dialog for
  narrowing by loader, MC version and category.
* **Modpacks**: Modrinth `.mrpack` and CurseForge ZIP, from the download center or by
  importing a file. Export your own modpacks too.
* **Resource packs & shaders**: same management flow, including renderer-compatibility
  notes for shader packs (a shader needing desktop GL will say which renderers can run it).

## Controls

* Touch layouts with a full editor: position, size, opacity, visibility, key mappings,
  hold-to-repeat with adjustable delay.
* **Per-version control layouts**: bind a specific control layout to a game version or
  instance, so different versions can use different buttons.
* Keyboard, mouse and gamepad support, including the gamepad mapper and the live mouse
  speed editor.
* Layouts can be imported/exported as JSON; layout metadata (name, author, version,
  description) is preserved.
* The bundled TouchController proxy support is included for Bedrock-style touch input
  with the corresponding mod.

## Renderers

Available renderers depend on your device (GPU, Vulkan support) and installed libraries:
GL4ES variants, MobileGlues, LTW, Zink (Mesa), VirGL, Freedreno, RNW, VGPU, ANGLE and the
system GLES renderer. The renderer list only shows what is actually usable on your device.
Per-renderer settings and diagnostics live in *Settings → Video*; if a renderer fails to
initialize, the launcher offers to reset its config or reinstall it.

## Java manager

* OpenJDK 8 (bundled), 17, 21, 25 — plus OpenJ9 17 and GraalVM 21.
* Architectures: ARM64, ARM32, x86, x86_64.
* Per-instance runtime selection; automatic updates keep runtimes current.
* Missing or incompatible runtimes are detected before launch and can be installed in one tap.

## Performance

* RAM allocation with a safe maximum computed for your device.
* Custom JVM arguments per instance.
* Profiles: Battery Saver, Balanced, Performance, Maximum Performance, Custom.
* The FPS overlay shows the current frame rate in-game.

## Logs & troubleshooting

* *Logs* gathers launcher, game, crash, renderer, Java, native and download logs — copy,
  share, save to file, clear, or upload to [mclo.gs](https://mclo.gs) and share the link.
* The launcher detects common problems: missing/incompatible Java, missing or corrupt
  libraries, broken renderer config, missing assets, incomplete installs, invalid mod
  loaders, insufficient storage, invalid instances, missing permissions, broken native
  libraries, ABI mismatches, SDL/LWJGL load failures and Vulkan init failures.
* Safe repair actions: Repair Libraries, Reinstall Renderer, Reset Renderer Config,
  Repair Minecraft Version, Reinstall Java, Clear Download Cache, Reset Instance Config.
* **Worlds and saves are never deleted automatically.** Anything destructive asks first.

## Backups, import/export

`TurtleLauncherBackup` files are versioned (format version, launcher version, date,
categories) and independent of any internal database layout, so future versions can
migrate them. Categories: settings, controls, safe account metadata, instance configs.
Restoring never silently overwrites — you confirm what gets replaced.

## Offline use

Everything installed on the device works offline: instances, mods, controls, accounts
(also offline ones), settings. Functions that genuinely need the network (downloads,
Modrinth/CurseForge browsing, mclo.gs uploads, Microsoft sign-in) clearly indicate when
you are offline.

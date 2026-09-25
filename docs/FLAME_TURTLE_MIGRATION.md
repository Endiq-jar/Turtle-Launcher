# FlameLauncher base / Turtle Launcher UI migration

Status: **Phase 4 in progress — the pinned Flame target has eight applied migration slices and a ninth account slice. The production root is not switched until the full parity/release gates pass.**

This document defines the migration order for a FlameLauncher-based build that preserves Turtle Launcher’s UI, animations, feature set, Minecraft 26.3 SDL support, LTW, and MobileGlues support.

## Non-negotiable safety rules

1. The existing Turtle build remains the production baseline until the replacement passes the same checks.
2. Do not delete or disable a Turtle feature before an equivalent Flame implementation is present and tested.
3. Do not copy Java/Kotlin/native code only because it has a similar name. Every copied subsystem must be adapted to Flame’s package names, lifecycle, classpath, JNI symbols, native-library loading, and renderer model.
4. The exact SDL 26.3 path remains isolated from the pre-26.3 GLFW path. No mixed LWJGL generations may enter one launch.
5. The current Turtle safeguards remain required in the replacement:
   - exact `org.lwjgl:lwjgl-glfw:3.4.3+4` provisioning for the 26.3 compatibility path;
   - `lwjgl-glfw-bridge.jar` before downloaded game libraries;
   - SDL-only fallback when the game-side `GLFWErrorCallback` is absent;
   - LTW and MobileGlues renderer selection and native loading;
   - all currently supported ABIs unless an equivalent implementation is explicitly tested.
6. Every phase ends with a build, static verification, and regression checklist before the next phase starts.

## Baseline inventory

Turtle Launcher currently contains approximately:

- 674 Java/Kotlin source files;
- 113 XML layouts;
- 168 drawable resources;
- 5 Android transition resources;
- 12 values files, including `colors.xml` and `strings.xml`;
- 37 asset files;
- four native ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

The UI is primarily Android Views/XML with custom `AnimPlayer` transitions. FlameLauncher is Compose-based, so the UI must be reimplemented as Compose components while preserving the same visual contract; copying XML files alone will not wire screens or behavior.

## Migration phases

### Phase 0 — freeze and baseline (current)

- Keep the current Turtle app and 26.3 fix unchanged.
- Record the feature/resource inventory and the renderer/classpath invariants.
- Confirm the repository is clean and the last known CI build remains green.
- Do not import Flame binaries or replace the existing Gradle application in this phase.

Exit gate: no regression in the current Turtle build or source checks.

### Phase 1 — create an isolated Flame migration target

- Add FlameLauncher as a separate migration target/module or an explicitly isolated source tree; do not overwrite `TurtleLauncher`.
- Retain Turtle’s package/application identity until the new target can install and run independently.
- Preserve Flame’s AGPL notices and Turtle’s GPL notices for any code that is copied or adapted. Review the final combined licensing and notices before distribution.
- Build the unmodified Flame baseline first.

Exit gate: Flame baseline assembles and launches its existing supported Minecraft versions before Turtle code is introduced.

### Phase 2 — port the runtime safety layer first

Port and adapt, rather than blindly copy:

- exact 26.3 version detection;
- the game-side LWJGL/SDL classpath and native ordering;
- Turtle’s `SdlMainReadyBootstrap` fallback behavior;
- SDL surface lifecycle and input bridge behavior;
- shaderc, SDL, OpenAL, SPVC, renderer, and allocator absolute-library handling;
- LTW and MobileGlues renderer contracts;
- the existing ABI matrix and low-memory launch behavior.

Flame’s `MinecraftActivity`, `flame_sdl.c`, and renderer environment setup are references only. JNI methods must be checked against Flame’s hard-coded native symbol names before integration.

Exit gate: an isolated Flame target launches 26.3 with SDL3 without the GLFW callback crash, and pre-26.3 versions still use their original GLFW path.

### Phase 3 — port resources and design tokens

Port Turtle resources into Flame’s resource model with collision-safe names and explicit mapping:

- `colors.xml` → Compose `Color`/theme tokens;
- `dimens.xml` → Compose `Dp`/spacing tokens;
- `strings.xml` and locale resources → Flame string resources or a generated typed string catalog;
- drawable XML, icons, backgrounds, gradients, fonts, and images → Compose-compatible resources;
- styles and accessibility text → Material 3 theme/component defaults.

The original resource files must remain available in the Turtle target until the replacement screens consume the equivalent values. No resource deletion is part of this phase.

Exit gate: a screenshot/visual comparison of the home, instance list, settings, dialogs, renderer selection, log viewer, and launch screens shows equivalent colors, spacing, typography, states, and accessibility labels.

### Phase 4 — port screen wiring and features by vertical slice

Port one complete feature at a time, including UI, state, persistence, navigation, loading/error states, and tests:

1. shell, splash, home, navigation, theme/background, and animation settings;
2. accounts and authentication;
3. versions, instances, downloads, and launch configuration;
4. settings, Java/runtime management, memory/JVM arguments, and controls;
5. renderer management, LTW, MobileGlues, plugins, and low-end optimization;
6. mods, resource packs, shaders, worlds, screenshots, logs, and crash analysis;
7. Terracotta/LAN, Shizuku, AI chat, emotes, skins/capes, and integrations;
8. backup/import/export, update/download manager, and remaining dialogs.

Each slice must be usable before the next slice starts. Flame’s existing implementation wins where it is runtime-critical; Turtle’s behavior and appearance win where the requirement is UI/feature parity.

Exit gate: the feature matrix has no unimplemented required item, and every migrated feature has a working empty/loading/error/success state.

### Phase 5 — animation parity

Implement Turtle’s animation contract in Compose:

- fade, slide, bounce, zoom, wobble, pulse, shake, and sheet transitions;
- fragment/screen enter and exit timing;
- list layout animations;
- dialog transitions;
- animation speed settings and disabled-animation accessibility behavior.

Do not substitute arbitrary Compose defaults. Capture the existing timing/interpolator behavior from `AnimPlayer`, `Animations`, and `TurtleTransitions`, then write Compose equivalents and compare them screen by screen.

Exit gate: animation settings work globally and no screen uses a placeholder transition.

### Phase 6 — compatibility and release gate

Test both targets before switching the default:

- Minecraft 26.3 and later SDL snapshots;
- pre-26.3 GLFW versions;
- MobileGlues and LTW;
- all supported ABIs;
- offline/incomplete-cache launch behavior;
- fresh install, upgrade install, and existing instance migration;
- low RAM/low-end devices;
- orientation, lifecycle pause/resume, surface recreation, input, IME, gamepad, and crash-log paths.

Only after this gate should the Flame-based target become the default. Turtle remains available as a rollback target until a subsequent release proves upgrade safety.

## Current decision

The requested end state is a Flame-based root target with Turtle UI and feature
parity. The user selected full Flame vendoring despite the large source/native
payload, so this is not a permanent submodule or UI-only rewrite decision.

The current repository keeps the production Turtle module as a rollback target
while the pinned Flame target is reproduced by `migration/build-flame.sh` and its
ordered patches. This is a deliberate safety boundary, not a parity claim: the
patched Flame APK builds, but root replacement and final vendoring remain gated
on the unfinished account, skin, file-manager, renderer-package, ABI, lifecycle,
and device validation work. Do not describe the migration as complete until
those gates pass.

Completed in the applied chain so far:

- Flame's Minecraft 26.3-safe SDL/LWJGL path, callback artifact, LTW, and
  MobileGlues ordering are preserved;
- Turtle colors, strings, dimensions, styles, drawables, animations, font, and
  motion primitives are available under collision-safe names;
- the home/settings shell consumes Turtle motion, background selection, offline
  assistant, crash-log styling, and offline account behavior;
- the complete target assembles successfully in GitHub Actions.

The next vertical slices are skin application/cape behavior, file and profile
management, fuller account management, and a renderer packaging/ABI audit. Only
after those are usable and tested should the vendored Flame tree become the
repository's default root target; Turtle remains the rollback target until the
upgrade and device gates pass.

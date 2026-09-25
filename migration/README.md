# Isolated FlameLauncher migration target

This directory is intentionally outside the production `TurtleLauncher` Gradle build.
It records the exact FlameLauncher source baseline used for the migration without
vendoring Flame's large JRE/native binaries into the Turtle repository.

## Step 1: reproduce the baseline

```bash
repo=$(awk -F= '$1 == "repository" {print $2}' migration/flame-baseline.properties)
commit=$(awk -F= '$1 == "commit" {print $2}' migration/flame-baseline.properties)
mkdir -p /tmp/turtle-flame-baseline
git clone --no-checkout "$repo" /tmp/turtle-flame-baseline
git -C /tmp/turtle-flame-baseline checkout --detach "$commit"
cd /tmp/turtle-flame-baseline
./gradlew :app:assembleDebug
```

The baseline must build before Turtle code is ported. Do not add the Flame source
as a second application in `settings.gradle.kts` yet: that would create duplicate
application tasks and make it too easy to ship a partially migrated build.

## Step 1 exit gate

- Flame commit is exactly the pinned commit in `flame-baseline.properties`.
- The unmodified Flame debug APK assembles.
- Its existing 26.3 implementation is recorded before changes are made.
- Turtle's production build and current SDL bridge remain untouched.

After this gate, migration work is applied as small, reviewable patches under
`migration/patches/` and validated against both targets. Large generated binaries
remain outside this repository unless they are required by the final Turtle build.

## Step 2: protect the Flame SDL classpath

`patches/0001-reject-stale-glfw-from-sdl-classpath.patch` is the first runtime
patch. It adds a pure, unit-testable `LwjglClasspathPolicy` and removes stale
`lwjgl-glfw-classes*.jar` files from the SDL/26.3 classpath while retaining them
for the legacy GLFW path. This prevents an old Pojav GLFW fat jar from shadowing
Minecraft's LWJGL 3.4.3 SDL classes after an upgrade or when an instance carries
extra jars.

Apply it only to the pinned Flame checkout:

```bash
git -C /tmp/turtle-flame-baseline apply \
  "$OLDPWD/migration/patches/0001-reject-stale-glfw-from-sdl-classpath.patch"
```

Then run Flame's unit test task before integrating any UI:

```bash
cd /tmp/turtle-flame-baseline
./gradlew :app:testDebugUnitTest
```

This patch is intentionally not copied into Turtle's production launcher. Turtle
already has its own tested classpath implementation and must remain unchanged
until the isolated Flame runtime passes its baseline and test gates.

## Step 3: provision the exact SDL callback artifact

`patches/0002-provision-exact-sdl-glfw-callback-artifact.patch` adds the pinned
coordinate `org.lwjgl:lwjgl-glfw:3.4.3+4` only for SDL versions. It downloads the
upstream LWJGL 3.4.3 GLFW binding into the exact `3.4.3+4` library path, checks
that the required `GLFWErrorCallback.class` is present, rejects an HTTP failure,
and removes a corrupt cached copy before retrying.

Apply it after patch 0001:

```bash
git -C /tmp/turtle-flame-baseline apply \
  "$OLDPWD/migration/patches/0002-provision-exact-sdl-glfw-callback-artifact.patch"
```

The exact dependency is added for Minecraft 26.3/snapshot SDL versions only;
pre-26.3 versions keep their original dependency and legacy GLFW path. The
Flame ViewModel already converts download exceptions into an install/launch
error state, so a missing callback artifact is reported instead of starting a
known-invalid JVM.

## Step 4: preserve LTW and MobileGlues

`patches/0003-preserve-ltw-renderer.patch` keeps Turtle's LTW renderer in the
Flame target instead of silently reducing the renderer set to GL4ES, Zink,
Krypton, and MobileGlues. It:

- adds LTW to Flame's renderer model and renderer-selection UI;
- uses `POJAV_RENDERER=opengles3_ltw`;
- keeps `libltw.so` as both the LWJGL GL library and the EGL entry point;
- sets `POJAVEXEC_EGL=libltw.so` and the safe LTW environment flags;
- loads `libltw.so` before the game JVM and fails through Flame's existing error
  path if the required native library is unavailable;
- adds pure unit coverage for LTW and MobileGlues EGL/provider ordering.

MobileGlues remains Flame's existing SDL fallback and keeps its
`libmobileglues_info_getter.so,libmobileglues.so` load order. The patch does not
replace MobileGlues with LTW for SDL launches; that decision requires device
validation because SDL's GL backend must use the same EGL provider as LWJGL.

Apply it after patches 0001 and 0002, then run:

```bash
git -C /tmp/turtle-flame-baseline apply \
  "$OLDPWD/migration/patches/0003-preserve-ltw-renderer.patch"
cd /tmp/turtle-flame-baseline
./gradlew :app:testDebugUnitTest
```

## Step 5: port Turtle design tokens

`patches/0004-port-turtle-design-tokens.patch` ports Turtle's visual foundation
without overwriting Flame's resource IDs:

- copies all Turtle color values into `turtle_colors.xml` with collision-safe
  `turtle_` names;
- copies all Turtle strings and plurals into `turtle_strings.xml` with the same
  collision-safe namespace;
- changes Flame's existing Compose theme tokens to Turtle's dark palette,
  including the green accent, dark surfaces, text colors, borders, and semantic
  states;
- keeps Flame's existing resource files and screen wiring intact so this stage
  cannot remove a working screen.

The patch contains 124 Turtle color resources and 1,306 string/plural resources.
The XML files were parsed and validated after applying the patch to a clean Flame
checkout. Feature slices will consume the namespaced strings as their screens
are ported; no existing Flame string IDs are replaced in this stage.

`patches/0006-port-turtle-resources-and-assets.patch` completes the remaining
resource foundation without colliding with Flame names:

- 168 Turtle drawable/image resources, five Android animation resources, and
  the Turtle font are copied with `turtle_` names;
- dimensions, styles, arrays, language tables, keycodes, and mod categories are
  copied into namespaced values files;
- Turtle's `_Nsdp`/`_Nssp` references are converted to ordinary `dp`/`sp`
  compatibility resources, so Flame does not acquire a new sdp dependency;
- resource references inside copied XML are rewritten to the namespaced colors,
  strings, drawables, styles, animations, fonts, and dimensions.

The patch preserves Flame's existing resource IDs and does not replace its
working layouts. This lets the Compose screens adopt Turtle assets incrementally
while keeping the current launcher build intact.

## Step 6: port Turtle Compose motion

`patches/0005-port-turtle-compose-motion.patch` adds a reusable Compose motion
layer and wires it into Flame's existing shell rather than replacing navigation
or duplicating screens. `TurtleMotion` provides:

- configurable screen enter/exit, horizontal section/tab, sheet, dialog, and
  staggered list-item transitions;
- zoom/scale dialog motion, pulse, wobble, shake, and low-allocation press
  feedback;
- centralized durations/easing and a cap on staggered list work for low-end
  devices;
- animated selection colors/borders for the sidebar, tabs, version cards, and
  installed-instance cards;
- animated section-content changes, bottom launch-bar entry/exit, loader and
  Terracotta overlay entry, and launch-progress dialog content.

The patch preserves Flame's callbacks and state paths. It does not animate an
active game session or alter the SDL/LWJGL launch path. Compose's platform motion
scale remains the system-level reduced-motion gate; callers can pass `enabled =
false` to the auxiliary effects when a screen is migrated with an explicit
low-power policy.

Apply it after patch 0004:

```bash
git -C /tmp/turtle-flame-baseline apply \
  "$OLDPWD/migration/patches/0005-port-turtle-compose-motion.patch"
```

## Step 7: port assistant, log presentation, and animation settings

`patches/0007-port-assistant-log-coloring-and-animation-settings.patch` ports a
complete, usable Turtle support slice instead of adding screen-only placeholders:

- adds the offline Turtle Assistant to the Flame home sidebar, with deterministic
  status, renderer, memory, controls, content-pack, Terracotta, and crash-log
  guidance; unknown questions are reported as unknown and no cloud API key is
  required;
- adds error and warning line styling to the crash viewer while leaving normal
  log lines unhighlighted, with unit coverage for classification and line
  preservation;
- persists Turtle's global animation switch, exposes it in settings, and makes
  all migrated screen/dialog/tab/list/press motion honor the switch;
- refreshes the setting when the main Activity resumes, so changing the switch
  does not require a reinstall or a stale process restart.

This slice is wired to Flame's existing repositories and lifecycle; it does not
replace the Minecraft launch path or hide any renderer. The assistant is
explicitly offline and therefore remains safe on devices without network access.

The complete patch chain (0001 through 0007) applies cleanly to a fresh pinned
Flame checkout. Resource XML was parsed again after the chain was applied, and
`git diff --check` passed. The patched target has also assembled successfully in
GitHub Actions. Gradle compilation is not available in this workspace because it
does not currently provide `java` or `javac`.

## Build and download a test APK

The reproducible builder is `migration/build-flame.sh`. It creates an isolated
checkout, verifies the pinned commit, applies every migration patch in order, and
can assemble the debug APK without modifying this repository:

```bash
chmod +x migration/build-flame.sh
migration/build-flame.sh --build
```

The script prints the resulting APK path under:

```text
/tmp/turtle-flame-v220/app/build/outputs/apk/debug/
```

A JDK 17 or newer, Android SDK platform 36, build tools 36.0.0, NDK
27.0.12077973, and CMake 3.22.1 are required for a local build.

Before deleting this repository, export a standalone copy containing the
baseline pin, build script, documentation, and all patches:

```bash
migration/export-patch-bundle.sh /path/to/turtle-flame-migration.tar.gz
```

Extracting that archive preserves the same `migration/build-flame.sh` workflow;
it does not depend on this Git checkout or its Git history.

For a hosted build, `.github/workflows/build-flame-migration.yml` runs on pushes
to the migration branch, pull requests, or manual dispatch. Open the completed
GitHub Actions run and download the artifact named
`TurtleLauncher-flame-v220-debug-<commit>`. Install the debug APK with Android
platform tools or copy it to the device for testing. This is intentionally a
debug artifact and is not a signed release/update package.

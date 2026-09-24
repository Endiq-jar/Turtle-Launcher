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

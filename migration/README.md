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

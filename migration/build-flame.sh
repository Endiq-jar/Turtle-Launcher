#!/usr/bin/env bash
# Reproduce the pinned Flame migration target and optionally build its APK.
#
# Usage:
#   migration/build-flame.sh                 # clone/apply patches only
#   migration/build-flame.sh --build         # clone/apply patches and assemble debug APK
#   FLAME_WORKTREE=/tmp/my-flame migration/build-flame.sh --build
#
# The worktree is deliberately outside this repository. The source checkout is
# never modified and the migration patches remain the reviewable source of truth.
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BASELINE_FILE="$ROOT_DIR/migration/flame-baseline.properties"
WORKTREE=${FLAME_WORKTREE:-/tmp/turtle-flame-v220}
BUILD=0

for arg in "$@"; do
    case "$arg" in
        --build) BUILD=1 ;;
        -h|--help)
            sed -n '2,12p' "$0"
            exit 0
            ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

prop() {
    awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$BASELINE_FILE"
}

REPOSITORY=$(prop repository)
COMMIT=$(prop commit)
PATCH_DIR="$ROOT_DIR/migration/patches"
MARKER="$WORKTREE/.turtle-flame-migration-worktree"

if [[ -e "$WORKTREE" && ! -d "$WORKTREE/.git" ]]; then
    echo "Refusing to use $WORKTREE: it is not a Git checkout." >&2
    exit 1
fi

if [[ ! -d "$WORKTREE/.git" ]]; then
    mkdir -p "$(dirname "$WORKTREE")"
    git clone --no-checkout "$REPOSITORY" "$WORKTREE"
else
    if [[ ! -f "$MARKER" ]]; then
        echo "Refusing to reset an unmarked checkout: $WORKTREE" >&2
        echo "Choose another FLAME_WORKTREE or create it through this script." >&2
        exit 1
    fi
fi

git -C "$WORKTREE" fetch --quiet --depth=1 origin "$COMMIT" || true
git -C "$WORKTREE" checkout --quiet --detach "$COMMIT"
git -C "$WORKTREE" reset --hard --quiet "$COMMIT"
git -C "$WORKTREE" clean -fdxq
printf '%s\n' "This directory is managed by migration/build-flame.sh." > "$MARKER"

for patch in \
    "$PATCH_DIR/0001-reject-stale-glfw-from-sdl-classpath.patch" \
    "$PATCH_DIR/0002-provision-exact-sdl-glfw-callback-artifact.patch" \
    "$PATCH_DIR/0003-preserve-ltw-renderer.patch" \
    "$PATCH_DIR/0004-port-turtle-design-tokens.patch" \
    "$PATCH_DIR/0005-port-turtle-compose-motion.patch"; do
    [[ -f "$patch" ]] || { echo "Missing patch: $patch" >&2; exit 1; }
    echo "Applying $(basename "$patch")"
    git -C "$WORKTREE" apply --check "$patch"
    git -C "$WORKTREE" apply "$patch"
done

git -C "$WORKTREE" diff --check
printf '\nPatched Flame checkout: %s\n' "$WORKTREE"
printf 'Source commit: %s\n' "$COMMIT"

if (( BUILD == 1 )); then
    command -v java >/dev/null 2>&1 || {
        echo "java is required for --build. Install JDK 17+ and set JAVA_HOME." >&2
        exit 1
    }
    command -v javac >/dev/null 2>&1 || {
        echo "javac is required for --build. Install a full JDK, not only a JRE." >&2
        exit 1
    }
    chmod +x "$WORKTREE/gradlew"
    "$WORKTREE/gradlew" :app:assembleDebug --no-daemon
    APK=$(find "$WORKTREE/app/build/outputs/apk/debug" -maxdepth 1 -type f -name '*.apk' -print -quit)
    [[ -n "$APK" ]] || { echo "Gradle completed but no debug APK was produced." >&2; exit 1; }
    printf '\nDebug APK: %s\n' "$APK"
fi

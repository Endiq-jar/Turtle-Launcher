#!/usr/bin/env bash
# Export the migration as a standalone bundle before this repository is removed.
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REV=$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || printf 'snapshot')
OUTPUT=${1:-"$ROOT_DIR/../turtle-flame-migration-$REV.tar.gz"}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

BUNDLE="$TMP/turtle-flame-migration"
mkdir -p "$BUNDLE/migration/patches"
cp "$ROOT_DIR/migration/flame-baseline.properties" "$BUNDLE/migration/"
cp "$ROOT_DIR/migration/README.md" "$BUNDLE/migration/"
cp "$ROOT_DIR/migration/build-flame.sh" "$BUNDLE/migration/"
cp "$ROOT_DIR/migration/export-patch-bundle.sh" "$BUNDLE/migration/"
cp "$ROOT_DIR/migration/patches/"*.patch "$BUNDLE/migration/patches/"
chmod +x "$BUNDLE/migration/"*.sh

mkdir -p "$(dirname "$OUTPUT")"
tar -C "$TMP" -czf "$OUTPUT" turtle-flame-migration
printf 'Bundle: %s\n' "$OUTPUT"
sha256sum "$OUTPUT"

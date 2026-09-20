# Feature Audit — TurtleLauncher on the Amethyst 26.3 foundation

This document audits the features of the original TurtleLauncher (`af43039`) and the
Amethyst-Android stack it was rebuilt against, recording what was restored, what was
already present, and what was intentionally not carried over. It is the reference for
"did we lose anything?" questions about the rebuild.

Scope of the audit:

* **Old Turtle** — the pre-rebuild launcher (`af43039`), audited via the old tree.
* **Amethyst-Android** — the modern upstream foundation (26.3-era), re-verified at
  commit `3ad1100`.
* **New Turtle** — this repository, branch `arena/01a0ba2a-turtlelauncher`.

Legend: ✅ present · 🔄 restored/adapted during the rebuild · ➖ intentionally skipped ·
(n/a) not applicable to the new architecture.

## 1. Core launcher

| Feature | Old Turtle | Amethyst | New Turtle | Notes |
|---|---|---|---|---|
| Vanilla version install (all versions incl. 26.3+) | ✅ | ✅ | ✅ | New version-series UI (selector → series detail → install). |
| Microsoft / local / Ely.by / Battly accounts | ✅ | ✅ (MS/local) | ✅ | Ely.by and Battly carried over from old Turtle. |
| Skins, capes, chroma names | ✅ | partial | ✅ | Per-account profile page. |
| Custom instances / version isolation | ✅ | ✅ | ✅ | |
| Java runtime manager (8/17/21/25, OpenJ9, GraalVM) | ✅ | ✅ | ✅ | 26.x auto-selects Java 25. |
| Renderer selection | ✅ | ✅ | ✅ | |
| Virtual controls / gestures / mouse sim | ✅ | ✅ | ✅ | Amethyst control editor adapted (`CustomControlsActivity`, `ControlLayout`). |
| Gyro controls | ✅ | ✅ | ✅ | `GyroControl.java` from Amethyst. |
| Java GUI launcher (installer runtime) | ✅ | ✅ | ✅ | Amethyst's activity + `com.endiq.LoggerView` carried over. |
| Backup manager | ✅ | n/a | 🔄 | `TurtleBackupManager` (full spec preserved in git history). |
| Quick settings / fps boost / Turtle Client | ✅ | n/a | ✅ | |

## 2. Mod loaders & installables

| Loader | Old Turtle | Amethyst | New Turtle | Notes |
|---|---|---|---|---|
| Fabric + Fabric API | ✅ | ✅ | ✅ | |
| Forge | ✅ | ✅ | ✅ | Cache-force overloads adapted (`ForgeUtils`). |
| NeoForge | ✅ | ✅ | ✅ | |
| Quilt + QSL | ✅ | ✅ | ✅ | |
| OptiFine | ✅ | ✅ | ✅ | Cache-force overload adapted (`OptiFineUtils`). |
| Cleanroom | ✅ | n/a | ✅ | |
| **BTA (Better Than Adventure)** | n/a | ✅ | 🔄 | **Restored in this rebuild.** Amethyst task layer (`BTAUtils`, `BTADownloadTask`) surfaced in the install flow: `Addon.BTA`, row in `fragment_install_game.xml`, `DownloadBtaFragment`, `BtaInstallTask` wrapper. Installs `bta-<version>` (inherits `b1.7.3`) with its own profile. |
| **LWJGL3ify** | n/a | ✅ | 🔄 | **Restored in this rebuild.** Amethyst task layer (`LWJGL3ifyUtils`, `LWJGL3ifyDownloadTask`) surfaced the same way: `Addon.LWJGL3IFY`, install row, `DownloadLwjgl3ifyFragment`, `Lwjgl3ifyInstallTask` wrapper. Downloads jar + dependencies and creates its own instance/profile. |
| Modpacks (Modrinth `.mrpack`, CurseForge ZIP, import/export) | ✅ | ✅ | ✅ | |

### Integration design (BTA / LWJGL3ify)

Both are *self-contained* installers in the Amethyst stack — they download, install and
register their own version/profile without a GUI installer step. In new Turtle they are
first-class entries in the install-game screen, like other loaders:

* `Addons.kt` — new `BTA` and `LWJGL3IFY` enum entries; both exclusive in the
  compatibility map (they create standalone profiles).
* `fragment_install_game.xml` — paired rows (BTA with the retro grass-block icon,
  LWJGL3ify with the Java icon) following the Quilt/QSL row pattern.
* `DownloadBtaFragment` / `DownloadLwjgl3ifyFragment` — version pickers in the download
  center; BTA lists tested releases plus nightlies, LWJGL3ify lists supported versions
  plus SDL-incompatible ones (marked).
* `BtaInstallTask` / `Lwjgl3ifyInstallTask` — `InstallTask` wrappers that run the
  Amethyst tasks synchronously and propagate errors; they return `null` because the
  tasks self-install (same end-task treatment as Quilt).
* Selecting either queues a sticky `SelectInstallTaskEvent`, exactly like Forge et al.

## 3. Mod / resource browser

| Feature | Old Turtle | Amethyst | New Turtle | Notes |
|---|---|---|---|---|
| Mod search (Modrinth + CurseForge) | ✅ | ✅ | ✅ | |
| **Filters: platform, sort, category, mod loader** | partial | ✅ (dialog) | ✅ | New Turtle has filter *spinners* in the search bar (platform, sort, category, mod loader) — a superset of Amethyst's `dialog_mod_filters`. No port needed. |
| Version/loader compatibility checks on mod install | ✅ | ✅ | ✅ | Contextual: matches the currently selected version and loader. |
| Resource packs / shaders / worlds | ✅ | ✅ | ✅ | |
| Dependency info / updates / enable-disable | ✅ | ✅ | ✅ | |

## 4. Audited and intentionally skipped

These items exist as resources or ideas but have **no functional code** in either the
old or the Amethyst tree — they are vestigial and are *not* features that were lost:

* `dialog_per_version_control.xml` — per-version control layouts. No code references
  in Amethyst `3ad1100` or old Turtle; the current control system has no hook for it.
* `dialog_live_mouse_speed_editor.xml` — live mouse speed editor. Same status: no
  references, nothing to wire. Mouse speed remains adjustable in settings.
* Amethyst's `SearchModFragment` (and its filter dialog) — superseded by new Turtle's
  browser with built-in filters (see §3).

## 5. Branding & identity sweep

* 29 locale files updated (app name, socket paths, etc.); no user-facing "Pojav"
  strings remain.
* `TOUCHCONTROLLER` socket rename preserved.
* Theme overlays (`ThemeOverlay_Turtle_*`) and Turtle drawables/styles validated.
* `about_en.txt` attribution kept in sync with the Amethyst stack.

## 6. Build & CI notes

* The Amethyst toolchain is used (AGP 9, Kotlin 2.3.20, `newDsl=false`,
  `builtInKotlin=false`).
* `zstd-jni 1.5.7-6` is consumed as `@aar` from Maven Central (verified listing).
* Compile-fix loop history and the false-positive resource catalog (resources that
  look unused but must not be removed) are tracked in the session that performed the
  rebuild; the catalog itself lives in this document's commit messages.

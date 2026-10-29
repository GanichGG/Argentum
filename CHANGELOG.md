# Changelog

Release notes are taken from the section whose heading matches the version (see `tools/release_notes.py`).

## [Unreleased] (1.1)

- **Error screen 2.0.** When the game crashes, an Argentum window says in plain words what happened, which mod is the likely
  cause and what to press: disable that mod, restore the last working set, show the diagnostics bundle, copy a summary for
  a forum, report to the author.
- **Root cause.** The crash report's chain of errors is unwrapped down to the real cause; when the final error is generic,
  the first real error from the log is shown. Mod code at the place of the error weighs most when looking for the culprit.
- **Crash-loop protection.** After two failed starts in a row the game asks before loading mods: restore the last working
  set, disable the likely culprit, start once without mods, or start as is.
- **Undo to last working.** Every successful start saves the mod set (old jars are kept by hard link), so "updated a mod
  and everything broke" is one button away: in the crash window, in the crash-loop dialog and in the catalog.
- **Diagnostics bundle** now has a README on how to send it, the crash analysis and the changes since the last working set.
- **Shaders and resource packs** in the catalog: Mods / Shaders / Resource packs switcher, own filters, install, update,
  disable and remove, no restart needed. An "Argentum catalog" button on the vanilla Resource Packs screen; shaders
  without Iris offer to install it.

## [1.0] - 2026-10-12

First stable release.

- License: ArgentumLoader is now **All Rights Reserved** (c) 2026 GanichGG. The text is in `LICENSE`; the code that comes from
  Fabric Loader keeps the Apache License 2.0 (`LICENSE-APACHE-2.0.txt`). Using the unmodified official builds is permitted.
- The loader's `fabric.mod.json` and the jar list both licences.
- Version 1.0 (SemVer `1.0.0` for mods): `"depends": {"argentumloader": ">=1.0.0"}`.

## [0.1B3] - 2026-10-11 (beta)

- Installer: the language now follows the language of the system at run time (the downloaded exe was always English,
  because the language of the build machine was baked into it).

## [0.1B2] - 2026-10-11 (beta)

- New installer window in the style of the Fabric installer, with standard Windows controls: it finds Prism Launcher
  instances and the official launcher's `.minecraft` by itself, so `ArgentumAnchor.exe` can be run from anywhere (it no
  longer has to be placed into the game folder). The **...** button picks any other folder. Unsupported Minecraft versions and
  instances that already contain Fabric Loader are reported before installing.
- The loader and the catalog are installed together, there is no separate choice.

## [0.1B1] - 2026-10-11 (beta)

First public build of ArgentumLoader, a fork of Fabric Loader 0.19.5 (that is the version mods see as `fabricloader`).

Versions look like `0.1B1`: `x` global version, `y` sub-version, then a stage letter (`A` alpha, `B` beta, none for a stable
release) and a build number. Mods and the loader see the SemVer form of the same version (`0.1.0-b.1`) under the mod id
`argentumloader`, so a mod can require it: `"depends": {"argentumloader": ">=0.1.0-b.1"}`.

### Installer
- Prism: ArgentumLoader is recognised when `ArgentumAnchor.exe` is run from the instance folder **or** from its `minecraft`
  folder (the one Prism's *Folder* button opens); re-running it updates the component and removes older loader jars.
- Unsigned for now: Windows SmartScreen may warn ("More info" -> "Run anyway").

### Loader
- Fail-safe startup: a mod whose entrypoint fails is disabled (also for the next launch) instead of crashing the game;
  mods that depend on it are skipped; the game reports what was disabled and why. Client only; `-Dargentum.failsafe=false`
  turns it off.
- Preflight check of the final mod set with the loader's own resolver before changes are applied; changes are rolled back
  with a readable reason when the set cannot start.
- Removal and disabling of mods whose jar is locked by the running game finish on the next start.
- Readable dependency errors in English (default) and Russian.
- Startup profile in `.argentum/startup-profile.txt`.
- The loader provides the mod id `argentumloader`.
- Hot-loading of safe mods (first stage, used by tooling only).

### Catalog (argentum-client)
- Modrinth catalog: search, sort, categories, version picker, dependencies.
- Change queue and **Apply changes**: download, preflight, hidden copy of the game, seamless window swap, rollback on error.
- Enable/disable mods, updates (by file hash), `.mrpack` import, crash diagnosis, built-in settings editor.
- English and Russian interface.

### Known limitations
- Minecraft 1.21.1, Windows, Fabric mods only.
- The installer is not code-signed.

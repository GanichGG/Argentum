# Changelog

Release notes are taken from the section whose heading matches the version (see `tools/release_notes.py`).

## [0.19.5+argentum.1] - unreleased

First public build of ArgentumLoader (a fork of Fabric Loader 0.19.5).

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

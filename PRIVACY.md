# Privacy

ArgentumLoader has **no telemetry**, no accounts and no analytics. It never uploads your worlds, logs or settings anywhere on
its own.

## Network requests

Only the catalog talks to the network, and only to Modrinth:

| Why | Where | What is sent |
|---|---|---|
| Search, project pages, version lists | `api.modrinth.com` | your search text, the game version, the loader name (`fabric`) |
| Finding the mods you already have, and updates | `api.modrinth.com` | the **SHA-1 hashes of the jar files in your `mods` folder** (not the files) |
| Downloads and icons | `cdn.modrinth.com` | the file or image being requested |
| Importing a `.mrpack` | the hosts listed in the pack, **only** `cdn.modrinth.com`, `github.com`, `raw.githubusercontent.com`, `gitlab.com` | the file being requested |

Requests carry a `User-Agent` that names ArgentumLoader and its repository (Modrinth asks for this). Your IP address is, as
with any web request, visible to those servers; the `HTTPS_PROXY` / `ALL_PROXY` environment variables are honoured.

Everything downloaded is checked against the SHA-1 published by Modrinth.

## Data stored on your computer

- `.argentum/` in the game folder: catalog cache (search results, descriptions, icons), the startup profile, reports.
- `mods/.argentum-modrinth.json`: which catalog projects and versions you installed.
- `mods/.argentum-pending-delete`: removals that wait for the next start.
- `.argentum/handoff/`: temporary files used while switching to a new copy of the game.

You can delete these any time; they are rebuilt when needed.

## Diagnostics

The catalog can create a diagnostics bundle for bug reports. It is created **locally**; nothing is sent. It contains version
numbers, the list of mods, the startup profile and recent logs, with your user name replaced in file paths. Look inside before
you attach it to a public issue.

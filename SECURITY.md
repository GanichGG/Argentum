# Security policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Use GitHub's private
[security advisory form](https://github.com/GanichGG/ArgentumLoader/security/advisories/new).
Include what you found, how to reproduce it and which version is affected.
We aim to answer within a few days.

## What is in scope

- The loader, the bundled client mod and the installer (`ArgentumAnchor.exe`).
- How the catalog downloads and verifies files from Modrinth (SHA-1 checks, allowed hosts for `.mrpack` packs, path
  restrictions when importing packs).

## Verifying a release

Every release publishes `SHA256SUMS.txt`. Compare the checksum of what you downloaded:

```
certutil -hashfile ArgentumAnchor.exe SHA256
```

The Windows installer is not code-signed yet, so SmartScreen may show a warning ("More info" -> "Run anyway"). Always take
files from the official Releases page of this repository.

## Supported versions

Only the latest release receives fixes.

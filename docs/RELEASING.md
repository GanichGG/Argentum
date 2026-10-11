# Releasing ArgentumLoader

## Versions

`0.19.5+argentum.N`: the base (`VERSION` in `FabricLoaderImpl.java`) is the Fabric Loader version this fork is based on;
`N` is the ArgentumLoader build number. Local builds are `0.19.5+local`. A release build gets its number from
`-PargentumRelease=N` (CI does this from the tag).

## Steps

1. Make sure `master` is green (CI: translations, checkstyle, tests, jars).
2. Add a section `## [0.19.5+argentum.N]` to `CHANGELOG.md` (the release notes are taken from it).
3. Run the manual checklist below on a clean Prism instance.
4. Tag and push: `git tag v0.19.5-argentum.N && git push origin v0.19.5-argentum.N`.
5. The **Release** workflow builds the loader jar, the client mod and (on Windows) `ArgentumAnchor.exe`, writes
   `SHA256SUMS.txt` and publishes the GitHub release. If the installer job fails the release still goes out with the jars;
   build the installer locally (below) and attach it by hand.

## Local build of everything

```
./gradlew -PargentumRelease=N checkstyleMain checkstyleTest test finalJar
cd argentum-client && ../gradlew -PargentumRelease=N build && cd ..
export JAVA_HOME=<path to GraalVM Community 21> GRAALVM_HOME=<same path>   # e.g. ~/.gradle/jdks/graalvm_community-21-amd64-windows.2
./gradlew -PargentumRelease=N :installer:nativeCompile      # needs MSVC (Visual Studio Build Tools); result: installer/build/native/nativeCompile/ArgentumAnchor.exe
```

Rebuild the installer **every time** the loader or the client changes - it embeds both jars.

## Manual checklist (clean Prism instance, Minecraft 1.21.1)

- [ ] Fresh install with the installer; the component appears in Prism; the game starts to the title screen.
- [ ] Catalog opens; search, filters, sort work; icons load.
- [ ] Install a mod with a dependency -> Apply changes -> the window switches, the mod works.
- [ ] Remove a mod, disable and enable a mod -> Apply changes.
- [ ] Update a mod and choose a specific older version.
- [ ] Import a `.mrpack`.
- [ ] A conflicting set (e.g. remove Sodium while Iris is installed) shows the "Cannot apply" window and changes nothing.
- [ ] A broken mod is disabled and reported at start; "Restart without them" works.
- [ ] Switch the game language: English and Russian both look right.
- [ ] No network: the catalog shows the cache and a readable message.
- [ ] Uninstall as described in the README.

## Code signing

The installer is unsigned, so SmartScreen warns. To fix that, buy a code-signing certificate and sign
`ArgentumAnchor.exe` with `signtool` after `nativeCompile` (before computing the checksums). Until then the README explains the
warning and the release carries `SHA256SUMS.txt`.

## Supporting another Minecraft version

The installer currently offers `1.21.1` only (`SUPPORTED_VERSIONS` in `AnchorMain`). A new version needs: intermediary names
checked in the client mixins, a Prism patch for it, and a full pass of the manual checklist.

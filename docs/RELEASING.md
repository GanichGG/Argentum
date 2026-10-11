# Releasing ArgentumLoader

## Versions

The product version is written **once**, in `ArgentumVersion.DISPLAY` (`src/main/java/net/fabricmc/loader/impl/ArgentumVersion.java`).
Format `x.y[letter number]`: `x` global version, `y` sub-version, then the stage - `A` alpha, `B` beta, nothing for a stable
release - and the build number. Examples: `0.1A3`, `0.1B1`, `0.1`, `1.0B2`.

- Humans see `0.1B1` (releases, tags `v0.1B1`, file names, Prism).
- The loader and mods see the SemVer form `0.1.0-b.1` (mod id `argentumloader`), so ranges in `depends` work and the order is
  right: `0.1.0-a.3 < 0.1.0-b.1 < 0.1.0`. The conversion is `ArgentumVersion.toSemver`; the build derives the same value.
- `fabricloader` keeps the upstream number (`0.19.5`, `FabricLoaderImpl.VERSION`) because mods depend on it. Local builds are
  `0.19.5+local`, release builds `0.19.5+argentum.<version>`.
- Alpha/beta versions are published as GitHub pre-releases automatically; a stable `x.y` is a normal release.
- Client mod files carry the game version: `argentum-client-0.1B1+mc1.21.1.jar` (its mixins depend on the Minecraft version).
- Build variants (client/server/dev) are not part of the version; if they appear they go into the file name.

When the upstream Fabric Loader is updated, change `VERSION` and bump the ArgentumLoader sub-version (`y`).

## Steps

1. Make sure `master` is green (CI: translations, checkstyle, tests, jars, installer).
2. Change `ArgentumVersion.DISPLAY` and add a section `## [0.1B1]` to `CHANGELOG.md` (the release notes are taken from it).
3. Run the manual checklist below on a clean Prism instance.
4. Tag and push: `git tag v0.1B1 && git push origin v0.1B1` (the tag must equal `v` + `DISPLAY`, the workflow checks it).
5. The **Release** workflow builds the loader jar, the client mod and (on Windows) `ArgentumAnchor.exe`, writes
   `SHA256SUMS.txt` and publishes the GitHub release. If the installer job fails the release still goes out with the jars;
   build the installer locally (below) and attach it by hand.

## Local build of everything

```
./gradlew -PargentumRelease=1 :checkstyleMain :checkstyleTest :test :finalJar
cd argentum-client && ../gradlew build && cd ..
export JAVA_HOME=<path to GraalVM Community 21> GRAALVM_HOME=<same path>   # e.g. ~/.gradle/jdks/graalvm_community-21-amd64-windows.2
./gradlew -PargentumRelease=1 :installer:nativeCompile      # needs MSVC (Visual Studio Build Tools); result: installer/build/native/nativeCompile/ArgentumAnchor.exe
```

Rebuild the installer **every time** the loader or the client changes - it embeds both jars.

If the installer window changes (new Swing components, dialogs), regenerate the GraalVM reflection config so the native
exe keeps the Windows look: build the installer jar (`./gradlew :installer:jar`), then run it with the GraalVM tracing agent -
`<GraalVM>/bin/java -agentlib:native-image-agent=config-merge-dir=installer/src/main/resources/META-INF/native-image/net.fabricmc.installer/argentum-anchor -cp installer/build/libs/argentum-anchor-*.jar net.fabricmc.installer.anchor.AnchorMain --trace-ui`
(the flag opens the window, the lists and the dialogs by itself and exits). Delete the empty `predefined-classes-config.json`
the agent adds.

## Ручная проверка перед релизом (чистый инстанс Prism, Minecraft 1.21.1)

Бета- и альфа-версии (`0.1B1`, `0.1A3`) публикуются как пре-релизы автоматически. Когда все пункты отмечены и выходит
стабильная версия (`0.1`), она публикуется как обычный релиз.

- [x] **Установка с нуля.** Запустить `ArgentumAnchor.exe` (откуда угодно), выбрать инстанс в списке, «Установить». В Prism
  *Edit Instance -> Version* появился **ArgentumLoader**, игра доходит до главного экрана.
- [x] **Каталог.** Открывается кнопкой «Моды Argentum»; работают поиск, фильтры и сортировка; иконки подгружаются.
- [x] **Установка мода с зависимостью.** «Установить», затем «Применить изменения»: окно само переключается на новую
  копию игры, мод работает.
- [x] **Удаление, отключение, включение.** Удалить один мод, отключить и включить другой, нажать «Применить изменения».
  Удалённый мод не возвращается в списке ни при смене вкладок, ни после перезапуска.
- [x] **Обновление и выбор версии.** Обновить мод; поставить конкретную более старую версию через «Выбрать версию».
- [x] **Импорт `.mrpack`.** Кнопка «Импорт сборки» или перетаскивание файла в окно.
- [x] **Конфликтный набор.** Удалить Sodium при установленном Iris: появляется окно «Нельзя применить изменения» с
  причиной, ничего не меняется.
- [ ] **Сломанный мод.** Положить мод, который падает при запуске: игра стартует, появляется окно «Не запустились и
  отключены», кнопка «Перезапустить без них» работает.
- [x] **Язык.** Переключить язык игры: интерфейс на английском и на русском выглядит правильно (без обрезанных и
  перемешанных надписей).
- [x] **Без сети.** Каталог показывает данные из кэша и понятное сообщение, а не зависает.
- [x] **Диагностика.** Кнопка «Диагностика» создаёт zip; внутри нет имени пользователя и пути к домашней папке.
- [ ] **Удаление.** Убрать ArgentumLoader по инструкции из README: игра запускается на обычном Fabric.

## Code signing

The installer is unsigned, so SmartScreen warns. To fix that, buy a code-signing certificate and sign
`ArgentumAnchor.exe` with `signtool` after `nativeCompile` (before computing the checksums). Until then the README explains the
warning and the release carries `SHA256SUMS.txt`.

## Supporting another Minecraft version

The installer currently offers `1.21.1` only (`SUPPORTED_VERSIONS` in `AnchorMain`). A new version needs: intermediary names
checked in the client mixins, a Prism patch for it, and a full pass of the manual checklist.

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

## Ручная проверка перед релизом (чистый инстанс Prism, Minecraft 1.21.1)

Пока все пункты не отмечены, релиз остаётся пре-релизом (`prerelease: true` в `release.yml`). После проверки снять флаг на
странице релиза («Set as the latest release»).

- [ ] **Установка с нуля.** Положить `ArgentumAnchor.exe` в папку нового инстанса и запустить. В Prism
  *Edit Instance -> Version* появился **ArgentumLoader**, игра доходит до главного экрана.
- [ ] **Каталог.** Открывается кнопкой «Моды Argentum»; работают поиск, фильтры и сортировка; иконки подгружаются.
- [ ] **Установка мода с зависимостью.** «Установить», затем «Применить изменения»: окно само переключается на новую
  копию игры, мод работает.
- [ ] **Удаление, отключение, включение.** Удалить один мод, отключить и включить другой, нажать «Применить изменения».
  Удалённый мод не возвращается в списке ни при смене вкладок, ни после перезапуска.
- [ ] **Обновление и выбор версии.** Обновить мод; поставить конкретную более старую версию через «Выбрать версию».
- [ ] **Импорт `.mrpack`.** Кнопка «Импорт сборки» или перетаскивание файла в окно.
- [ ] **Конфликтный набор.** Удалить Sodium при установленном Iris: появляется окно «Нельзя применить изменения» с
  причиной, ничего не меняется.
- [ ] **Сломанный мод.** Положить мод, который падает при запуске: игра стартует, появляется окно «Не запустились и
  отключены», кнопка «Перезапустить без них» работает.
- [ ] **Язык.** Переключить язык игры: интерфейс на английском и на русском выглядит правильно (без обрезанных и
  перемешанных надписей).
- [ ] **Без сети.** Каталог показывает данные из кэша и понятное сообщение, а не зависает.
- [ ] **Диагностика.** Кнопка «Диагностика» создаёт zip; внутри нет имени пользователя и пути к домашней папке.
- [ ] **Удаление.** Убрать ArgentumLoader по инструкции из README: игра запускается на обычном Fabric.

## Code signing

The installer is unsigned, so SmartScreen warns. To fix that, buy a code-signing certificate and sign
`ArgentumAnchor.exe` with `signtool` after `nativeCompile` (before computing the checksums). Until then the README explains the
warning and the release carries `SHA256SUMS.txt`.

## Supporting another Minecraft version

The installer currently offers `1.21.1` only (`SUPPORTED_VERSIONS` in `AnchorMain`). A new version needs: intermediary names
checked in the client mixins, a Prism patch for it, and a full pass of the manual checklist.

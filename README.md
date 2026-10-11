# ArgentumLoader

A Fabric-compatible mod loader with a built-in mod manager. Pick mods in a catalog inside the game, press **Apply changes**,
and the game switches to the new mod set by itself - no manual restarts, and a broken mod can no longer take the whole
game down with it.

*[Русская версия ниже](#русская-версия)*

## What makes it different from Fabric Loader

Loader level (things a regular Fabric mod cannot do):

- **Fail-safe startup.** If a mod crashes while starting, the game keeps running. The mod (and everything that depends on
  it) is disabled and the game tells you which mods were disabled and why.
- **Preflight check.** Before any change is applied, the final mod set is run through the loader's own dependency
  resolver. If it cannot start, nothing is changed and you get a readable reason ("Install sodium 0.8.x - Iris needs it").
- **Locked files handled.** Windows keeps the jars of a running game locked. Removing or disabling such a mod is finished
  automatically when the new copy of the game starts.
- **Readable errors**, in English and Russian, instead of raw exceptions.
- **Startup profile** (`.argentum/startup-profile.txt`): where the launch time goes - loader phases, slowest mods,
  Mixin time.
- The loader provides the mod id `argentumloader`, so a mod can require it: `"depends": {"argentumloader": ">=0.1.0-b.1"}`.

Catalog (the bundled `argentum-client` mod, shown as **Argentum Mods** on the title screen):

- Search and install from [Modrinth](https://modrinth.com) with dependencies; sort, filter by category, version picker.
- **Queue and "Apply changes".** Install, remove, disable, enable and update mods; nothing happens until you confirm.
  Then the files are downloaded, the set is checked, a hidden copy of the game starts with the new mods and replaces the
  window seamlessly. If anything is wrong the changes are rolled back.
- Updates (checked by file hash), `.mrpack` import, disabling mods without deleting them.
- Crash diagnosis: after a crash it names the likely mod and offers to disable it.
- A built-in editor for mod settings (JSON, JSON5, TOML, properties).
- Languages: English and Russian (follows the game language).

## Versions

`0.1B1` = global version 0, sub-version 1, **B**eta build 1 (`A` alpha, no letter - stable). Mods see the same version as
SemVer `0.1.0-b.1` under the id `argentumloader`; `fabricloader` stays at the Fabric Loader version this fork is based on.

## Compatibility

| | |
|---|---|
| Mods | Fabric mods (`fabric.mod.json`). Forge / NeoForge mods are **not** supported. |
| Minecraft | 1.21.1 |
| OS | Windows (other systems are untested) |
| Launchers | Prism Launcher, official launcher (profile) |

## Install

1. Download `ArgentumAnchor.exe` from the [latest release](https://github.com/GanichGG/ArgentumLoader/releases/latest)
   and check it against `SHA256SUMS.txt`.
2. Put it into your game folder and run it once: for the official launcher that is `.minecraft`; for Prism it is the instance
   folder (the one with `instance.cfg`) **or** its `minecraft` subfolder - the *Folder* button in Prism opens the latter, both work.
3. Prism: open *Edit Instance -> Version* - **ArgentumLoader** is there. Official launcher: choose the **ArgentumLoader**
   profile. Libraries are downloaded by the launcher on first start.
4. Start the game and press **Argentum Mods** on the title screen.

The installer is not code-signed yet, so Windows SmartScreen may warn about it (*More info -> Run anyway*).

### Uninstall

Prism: *Edit Instance -> Version*, remove **ArgentumLoader** and add Fabric Loader back; delete `argentum-client-*.jar`
from `mods`. You may also delete the `.argentum` folder and the `.argentum-*` files in `mods`.

## Privacy

No telemetry. The catalog talks only to Modrinth (search, downloads, and file hashes of your mods to find updates). See
[PRIVACY.md](PRIVACY.md).

## Build

JDK 21 is required.

```
./gradlew checkstyleMain checkstyleTest test finalJar      # loader -> build/libs
cd argentum-client && ../gradlew build                      # client mod
python tools/check_translations.py                          # every Lang.tr("...") has an English entry
```

Details for maintainers: [docs/RELEASING.md](docs/RELEASING.md). Changes: [CHANGELOG.md](CHANGELOG.md).
Security issues: [SECURITY.md](SECURITY.md).

### Translations

User-visible text is written in Russian in the code as `Lang.tr("original")`; the tables are
`src/main/resources/argentum/lang/<language>.json` (loader) and `argentum-client/src/main/resources/argentum/lang/<language>.json`
(client). To add a language, copy `en.json` to e.g. `de.json` and translate the values.

## License

ArgentumLoader is a fork of [FabricMC/fabric-loader](https://github.com/FabricMC/fabric-loader).

- Original ArgentumLoader code (the catalog client, the Modrinth integration, the hot-loader, fail-safe startup,
  preflight, the installer anchor) is **All Rights Reserved**, (c) 2026 GanichGG. The exact list of files is in
  [LICENSE-ARGENTUM.txt](LICENSE-ARGENTUM.txt). Using the unmodified official builds is permitted.
- The rest of the repository is derived from Fabric Loader and stays under the **Apache License 2.0** ([LICENSE](LICENSE));
  that licence cannot be removed from upstream code.
- Bundled third-party libraries and fonts keep their own licences.

---

# Русская версия

Fabric-совместимый загрузчик модов со встроенным менеджером модов. Выбираешь моды в каталоге прямо в игре, нажимаешь
**«Применить изменения»**, и игра сама переключается на новый набор модов - без ручных перезапусков. А сломанный мод больше
не может уронить всю игру.

## Чем отличается от Fabric Loader

В самом загрузчике (то, чего не сделает обычный мод):

- **Безопасный запуск.** Если мод падает при старте, игра продолжает работать: мод (и зависящие от него) отключается, а игра
  показывает, что отключено и почему.
- **Предпроверка.** Перед применением изменений итоговый набор прогоняется через настоящий разрешитель зависимостей
  загрузчика. Если он не запустится, ничего не меняется, а причина понятна («Установите sodium 0.8.x - он нужен Iris»).
- **Заблокированные файлы.** Windows держит jar запущенной игры. Удаление или отключение такого мода завершается само при
  запуске новой копии игры.
- **Читаемые ошибки** на английском и русском.
- **Профиль запуска** (`.argentum/startup-profile.txt`): куда уходит время запуска.
- Загрузчик предоставляет идентификатор `argentumloader`, мод может требовать его: `"depends": {"argentumloader": ">=0.1.0-b.1"}`.

Каталог (встроенный мод `argentum-client`, кнопка **«Моды Argentum»** на главном экране):

- Поиск и установка с [Modrinth](https://modrinth.com) вместе с зависимостями, сортировка, категории, выбор версии.
- **Очередь и «Применить изменения».** Установка, удаление, отключение, включение и обновление - только после
  подтверждения: скачивание, проверка набора, скрытая копия игры с новыми модами и бесшовная подмена окна. При ошибке
  изменения откатываются.
- Обновления (по хешам файлов), импорт `.mrpack`, отключение модов без удаления.
- Диагностика падений: называет вероятный мод и предлагает его отключить.
- Встроенный редактор настроек модов (JSON, JSON5, TOML, properties).
- Языки: английский и русский (по языку игры).

## Совместимость

Моды Fabric (`fabric.mod.json`); моды Forge / NeoForge **не поддерживаются**. Minecraft 1.21.1. Windows. Prism Launcher и
официальный лаунчер (профиль).

## Установка

1. Скачай `ArgentumAnchor.exe` из [последнего релиза](https://github.com/GanichGG/ArgentumLoader/releases/latest) и
   сверь с `SHA256SUMS.txt`.
2. Положи его в папку игры и запусти один раз: для официального лаунчера это `.minecraft`; для Prism - папка инстанса (где
   `instance.cfg`) **или** его подпапка `minecraft` (её открывает кнопка *Folder* в Prism), подойдёт любая.
3. Prism: *Edit Instance -> Version* - там появится **ArgentumLoader**. Официальный лаунчер: профиль **ArgentumLoader**.
4. Запусти игру и нажми **«Моды Argentum»** на главном экране.

Установщик пока без подписи, поэтому SmartScreen может предупредить (*Подробнее -> Выполнить в любом случае*).

Удаление: в Prism убери ArgentumLoader из *Edit Instance -> Version*, верни Fabric Loader и удали `argentum-client-*.jar` из
`mods`. Папку `.argentum` и файлы `.argentum-*` в `mods` тоже можно удалить.

## Приватность и лицензия

Телеметрии нет, подробности в [PRIVACY.md](PRIVACY.md). Оригинальный код ArgentumLoader - **All Rights Reserved**, остальное
(код форка Fabric Loader) остаётся под Apache 2.0: см. [LICENSE-ARGENTUM.txt](LICENSE-ARGENTUM.txt) и [LICENSE](LICENSE).

#!/usr/bin/env python3
"""Проверка переводов: каждая русская строка в Lang.tr("...") должна иметь запись в таблицах argentum/lang/en.json.

Запуск из корня репозитория: python tools/check_translations.py
Код возврата 1, если есть непереведённые строки (для CI). Также сообщает о записях, которых больше нет в коде.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CYR = re.compile('[Ѐ-ӿ]')
TR = re.compile(r'Lang\.tr\(\s*"((?:[^"\\]|\\.)*)"')

SOURCES = [
    ('argentum-core/src/main/java', 'argentum-core/src/main/resources/argentum/lang-core'),
    ('src/main/java', 'src/main/resources/argentum/lang'),
    ('minecraft/src/main/java', 'src/main/resources/argentum/lang'),
    ('argentum-client/src/main/java', 'argentum-client/src/main/resources/argentum/lang'),
]


def unescape(s):
    return (s.replace('\\n', '\n').replace('\\t', '\t').replace('\\"', '"').replace('\\\\', '\\'))


def used_keys(src):
    keys = set()

    for d, _, files in os.walk(os.path.join(ROOT, src)):
        for f in files:
            if f.endswith('.java'):
                text = open(os.path.join(d, f), encoding='utf-8', errors='ignore').read()

                for m in TR.finditer(text):
                    if CYR.search(m.group(1)):
                        keys.add(unescape(m.group(1)))

    return keys


def main():
    bad = 0
    tables = {}

    for src, lang_dir in SOURCES:
        keys = used_keys(src)
        path = os.path.join(ROOT, lang_dir, 'en.json')
        table = json.load(open(path, encoding='utf-8')) if os.path.exists(path) else {}
        tables[path] = tables.get(path, set()) | keys
        missing = sorted(k for k in keys if not table.get(k))
        bad += len(missing)

        for k in missing:
            print('НЕТ ПЕРЕВОДА (%s): %r' % (lang_dir, k))

    for path, used in tables.items():
        table = json.load(open(path, encoding='utf-8')) if os.path.exists(path) else {}
        # ключи могут задаваться вне Lang.tr (например, таблицы с оригиналами) — только предупреждаем
        stale = sorted(k for k in table if k not in used)

        for k in stale:
            print('предупреждение: запись не найдена в коде (%s): %r' % (os.path.relpath(path, ROOT), k))

    print('непереведённых строк:', bad)

    return 1 if bad else 0


sys.exit(main())

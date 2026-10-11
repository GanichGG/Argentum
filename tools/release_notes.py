#!/usr/bin/env python3
"""Печатает заметки релиза: раздел CHANGELOG.md для заданной версии.

Использование: python tools/release_notes.py 0.19.5+argentum.1
Раздел начинается заголовком "## [0.19.5+argentum.1]" и идёт до следующего заголовка "## [".
Если раздела нет, печатает короткую заглушку (релиз всё равно выпускается), а в stderr - предупреждение.
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else ''
    path = os.path.join(ROOT, 'CHANGELOG.md')
    lines = open(path, encoding='utf-8').read().splitlines() if os.path.exists(path) else []
    out = []
    inside = False

    for line in lines:
        if line.startswith('## ['):
            if inside:
                break

            inside = line.startswith('## [' + version + ']')
            continue

        if inside:
            out.append(line)

    text = '\n'.join(out).strip()

    if not text:
        sys.stderr.write('warning: no CHANGELOG.md section for %s\n' % version)
        text = 'ArgentumLoader %s. See the repository for details.' % version

    footer = ('\n\n---\nChecksums are in `SHA256SUMS.txt`. Install: run `ArgentumAnchor.exe`, or put the loader jar into your launcher '
              'as described in the README.')
    print(text + footer)


main()

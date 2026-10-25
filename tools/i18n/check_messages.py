#!/usr/bin/env python3
"""Checks that messages.properties (English, default) and messages_pt.properties (Portuguese) have the same keys
and the same %s / %d placeholders, and that no Portuguese text was left in the English file.

    python3 tools/i18n/check_messages.py
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', '..', 'ashv', 'src', 'main', 'resources')


def load(name):
    out = {}
    with open(os.path.join(RES, name), encoding='utf-8') as f:
        for line in f:
            m = re.match(r'^([A-Za-z][^=]*)=(.*)$', line.rstrip('\n'))
            if m:
                if m.group(1) in out:
                    print('DUPLICATE', name, m.group(1))
                out[m.group(1)] = m.group(2)
    return out


en, pt = load('messages.properties'), load('messages_pt.properties')
placeholders = lambda s: re.findall(r'%(?:\d+\$)?[sd]', s)
problems = 0
for key in sorted(set(en) | set(pt)):
    if key not in en or key not in pt:
        print('MISSING in', 'messages.properties' if key not in en else 'messages_pt.properties', key)
        problems += 1
    elif placeholders(en[key]) != placeholders(pt[key]):
        print('PLACEHOLDERS differ', key, placeholders(en[key]), placeholders(pt[key]))
        problems += 1
accents = re.compile(r'[ãçéêáíóõúâÃÇÉÊÁÍÓÚ]')
for key, value in en.items():
    if accents.search(value) and not key.startswith('language.'):
        print('PORTUGUESE in messages.properties?', key, value[:60])
        problems += 1
print(len(en), 'keys,', problems, 'problem(s)')
sys.exit(1 if problems else 0)

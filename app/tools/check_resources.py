#!/usr/bin/env python3
"""Check translation coverage, formatting arguments and product wording without extra dependencies."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'src/main/res'
def strings(locale):
    return {e.attrib['name']: ''.join(e.itertext()) for e in ET.parse(root / locale / 'strings.xml').getroot()}

en = strings('values')
bg = strings('values-bg')
assert en.keys() == bg.keys(), (en.keys() - bg.keys(), bg.keys() - en.keys())
for name in en:
    placeholders = lambda s: sorted(re.findall(r'%\d+\$[dsf]', s))
    assert placeholders(en[name]) == placeholders(bg[name]), name
    for text in (en[name], bg[name]):
        assert not re.search(r'diagnos|injury|screening|диагноз|скрининг|риск от травм', text, re.I), (name, text)
print(f'{len(en)} strings: English/Bulgarian keys and format arguments match; wording check passed.')

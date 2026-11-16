#!/usr/bin/env python3
"""Small adb helper for the dedicated JumpScan emulator; screenshots stay in gitignored logs."""
import argparse
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = '/opt/android-sdk/platform-tools/adb'
def remote(args, binary=False):
    command = ' '.join(shlex.quote(x) for x in [ADB, *args])
    return subprocess.check_output(['ssh', 'android-build-kit', command], text=not binary, timeout=30)

def nodes():
    remote(['shell', 'uiautomator', 'dump', '/sdcard/window.xml'])
    return list(ET.fromstring(remote(['shell', 'cat', '/sdcard/window.xml'])).iter('node'))

def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    remote(['shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)])
    time.sleep(2)

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('action', choices=['dump', 'tap', 'field', 'shot', 'locale', 'scroll', 'back'])
    p.add_argument('value', nargs='?', default='')
    p.add_argument('text', nargs='?', default='')
    a = p.parse_args()
    if a.action == 'dump':
        print([(n.get('text'), n.get('bounds')) for n in nodes() if n.get('text')])
    elif a.action == 'tap':
        tap(next(n for n in nodes() if n.get('text') == a.value and n.get('clickable') == 'true'))
    elif a.action == 'field':
        tap([n for n in nodes() if n.get('class') == 'android.widget.EditText'][int(a.value)])
        remote(['shell', 'input', 'keycombination', '113', '29'])
        remote(['shell', 'input', 'text', a.text])
        remote(['shell', 'input', 'keyevent', '4'])
    elif a.action == 'shot':
        time.sleep(1)
        folder = Path('logs/app2'); folder.mkdir(parents=True, exist_ok=True)
        (folder / (a.value + '.png')).write_bytes(remote(['exec-out', 'screencap', '-p'], True))
    elif a.action == 'locale':
        remote(['shell', 'cmd', 'locale', 'set-app-locales', 'jumpscan.app', '--locales', a.value])
        time.sleep(3)
    elif a.action == 'scroll':
        remote(['shell', 'input', 'swipe', '540', '1950', '540', '600', '600'])
        time.sleep(1)
    else:
        remote(['shell', 'input', 'keyevent', '4'])

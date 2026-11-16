#!/usr/bin/env python3
"""Run a reference clip on the dedicated build-kit emulator and save local evidence."""
import argparse
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = '/opt/android-sdk/platform-tools/adb'
def remote(command, binary=False):
    return subprocess.check_output(['ssh', 'android-build-kit', command], text=not binary, timeout=40)

def ui():
    remote(f'{ADB} shell rm -f /sdcard/window.xml')
    remote(f'timeout 15 {ADB} shell uiautomator dump /sdcard/window.xml >/dev/null')
    return remote(f'{ADB} shell cat /sdcard/window.xml')

def texts():
    return [(n.get('text'), n.get('bounds')) for n in ET.fromstring(ui()).iter('node') if n.get('text')]

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('clip', type=int, choices=[1, 2, 3])
    p.add_argument('--tag', default='full')
    p.add_argument('--mode', default='full')
    p.add_argument('--skip-export', action='store_true')
    p.add_argument('--resume', action='store_true')
    a = p.parse_args()
    out = Path('logs/app2'); out.mkdir(parents=True, exist_ok=True)
    if a.resume:
        expected = remote(f'sha256sum /work/jumpscan-samples/private/jump{a.clip}.mp4').split()[0]
        actual = remote(f'{ADB} shell run-as jumpscan.app sha256sum files/reference.mp4').split()[0]
        if actual != expected:
            raise RuntimeError('The emulator video does not match the requested reference clip')
    if not a.resume:
        remote(f'{ADB} push /work/jumpscan-samples/private/jump{a.clip}.mp4 /data/local/tmp/jumpscan-ref.mp4 >/dev/null')
        remote(f'{ADB} shell run-as jumpscan.app mkdir -p files')
        remote(f'{ADB} shell run-as jumpscan.app cp /data/local/tmp/jumpscan-ref.mp4 files/reference.mp4')
        remote(f'{ADB} logcat -c')
        extra = '' if a.mode == 'default' else f' --es analysis_mode {a.mode}'
        remote(f'{ADB} shell am start -n jumpscan.app/.MainActivity -a android.intent.action.VIEW -d file:///data/user/0/jumpscan.app/files/reference.mp4 -t video/mp4{extra}')
    started = time.monotonic()
    seen_analysis = False
    while time.monotonic() - started < 900:
        try:
            log = remote(f'{ADB} logcat -d -s JumpScan:D AndroidRuntime:E')
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError):
            continue
        seen_analysis |= 'Decoded ' in log or (a.mode == 'replay' and 'phase=replay' in log)
        if 'FATAL EXCEPTION' in log:
            raise RuntimeError(log)
        if seen_analysis and 'Analysis elapsedMs=' in log and 'Metrics ' in log:
            time.sleep(2)
            stem = out / f'{a.tag}-jump{a.clip}'
            stem.with_suffix('.log').write_text(log)
            try:
                screen = ui()
            except (subprocess.TimeoutExpired, subprocess.CalledProcessError):
                continue
            if 'Your result' not in screen and 'Вашият резултат' not in screen:
                continue
            stem.with_suffix('.xml').write_text(screen)
            stem.with_suffix('.json').write_bytes(remote(f'{ADB} shell run-as jumpscan.app cat files/metrics.json', True))
            stem.with_suffix('.png').write_bytes(remote(f'{ADB} exec-out screencap -p', True))
            if not a.skip_export: stem.with_suffix('.csv').write_bytes(remote(f'{ADB} shell run-as jumpscan.app cat files/landmarks.csv', True))
            print('\n'.join(line for line in log.splitlines() if 'elapsedMs=' in line or 'Metrics ' in line))
            print(f'Evidence saved: {stem}')
            break
        time.sleep(4)
    else:
        raise RuntimeError('Analysis did not finish within 900 seconds')

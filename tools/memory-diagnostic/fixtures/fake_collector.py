"""PC-only lifecycle fixture; no adb, device, photo, or network access."""
import json
from pathlib import Path
import sys
import time

root = Path(sys.argv[1])
duration = float(sys.argv[2])
stall = len(sys.argv) > 3 and sys.argv[3] == '--stall'
root.mkdir(parents=True, exist_ok=False)
started = time.monotonic()
with (root / 'observations.jsonl').open('w', encoding='utf-8') as output:
    def emit(kind, **fields):
        output.write(json.dumps(dict(host_epoch=time.time(), kind=kind, synthetic=True, **fields)) + '\n')
        output.flush()
    emit('COLLECTOR_START')
    while time.monotonic() - started < duration:
        if (root / 'stop.request').exists():
            break
        phase = ['DECODE', 'PREVIEW', 'SAVE', 'RELEASE'][int((time.monotonic() - started) / 60) % 4]
        if not stall:
            emit('FAKE_SAMPLE', stage=phase)
        time.sleep(min(.5, duration))
    emit('COLLECTOR_STOP')

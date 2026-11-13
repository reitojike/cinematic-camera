"""PC-only collector owner. Start as a hidden independent process, never restart automatically."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def write_status(root, status):
    temporary = root / 'status.new'
    temporary.write_text(json.dumps(dict(status, heartbeat_epoch=time.time())), encoding='utf-8')
    temporary.replace(root / 'status.json')


def health(root, now=None):
    """A missing/stale supervisor never grants permission to continue a measurement."""
    try:
        status = json.loads((root / 'status.json').read_text(encoding='utf-8'))
        age = (time.time() if now is None else now) - status['heartbeat_epoch']
        return status['state'] == 'RUNNING' and 0 <= age <= 5
    except (OSError, ValueError, KeyError, TypeError):
        return False


def supervise(command, root, *, poll_seconds=1, stale_seconds=30):
    """Own only this PC child; no device lifecycle or data actions. command is fixed by main."""
    root.mkdir(parents=True, exist_ok=False)
    started = time.time()
    status = {'state': 'STARTING', 'supervisor_pid': os.getpid(), 'started_epoch': started}
    write_status(root, status)
    child = None
    reason = 'CHILD_EXIT'
    try:
        # The collector normally emits no stdout. Capture errors locally without printing private arguments.
        with (root / 'child.stdout').open('wb') as out, (root / 'child.stderr').open('wb') as err:
            child = subprocess.Popen(command, stdout=out, stderr=err)
            status.update(state='RUNNING', child_pid=child.pid)
            while child.poll() is None:
                observations = root / 'collector' / 'observations.jsonl'
                latest = observations.stat().st_mtime if observations.exists() else started
                if (root / 'stop.request').exists() or time.time() - latest > stale_seconds:
                    reason = 'OPERATOR_STOP' if (root / 'stop.request').exists() else 'STALE_COLLECTOR'
                    status.update(state='STOPPING', reason=reason)
                    write_status(root, status)
                    directory = root / 'collector'
                    if directory.is_dir():
                        (directory / 'stop.request').write_text(reason, encoding='utf-8')
                    try:
                        child.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        child.terminate()  # Only the PC collector child; never adb/device/app.
                        child.wait(timeout=5)
                    break
                status['observation_age_seconds'] = time.time() - latest
                write_status(root, status)
                time.sleep(poll_seconds)
            status['exit_code'] = child.returncode
        # Exit zero alone cannot prove a complete collector shutdown.
        try:
            lines = (root / 'collector' / 'observations.jsonl').read_text(encoding='utf-8').splitlines()
            events = [json.loads(line) for line in lines]
            last = events[-1] if events else {}
            complete = last.get('kind') == 'COLLECTOR_STOP'
            failed = any(event.get('kind', '').startswith('STOP_') for event in events)
        except (OSError, ValueError):
            complete = False
            failed = True
        status.update(state='STOPPED' if child.returncode == 0 and complete and not failed and reason != 'STALE_COLLECTOR' else 'FAILED',
                      reason=reason, collector_shutdown_recorded=complete)
    except Exception as failure:
        status.update(state='FAILED', reason='SUPERVISOR_EXCEPTION', exception=type(failure).__name__)
    finally:
        if child is not None and child.poll() is None:
            child.terminate()
            child.wait(timeout=5)
        write_status(root, status)
    return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=3600)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 3600:
        parser.error('seconds must be 1..3600')
    collector = Path(__file__).with_name('collector.py').resolve()
    command = [sys.executable, '-B', str(collector), '--adb', args.adb, '--serial', args.serial,
               '--out', str(args.out.resolve() / 'collector'), '--seconds', str(args.seconds)]
    status = supervise(command, args.out.resolve())
    return 0 if status['state'] == 'STOPPED' else 1


if __name__ == '__main__':
    sys.exit(main())

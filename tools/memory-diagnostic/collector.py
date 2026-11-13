"""Local-only measurement recorder. Never installs, launches, kills, or edits device data."""
import argparse
import json
import pathlib
import re
import subprocess
import threading
import time

PACKAGE = 'dev.cinematic.p0.memorydiagnostic'
KINDS = {'MEMORY_ADMISSION', 'GPU_TEXTURE_LEDGER', 'PROCESS_START', 'OWN_PROCESS_EXIT',
         'ORIGINAL_MEMORY_REJECTED', 'MEASUREMENT_CONFIGURATION'}
KEYS = {'mode', 'stage', 'elapsedMs', 'epochMs', 'pid', 'availMemBytes', 'lowMemory',
        'thresholdBytes', 'requiredAvailableBytes', 'wouldPermit', 'reason', 'outputPixels',
        'estimatedAdditionalBytes', 'rgb16PayloadBytes', 'budgetBytes', 'reservedBytes',
        'peakReservedBytes', 'textures', 'status', 'pssKiB', 'rssKiB', 'exception', 'engine', 'recordOnly'}
STAGES = {'ROLL_PREVIEW', 'ROLL_SAVE', 'ORIGINAL_DECODE', 'BOUNDED_PREVIEW', 'ORIGINAL_SAVE'}


def parse_package_uid(text):
    matches = re.findall(r'^package:' + re.escape(PACKAGE) + r' uid:(\d+)\s*$', text, re.M)
    if len(matches) != 1:
        raise ValueError('Diagnostic package identity unavailable')
    return matches[0]


def parse_log(line, own_pids):
    prefix = re.match(r'\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+[VDIWEF]\s+(\S+?)\s*:\s*(.*)', line)
    if not prefix:
        return None
    stamp, pid, tag, message = prefix.groups()
    if pid in own_pids and tag == 'AndroidRuntime' and 'FATAL EXCEPTION' in message:
        return {'kind': 'STOP_OWN_CRASH', 'device_epoch': stamp, 'cause': 'unknown'}
    if tag == 'lmkd' and re.search(r'\bKill(?:ing)?\b', message):
        target = re.search(r"\bKill(?:ing)?\s+['\"]([^'\"]+)['\"]", message)
        return {'kind': 'OS_KILL_EVENT', 'device_epoch': stamp,
                'target': 'own' if target and target[1] == PACKAGE else 'other_or_unidentified',
                'evidence': 'lmkd_kill_message', 'reason_detail': 'not_collected'}
    if pid not in own_pids or tag not in {'CinematicMemory', 'CinematicRaw'}:
        return None
    kind, _, tail = message.partition(' ')
    if kind not in KINDS:
        return None
    fields = {key: value for key, value in re.findall(r'([A-Za-z][A-Za-z0-9]*)=([A-Za-z0-9_.-]+)', tail)
              if key in KEYS}
    if 'stage' in fields and fields['stage'] not in STAGES:
        return {'kind': 'STOP_BAD_STAGE', 'device_epoch': stamp}
    return {'kind': kind, 'device_epoch': stamp, 'process_pid': pid, 'fields': fields}


def parse_memory(text, expected_pid):
    header = re.search(r'MEMINFO in pid (\d+) \[([^\]]+)\]', text)
    if not header or header[1] != expected_pid or header[2] != PACKAGE:
        raise ValueError('Process memory identity mismatch')
    fields = {}
    for name in ['Java Heap', 'Native Heap', 'Code', 'Stack', 'Graphics', 'Private Other', 'System', 'Unknown']:
        match = re.search(r'^\s*' + re.escape(name) + r':\s+(\d+)', text, re.M)
        if match:
            fields[name.replace(' ', '_') + '_KiB'] = int(match[1])
    for name in ['TOTAL PSS', 'TOTAL RSS', 'TOTAL SWAP PSS']:
        match = re.search(re.escape(name) + r':\s+(\d+)', text)
        if match:
            fields[name.replace(' ', '_') + '_KiB'] = int(match[1])
    if 'TOTAL_PSS_KiB' not in fields:
        raise ValueError('Missing process memory totals')
    return fields


def stop_reason(event, prior_pid=None):
    kind = event['kind']
    fields = event.get('fields', {})
    if kind.startswith('STOP_'):
        return kind
    if kind == 'MEASUREMENT_CONFIGURATION' and (fields.get('engine') != 'GPU' or fields.get('recordOnly') != 'true'):
        return 'STOP_WRONG_CONFIGURATION'
    if kind == 'THERMAL' and event.get('status') is not None and event['status'] >= 2:
        return 'STOP_THERMAL'
    if kind == 'PID' and prior_pid and event.get('pid') != prior_pid:
        return 'STOP_PROCESS_CHANGED_OR_DISAPPEARED'
    if kind == 'ORIGINAL_MEMORY_REJECTED':
        return 'STOP_UNEXPECTED_ADMISSION_REJECTION'
    if kind == 'OS_KILL_EVENT' and event.get('target') == 'own':
        return 'STOP_OWN_OS_KILL'
    return None


def preflight_evidence(events):
    """Require actual selected engine, decoded input, positive GPU reservation and process sampling."""
    boundaries = [i for i, event in enumerate(events) if event['kind'] == 'COLLECTOR_START']
    session = events[boundaries[-1]+1:] if boundaries else []
    starts = [event for event in session if event['kind'] == 'PROCESS_START']
    start = starts[0] if len(starts) == 1 else None
    pid = start.get('process_pid') if start else None
    same = []
    if start and pid and start.get('fields', {}).get('pid') == pid:
        for event in session:
            if event.get('process_pid') != pid or event.get('host_epoch', 0) < start.get('host_epoch', 0):
                continue
            if event['kind'] != 'PROCESS_MEMORY_SAMPLE' and float(event.get('device_epoch', 0)) < float(start.get('device_epoch', 0)):
                continue
            same.append(event)
    configured = any(e['kind'] == 'MEASUREMENT_CONFIGURATION' and
        e.get('fields', {}).get('engine') == 'GPU' and e['fields'].get('recordOnly') == 'true' for e in same)
    gpu = any(e['kind'] == 'GPU_TEXTURE_LEDGER' and int(e.get('fields', {}).get('reservedBytes', '0')) > 0 for e in same)
    decoded = any(e['kind'] == 'MEMORY_ADMISSION' and e.get('fields', {}).get('stage') == 'ORIGINAL_DECODE' for e in same)
    sampled = any(e['kind'] == 'PROCESS_MEMORY_SAMPLE' for e in same)
    stopped = any(stop_reason(e) or e['kind'] == 'COLLECTOR_STOP' for e in session) or len(starts) > 1
    complete = configured and gpu and decoded and sampled
    return {'configured_gpu': configured, 'gpu_reserved': gpu, 'decode_seen': decoded,
            'sampled': sampled, 'stopped': stopped, 'evidence_complete': complete,
            'ready_for_operator_next_step': complete and not stopped}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--out', type=pathlib.Path, required=True)
    parser.add_argument('--seconds', type=int, default=3600)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 3600:
        parser.error('seconds must be 1..3600')
    args.out.mkdir(parents=True, exist_ok=False)  # Never append to or overwrite another session.
    output = args.out / 'observations.jsonl'
    stop_file = args.out / 'stop.request'
    lock = threading.Lock()
    stop = threading.Event()
    own_pids = set()
    started = time.time()

    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True,
            text=True, encoding='utf-8', errors='replace', timeout=12)

    def write(event):
        with lock, output.open('a', encoding='utf-8') as file:
            file.write(json.dumps(dict(host_epoch=time.time(), **event)) + '\n')
        reason = stop_reason(event)
        if reason:
            stop_file.write_text(reason, encoding='utf-8')
            stop.set()

    # Learn this installation's UID before logcat so Application startup is observed before pidof catches up.
    package = adb('shell', 'pm', 'list', 'packages', '-U', '--user', 'current', PACKAGE)
    if package.returncode:
        raise RuntimeError('Diagnostic package identity unavailable')
    uid = parse_package_uid(package.stdout)
    device_clock = adb('shell', 'date', '+%s.%N')
    device_started = float(device_clock.stdout.strip())
    if device_clock.returncode:
        raise RuntimeError('Device clock unavailable')
    logs = subprocess.Popen([args.adb, '-s', args.serial, 'logcat', '-v', 'epoch', '-T', '1',
        'CinematicMemory:I', 'CinematicRaw:W', 'AndroidRuntime:E', 'lmkd:I', '*:S'], stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, text=True, encoding='utf-8', errors='replace')

    def read_logs():
        try:
            for line in logs.stdout:
                if stop.is_set():
                    break
                # Confirm startup PID belongs to our package instead of trusting a tag alone.
                if 'CinematicMemory' in line and 'PROCESS_START ' in line:
                    found = re.search(r'\bpid=(\d+)', line)
                    if found:
                        result = adb('shell', 'cat', '/proc/' + found[1] + '/status')
                        if re.search(r'^Uid:\s+' + uid + r'\s', result.stdout, re.M):
                            own_pids.add(found[1])
                event = parse_log(line, own_pids)
                if event and float(event['device_epoch']) >= device_started:
                    write(event)
            if not stop.is_set():
                write({'kind': 'STOP_LOG_STREAM_ENDED'})
        except Exception as failure:
            write({'kind': 'STOP_LOG_READER', 'exception': type(failure).__name__})

    thread = threading.Thread(target=read_logs, daemon=True)
    write({'kind': 'COLLECTOR_START', 'sample_target_seconds': 1, 'max_duration_seconds': args.seconds,
           'device_started_epoch': device_started})
    thread.start()
    prior_pid = None
    iteration = 0
    try:
        while not stop.is_set() and not stop_file.exists() and time.time() - started < args.seconds:
            tick = time.monotonic()
            result = adb('shell', 'pidof', PACKAGE)
            pid = result.stdout.strip()
            if pid and not re.fullmatch(r'\d+', pid):
                raise ValueError('Unexpected process identity')
            reason = stop_reason({'kind': 'PID', 'pid': pid}, prior_pid)
            if reason:
                write({'kind': reason, 'cause': 'unknown'})
                break
            if pid:
                own_pids.add(pid)
                prior_pid = pid
                memory = adb('shell', 'dumpsys', 'meminfo', pid)
                if memory.returncode:
                    raise RuntimeError('Memory read failed')
                write({'kind': 'PROCESS_MEMORY_SAMPLE', 'process_pid': pid, 'fields': parse_memory(memory.stdout, pid),
                       'command_seconds': round(time.monotonic() - tick, 3)})
            if iteration % 10 == 0:
                thermal = adb('shell', 'dumpsys', 'thermalservice')
                match = re.search(r'Thermal Status:\s*(\d+)', thermal.stdout)
                if thermal.returncode or not match:
                    raise RuntimeError('Thermal observation unavailable')
                write({'kind': 'THERMAL', 'status': int(match[1])})
            iteration += 1
            stop.wait(max(0, 1 - (time.monotonic() - tick)))
    except Exception as failure:
        write({'kind': 'STOP_SAMPLER_FAILURE', 'exception': type(failure).__name__})
    finally:
        stop.set()
        try:
            if logs.poll() is None:
                logs.terminate()
            logs.wait(timeout=5)
        except Exception as failure:
            write({'kind': 'STOP_RECORDER_CLEANUP', 'exception': type(failure).__name__})
        thread.join(timeout=5)
        if thread.is_alive():
            write({'kind': 'STOP_RECORDER_CLEANUP', 'exception': 'ReaderStillAlive'})
        write({'kind': 'COLLECTOR_STOP', 'stop_requested': stop_file.exists()})


if __name__ == '__main__':
    main()

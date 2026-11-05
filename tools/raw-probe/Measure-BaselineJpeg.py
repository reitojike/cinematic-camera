"""One opt-in AVD measurement, local files only. No install, upload, physical device or retry."""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid


def host_memory():
    if sys.platform != 'win32':
        raise RuntimeError('HOST_GUARD_REQUIRES_WINDOWS')
    class Memory(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ulong), ('load', ctypes.c_ulong)] + [(x, ctypes.c_ulonglong) for x in
            ('totalPhysical', 'availablePhysical', 'totalCommit', 'availableCommit', 'totalVirtual', 'availableVirtual', 'extended')]
    state = Memory()
    state.length = ctypes.sizeof(state)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(state)):
        raise RuntimeError('HOST_MEMORY_UNAVAILABLE')
    return {'availablePhysicalBytes': state.availablePhysical, 'availableCommitBytes': state.availableCommit}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--mp', type=int, choices=[12, 24], required=True)
    parser.add_argument('--case', choices=['success', 'write', 'close', 'cancel'], default='success')
    parser.add_argument('--flavor', choices=['normal', 'largeHeap'], default='normal')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('Only explicit emulator serials are allowed')
    args.output.mkdir(parents=True, exist_ok=True)
    run_id = uuid.uuid4().hex
    stem = f'{args.mp}mp-{args.flavor}-{args.case}-{run_id}'
    record_path = args.output / f'{stem}-run.json'
    record = {'runId': run_id, 'mp': args.mp, 'case': args.case, 'flavor': args.flavor, 'status': 'STARTED',
              'scope': 'DIAGNOSTIC_BASELINE_NO_R7_NO_STAGE_CLEAR', 'instrumentationStarted': False}
    package = 'dev.cinematic.rawprobe' + ('.largeheap' if args.flavor == 'largeHeap' else '')
    process = None

    def adb(*parameters, timeout=30):
        return subprocess.check_output([args.adb, '-s', args.serial, *parameters], timeout=timeout)

    def save():
        record_path.write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')

    save()
    try:
        if adb('emu', 'avd', 'name').decode().splitlines()[0].strip() != args.avd:
            raise RuntimeError('WRONG_AVD')
        memory = host_memory()
        record['hostBefore'] = memory
        if memory['availableCommitBytes'] < 2 * 1024**3 or memory['availablePhysicalBytes'] < 4 * 1024**3:
            raise RuntimeError('HOST_RESERVE_BEFORE_RUN')
        log_path = args.output / f'{stem}.txt'
        record['instrumentationLog'] = log_path.name
        save()
        with log_path.open('xb') as log:
            process = subprocess.Popen([args.adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w',
                '-e', 'rawProbe', 'true', '-e', 'runId', run_id, '-e', 'megapixels', str(args.mp), '-e', 'case', args.case,
                '-e', 'class', 'dev.cinematic.rawprobe.LargeBaselineMeasureTest',
                package + '.test/androidx.test.runner.AndroidJUnitRunner'], stdout=log, stderr=subprocess.STDOUT)
            record['instrumentationStarted'] = True
            save()
            start = time.monotonic()
            minimum = dict(memory)
            while process.poll() is None:
                current = host_memory()
                minimum = {k: min(v, current[k]) for k, v in minimum.items()}
                if current['availableCommitBytes'] < 1024**3 or time.monotonic() - start > 600:
                    raise RuntimeError('RESOURCE_OR_TIMEOUT_ABORT_UNKNOWN')
                time.sleep(1)
            record['hostObservedMinimum'] = minimum
            record['adbExit'] = process.returncode
        log = log_path.read_text(encoding='utf-8', errors='replace')
        data = adb('exec-out', 'run-as', package, 'cat', f'files/baseline-{run_id}.json')
        report_path = args.output / f'{stem}.json'
        report_path.write_bytes(data)  # Preserve even incomplete or failed output for diagnosis.
        report = json.loads(data)
        record['reportFile'] = report_path.name
        record['reportSha256'] = hashlib.sha256(data).hexdigest()
        valid = report.get('runId') == run_id and report.get('complete') is True and report.get('megapixels') == args.mp and \
            report.get('case') == args.case and report.get('package') == package and run_id in log and \
            'OK (1 test)' in log and process.returncode == 0
        if not valid:
            raise RuntimeError('CURRENT_RUN_NOT_VERIFIED')
        record['status'] = 'VERIFIED_CURRENT_RUN'
        save()
        print(json.dumps({'runId': run_id, 'mp': args.mp, 'flavor': args.flavor, 'case': args.case,
            'status': report['status'], 'elapsedMs': report['elapsedMs'],
            'sampledManagedMiB': round(report['sampledManagedMaxBytes'] / 1024**2, 2),
            'sampledNativeMiB': round(report['sampledNativeMaxBytes'] / 1024**2, 2),
            'sampledPssMiB': round(report['sampledPssMaxKiB'] / 1024, 2),
            'width': report.get('output', {}).get('width'), 'height': report.get('output', {}).get('height'),
            'sourceUnchanged': report['sourceUnchanged'], 'released': report['releasedAfterExplicitClose']}))
    except BaseException as error:
        record['status'] = 'UNKNOWN_OR_FAILED_CURRENT_RUN' if process is not None else 'BLOCKED_BEFORE_INSTRUMENTATION'
        record['errorType'] = type(error).__name__
        record['reason'] = str(error) if isinstance(error, RuntimeError) else 'SEE_LOCAL_LOG'
        if process is not None:  # Never stop a pre-existing app if this run was not actually started.
            cleanup = record['abnormalCleanup'] = {'remoteStoppedObserved': False}
            try:
                if adb('emu', 'avd', 'name', timeout=10).decode().splitlines()[0].strip() != args.avd:
                    raise RuntimeError('AVD_CHANGED_DURING_CLEANUP')
                adb('shell', 'am', 'force-stop', package, timeout=10)
                cleanup['forceStopReturned'] = True
                observed = subprocess.run([args.adb, '-s', args.serial, 'shell', 'pidof', package],
                                          capture_output=True, timeout=10)
                cleanup['remoteStoppedObserved'] = observed.returncode in (0, 1) and not observed.stdout.strip()
            except BaseException as stop_error:
                cleanup['errorType'] = type(stop_error).__name__
            finally:
                try:
                    if process.poll() is None:
                        process.terminate()
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    try: process.wait(timeout=5)
                    except subprocess.TimeoutExpired: cleanup['localAdbExitUnknown'] = True
            # Observed process exit is not an attempt's close acknowledgement or a successful receipt.
        save()
        raise


if __name__ == '__main__':
    main()

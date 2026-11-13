"""Fail-closed result for the single opt-in three-cycle emulator rehearsal."""
import re
from supervisor import pss_gap


def overall_passed(result):
    return (result.get('aggregate', {}).get('passed') is True and result.get('failure') is None
            and not result.get('cleanup_failures') and not result.get('owned_stop_unconfirmed')
            and not result.get('runner_still_active') and not result.get('supervisor_still_active'))


def evaluate(runner_exit, transcript, monitor, events):
    failures = []
    if runner_exit != 0:
        failures.append('ADB_RUNNER_EXIT')
    if not re.search(r'^OK \(1 test\)\s*$', transcript, re.M) or re.search(
            r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[234]', transcript):
        failures.append('INSTRUMENTATION_NOT_PASS')
    cycles = re.findall(r'MEMORY_REHEARSAL cycle=(\d+) phase=(BEGIN|RELEASED)', transcript)
    if cycles != [(str(i), phase) for i in (1, 2, 3) for phase in ('BEGIN', 'RELEASED')]:
        failures.append('CYCLES_INCOMPLETE')
    if (monitor.get('state') != 'STOPPED' or monitor.get('exit_code') != 0 or
            monitor.get('collector_shutdown_recorded') is not True):
        failures.append('MONITOR_NOT_PASS')
    if not events or events[0].get('kind') != 'COLLECTOR_START' or events[-1].get('kind') != 'COLLECTOR_STOP':
        failures.append('COLLECTOR_BOUNDARY_MISSING')
    else:
        if events[-1].get('stop_requested') is not False or any(e.get('kind', '').startswith('STOP_') for e in events):
            failures.append('COLLECTOR_STOP_REQUESTED_OR_FAILED')
        if events[-1]['host_epoch'] - events[0]['host_epoch'] < 1200:
            failures.append('DURATION_SHORT')
        if pss_gap(events, events[-1]['host_epoch']):
            failures.append('PSS_GAP')
    starts = [e for e in events if e.get('kind') == 'PROCESS_START']
    samples = [e for e in events if e.get('kind') == 'PROCESS_MEMORY_SAMPLE']
    if len(starts) != 1 or not samples or any(e.get('process_pid') != starts[0].get('process_pid') for e in samples):
        failures.append('PROCESS_SAMPLES_INCOMPLETE')
    return {'passed': not failures, 'failures': failures}

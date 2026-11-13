import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from check_session import check
from supervisor import health, supervise, pss_gap


class SupervisorTest(unittest.TestCase):
    def test_pss_gap_remains_failure_after_samples_resume_and_other_logs_continue(self):
        start = {'kind': 'PROCESS_START', 'host_epoch': 100, 'process_pid': '11'}
        sample = lambda t: {'kind': 'PROCESS_MEMORY_SAMPLE', 'host_epoch': t, 'process_pid': '11'}
        self.assertFalse(pss_gap([start, sample(110)], 120))
        self.assertTrue(pss_gap([start, sample(110), sample(126)], 126))
        self.assertTrue(pss_gap([start, sample(110), {'kind': 'MEMORY_ADMISSION', 'host_epoch': 126}], 126))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'session'
            script = Path(__file__).with_name('fixtures') / 'fake_collector.py'
            status = supervise([sys.executable, '-B', str(script), str(root / 'collector'), '5', '--pss-gap'],
                               root, poll_seconds=.01)
            self.assertEqual('FAILED', status['state'])
            self.assertEqual('STALE_PSS', status['reason'])
            self.assertTrue(status['collector_shutdown_recorded'])
            self.assertFalse(check(root)['ready_for_operator_next_step'])

    def test_combined_gate_rejects_missing_supervisor_and_either_stop_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'collector').mkdir()
            now = time.time()
            events = [{'kind': 'COLLECTOR_START'}]
            for kind, fields in [('PROCESS_START', {'pid': '11'}),
                                 ('MEASUREMENT_CONFIGURATION', {'engine': 'GPU', 'recordOnly': 'true'}),
                                 ('MEMORY_ADMISSION', {'stage': 'ORIGINAL_DECODE'}),
                                 ('GPU_TEXTURE_LEDGER', {'reservedBytes': '64'}),
                                 ('PROCESS_MEMORY_SAMPLE', {})]:
                events.append(dict(kind=kind, fields=fields, process_pid='11', host_epoch=now, device_epoch='1000'))
            (root / 'collector' / 'observations.jsonl').write_text('\n'.join(json.dumps(e) for e in events))
            self.assertFalse(check(root)['ready_for_operator_next_step'])
            (root / 'status.json').write_text(json.dumps({'state': 'RUNNING', 'heartbeat_epoch': now}))
            self.assertTrue(check(root)['ready_for_operator_next_step'])
            with_gap = [dict(event) for event in events]
            with_gap[1]['host_epoch'] = now - 17
            (root / 'collector' / 'observations.jsonl').write_text('\n'.join(json.dumps(e) for e in with_gap))
            self.assertFalse(check(root)['ready_for_operator_next_step'])
            (root / 'collector' / 'observations.jsonl').write_text('\n'.join(json.dumps(e) for e in events))
            for stop in [root / 'stop.request', root / 'collector' / 'stop.request']:
                stop.touch()
                self.assertFalse(check(root)['ready_for_operator_next_step'])
                stop.unlink()
            (root / 'status.json').write_text(json.dumps({'state': 'RUNNING', 'heartbeat_epoch': now - 6}))
            self.assertFalse(check(root)['ready_for_operator_next_step'])

    def test_normal_completion_never_remains_ready(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'session'
            script = Path(__file__).with_name('fixtures') / 'fake_collector.py'
            status = supervise([sys.executable, '-B', str(script), str(root / 'collector'), '.05'], root,
                               poll_seconds=.01)
            self.assertEqual('STOPPED', status['state'])
            self.assertTrue(status['collector_shutdown_recorded'])
            self.assertFalse(health(root))

    def test_force_terminated_child_and_missing_shutdown_fail(self):
        for script in ['import os,signal; os.kill(os.getpid(),signal.SIGTERM)', 'pass']:
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / 'session'
                status = supervise([sys.executable, '-c', script], root, poll_seconds=.01)
                self.assertEqual('FAILED', status['state'])
                self.assertFalse(status['collector_shutdown_recorded'])
                self.assertFalse(health(root))

    def test_stale_source_stops_only_owned_pc_child_without_restart(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'session'
            status = supervise([sys.executable, '-c', 'import time; time.sleep(.15)'], root,
                               poll_seconds=.01, stale_seconds=.03)
            self.assertEqual('FAILED', status['state'])
            self.assertEqual('STALE_COLLECTOR', status['reason'])

    def test_stale_failure_is_preserved_after_successful_child_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'session'
            script = Path(__file__).with_name('fixtures') / 'fake_collector.py'
            status = supervise([sys.executable, '-B', str(script), str(root / 'collector'), '5', '--stall'],
                               root, poll_seconds=.01, stale_seconds=.1)
            self.assertEqual(0, status['exit_code'])
            self.assertTrue(status['collector_shutdown_recorded'])
            self.assertEqual('FAILED', status['state'])
            self.assertEqual('STALE_COLLECTOR', status['reason'])
            self.assertFalse(health(root))

    def test_missing_stale_future_or_terminal_supervisor_never_allows_next_step(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertFalse(health(root))
            for state, stamp, expected in [('RUNNING', 100, True), ('RUNNING', 94, False),
                                            ('RUNNING', 101, False), ('STOPPED', 100, False),
                                            ('FAILED', 100, False)]:
                (root / 'status.json').write_text(json.dumps({'state': state, 'heartbeat_epoch': stamp}))
                self.assertEqual(expected, health(root, now=100))


if __name__ == '__main__':
    unittest.main()

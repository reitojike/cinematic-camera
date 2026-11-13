import unittest
from rehearsal_result import evaluate, overall_passed


class RehearsalResultTest(unittest.TestCase):
    def setUp(self):
        self.transcript = '\n'.join(f'MEMORY_REHEARSAL cycle={i} phase={p}'
            for i in (1, 2, 3) for p in ('BEGIN', 'RELEASED')) + '\nOK (1 test)\n'
        self.monitor = dict(state='STOPPED', exit_code=0, collector_shutdown_recorded=True)
        self.events = [dict(kind='COLLECTOR_START', host_epoch=0),
                       dict(kind='PROCESS_START', host_epoch=0, process_pid='11')]
        self.events += [dict(kind='PROCESS_MEMORY_SAMPLE', host_epoch=i, process_pid='11') for i in range(1201)]
        self.events += [dict(kind='COLLECTOR_STOP', host_epoch=1201, stop_requested=False)]

    def test_complete_result_passes(self):
        self.assertTrue(evaluate(0, self.transcript, self.monitor, self.events)['passed'])

    def test_driver_failure_or_unconfirmed_cleanup_overrides_complete_evidence(self):
        result = dict(aggregate={'passed': True}, failure=None, cleanup_failures=[])
        self.assertTrue(overall_passed(result))
        for key, value in [('failure', 'ERROR'), ('cleanup_failures', ['ERROR']),
                ('owned_stop_unconfirmed', True), ('runner_still_active', True), ('supervisor_still_active', True)]:
            self.assertFalse(overall_passed(dict(result, **{key: value})))
        self.assertFalse(overall_passed({}))

    def test_adb_exit_zero_does_not_hide_assertion_or_skip(self):
        for text in ['FAILURES!!!\nTests run: 1, Failures: 1', self.transcript + '\nINSTRUMENTATION_STATUS_CODE: -3',
                     self.transcript.replace('OK (1 test)', 'OK (0 tests)')]:
            self.assertIn('INSTRUMENTATION_NOT_PASS', evaluate(0, text, self.monitor, self.events)['failures'])

    def test_all_three_released_cycles_required_without_duplicates(self):
        for text in [self.transcript.replace('cycle=2 phase=RELEASED', 'cycle=2 phase=BEGIN'),
                     self.transcript + '\nMEMORY_REHEARSAL cycle=3 phase=RELEASED']:
            self.assertIn('CYCLES_INCOMPLETE', evaluate(0, text, self.monitor, self.events)['failures'])

    def test_monitor_failure_and_incomplete_measurement_are_not_success(self):
        bad_monitor = dict(self.monitor, state='FAILED')
        self.assertIn('MONITOR_NOT_PASS', evaluate(0, self.transcript, bad_monitor, self.events)['failures'])
        for events in [[], self.events[:-1], self.events[:50] + [dict(kind='COLLECTOR_STOP', host_epoch=50, stop_requested=True)],
                       self.events[:20] + self.events[60:]]:
            self.assertFalse(evaluate(0, self.transcript, self.monitor, events)['passed'])

if __name__ == '__main__':
    unittest.main()

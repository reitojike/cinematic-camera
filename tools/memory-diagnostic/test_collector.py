import json
import unittest
from collector import parse_log, parse_memory, parse_package_uid, stop_reason, preflight_evidence


def log(message, pid='11', tag='CinematicMemory'):
    return f'1000.123 {pid} 12 I {tag}: {message}'


class CollectorTest(unittest.TestCase):
    def test_package_uid_matches_exact_current_user_package_not_test_package(self):
        target = 'package:dev.cinematic.p0.memorydiagnostic uid:110240\n'
        test = 'package:dev.cinematic.p0.memorydiagnostic.test uid:110241\n'
        self.assertEqual('110240', parse_package_uid(test + target))
        for text in ['', test, target + target, 'appId=10240', 'userId=10240']:
            with self.assertRaises(ValueError):
                parse_package_uid(text)

    def test_fixed_fields_and_own_pid_only(self):
        line = log('MEMORY_ADMISSION stage=ORIGINAL_DECODE outputPixels=123 mode=RECORD_ONLY uri=private photo=secret')
        event = parse_log(line, {'11'})
        self.assertEqual(event['fields'], {'stage': 'ORIGINAL_DECODE', 'outputPixels': '123', 'mode': 'RECORD_ONLY'})
        self.assertNotIn('secret', json.dumps(event))
        self.assertIsNone(parse_log(line, {'22'}))
        self.assertIsNone(parse_log(log('unrelated private-content'), {'11'}))
        self.assertEqual('STOP_BAD_STAGE', parse_log(log('MEMORY_ADMISSION stage=unexpected'), {'11'})['kind'])

    def test_configuration_stops_cpu_and_requires_gpu(self):
        for engine, expected in [('CPU', 'STOP_WRONG_CONFIGURATION'), ('GPU', None)]:
            self.assertEqual(expected, stop_reason(parse_log(log(
                f'MEASUREMENT_CONFIGURATION engine={engine} recordOnly=true'), {'11'})))
        self.assertEqual('STOP_WRONG_CONFIGURATION', stop_reason(parse_log(log(
            'MEASUREMENT_CONFIGURATION engine=GPU recordOnly=false'), {'11'})))

    def test_record_only_would_reject_is_observation_not_failure(self):
        event = parse_log(log('MEMORY_ADMISSION stage=ORIGINAL_SAVE wouldPermit=false reason=OS_LOW_MEMORY'), {'11'})
        self.assertIsNone(stop_reason(event))
        event = parse_log(log('ORIGINAL_MEMORY_REJECTED stage=ORIGINAL_SAVE', tag='CinematicRaw'), {'11'})
        self.assertEqual('STOP_UNEXPECTED_ADMISSION_REJECTION', stop_reason(event))

    def test_process_and_thermal_stop_without_causing_device_actions(self):
        self.assertIsNone(stop_reason({'kind': 'PID', 'pid': ''}, None))
        self.assertIsNone(stop_reason({'kind': 'PID', 'pid': '11'}, '11'))
        for pid in ['', '22']:
            self.assertEqual('STOP_PROCESS_CHANGED_OR_DISAPPEARED', stop_reason({'kind': 'PID', 'pid': pid}, '11'))
        self.assertIsNone(stop_reason({'kind': 'THERMAL', 'status': 1}))
        self.assertEqual('STOP_THERMAL', stop_reason({'kind': 'THERMAL', 'status': 2}))

    def test_os_kills_and_crashes_do_not_disclose_other_app_data(self):
        event = parse_log(log("Kill 'private.other.app' (123): private reason", pid='99', tag='lmkd'), {'11'})
        self.assertNotIn('private', json.dumps(event))
        self.assertEqual('other_or_unidentified', event['target'])
        self.assertIsNone(stop_reason(event))
        own = parse_log(log("Kill 'dev.cinematic.p0.memorydiagnostic' (11)", pid='99', tag='lmkd'), {'11'})
        self.assertEqual('STOP_OWN_OS_KILL', stop_reason(own))
        other = parse_log(log("Kill 'dev.cinematic.p0.memorydiagnostic.other' (22)", pid='99', tag='lmkd'), {'11'})
        self.assertEqual('other_or_unidentified', other['target'])
        crash = parse_log(log('FATAL EXCEPTION: private-content', tag='AndroidRuntime'), {'11'})
        self.assertEqual('STOP_OWN_CRASH', stop_reason(crash))
        self.assertNotIn('private', json.dumps(crash))
        self.assertIsNone(parse_log(log('FATAL EXCEPTION: other', pid='99', tag='AndroidRuntime'), {'11'}))

    def test_memory_requires_actual_totals_and_does_not_add_components(self):
        self.assertEqual({'Graphics_KiB': 10, 'TOTAL_PSS_KiB': 100, 'TOTAL_RSS_KiB': 200},
            parse_memory('** MEMINFO in pid 11 [dev.cinematic.p0.memorydiagnostic] **\n Graphics: 10\n TOTAL PSS: 100 TOTAL RSS: 200', '11'))
        with self.assertRaises(ValueError):
            parse_memory('permission denied', '11')
        with self.assertRaises(ValueError):
            parse_memory('** MEMINFO in pid 22 [dev.cinematic.p0.memorydiagnostic] **\n TOTAL PSS: 100', '11')
        with self.assertRaises(ValueError):
            parse_memory('** MEMINFO in pid 11 [another.app] **\n TOTAL PSS: 100', '11')

    def test_preflight_rejects_old_cpu_or_incomplete_evidence(self):
        events = [{'kind': 'COLLECTOR_START'}, parse_log(log('PROCESS_START pid=11'), {'11'}),
                  parse_log(log('MEASUREMENT_CONFIGURATION engine=GPU recordOnly=true'), {'11'}),
                  parse_log(log('MEMORY_ADMISSION stage=ORIGINAL_DECODE'), {'11'}),
                  parse_log(log('GPU_TEXTURE_LEDGER reservedBytes=64'), {'11'}),
                  {'kind': 'PROCESS_MEMORY_SAMPLE', 'process_pid': '11'}]
        self.assertTrue(preflight_evidence(events)['ready_for_operator_next_step'])
        for index in range(6):
            self.assertFalse(preflight_evidence(events[:index] + events[index+1:])['ready_for_operator_next_step'])
        self.assertFalse(preflight_evidence(events + [{'kind': 'COLLECTOR_STOP'}])['ready_for_operator_next_step'])
        self.assertFalse(preflight_evidence(events + [{'kind': 'STOP_SAMPLER_FAILURE'}])['ready_for_operator_next_step'])
        for index in range(2, 6):
            mixed = [dict(e) for e in events]
            mixed[index]['process_pid'] = '22'
            self.assertFalse(preflight_evidence(mixed)['ready_for_operator_next_step'])
        stale = [dict(e) for e in events]
        stale[3]['device_epoch'] = '999.0'
        self.assertFalse(preflight_evidence(stale)['ready_for_operator_next_step'])
        self.assertFalse(preflight_evidence(events + [parse_log(log('PROCESS_START pid=11'), {'11'})])['ready_for_operator_next_step'])
        self.assertFalse(preflight_evidence(events + [{'kind': 'COLLECTOR_START'}])['ready_for_operator_next_step'])


if __name__ == '__main__':
    unittest.main()

"""Read PC evidence only. Any incomplete/stale observation forbids the next operator step."""
import json
from pathlib import Path
import sys
from collector import preflight_evidence
from supervisor import health


def check(root):
    supervisor_alive = health(root)
    try:
        events = [json.loads(line) for line in (root / 'collector' / 'observations.jsonl')
                  .read_text(encoding='utf-8').splitlines()]
        result = preflight_evidence(events)
    except (OSError, ValueError, KeyError, TypeError):
        result = {'ready_for_operator_next_step': False, 'evidence_readable': False}
    stop_requested = (root / 'stop.request').exists() or (root / 'collector' / 'stop.request').exists()
    result['supervisor_fresh'] = supervisor_alive
    result['stop_requested'] = stop_requested
    result['ready_for_operator_next_step'] = supervisor_alive and not stop_requested and result['ready_for_operator_next_step']
    return result


if __name__ == '__main__':
    result = check(Path(sys.argv[1]))
    print(json.dumps(result))
    sys.exit(0 if result['ready_for_operator_next_step'] else 1)

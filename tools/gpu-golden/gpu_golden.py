#!/usr/bin/env python3
"""Validate, review and compare Android GPU RGB8 candidates (standard library only)."""
import argparse
import collections
import gzip
import hashlib
import html
import json
import math
from pathlib import Path
import re
import shutil
import struct
import sys
import zlib

LIMIT = 20_000_000
FIELDS = ('id', 'group', 'kind', 'width', 'height', 'strength', 'warmth', 'ev', 'analysis')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def pixels(root, case):
    path = root / case['file']
    blob = path.read_bytes()
    if digest(blob) != case['fileSha256'] or len(blob) != case['compressedBytes']:
        raise ValueError(f"Compressed file identity differs: {path.name}")
    size = case['width'] * case['height'] * 3
    with gzip.open(path, 'rb') as stream:
        rgb = stream.read(size + 1)
    if len(rgb) != size or digest(rgb) != case['rgbSha256']:
        raise ValueError(f"RGB identity/size differs: {path.name}")
    return rgb


def validate(root):
    capture = load_json(root / 'capture.json')
    if (capture['schema'], capture['status'], capture['matrixVersion'], capture['caseCount'],
        capture['fixtureSeed'], capture['baselineApproved']) != (1, 'complete', 'stage-clear-90-v1', 90, 20261110, False):
        raise ValueError('Incomplete or unsupported candidate; never infer success from files alone')
    cases = capture['cases']
    if len(cases) != 90 or [c['id'] for c in cases] != [f'c{i:03}' for i in range(1, 91)]:
        raise ValueError('Missing, reordered or duplicate cases')
    if collections.Counter(c['group'] for c in cases) != {'small': 58, 'preview': 18, 'export': 14}:
        raise ValueError('Coverage differs from approved 58 + 18 + 14 matrix')
    total = 0
    files = set()
    for case in cases:
        if case['file'] != case['id'] + '.rgb8.gz':
            raise ValueError('Unexpected filename')
        if not 1 <= case['width'] <= 1600 or not 1 <= case['height'] <= 1200:
            raise ValueError('Unexpected dimensions')
        expected_bands = [64, 256] if case['group'] == 'export' else []
        if case['bandRowsPassed'] != expected_bands:
            raise ValueError(f"Band coverage incomplete: {case['id']}")
        for rows in expected_bands:
            result = case[f'bands{rows}']
            if result['changedPixels'] != 0 or result['maxDelta'] != 0:
                raise ValueError('Band/full mismatch')
        pixels(root, case)
        total += case['compressedBytes']
        files.add(case['file'])
    if total != capture['totalImageBytes'] or total > LIMIT:
        raise ValueError('Image budget exceeded or inconsistent; ask before reducing coverage')
    if {p.name for p in root.glob('*.rgb8.gz')} != files:
        raise ValueError('Unexpected image files')
    host = load_json(root / 'host.json')
    if host['schema'] != 1 or host['instrumentationPassed'] is not True or host['sourceDirty'] is not False or host.get('phase') != 'complete':
        raise ValueError('Host run did not pass against a clean source')
    for field in ('sourceCommit', 'sourceTree'):
        if not re.fullmatch('[0-9a-f]{40}', host[field]):
            raise ValueError(f'Missing {field}')
    for field in ('appApkSha256', 'testApkSha256'):
        if not re.fullmatch('[0-9a-f]{64}', host[field]):
            raise ValueError(f'Missing {field}')
    for field in ('emulatorVersion', 'systemImageRevision', 'avdImage', 'gpuMode', 'gpuFeatures', 'hostGpuDrivers'):
        if not host['environment'].get(field):
            raise ValueError(f'Missing environment identity: {field}')
    for field in ('glVendor', 'glRenderer', 'glVersion', 'glslVersion', 'fingerprint'):
        if not capture['android'].get(field):
            raise ValueError(f'Missing Android identity: {field}')
    return capture, host


def png(path, width, height, rgb):
    """Write truecolour PNG, no image library or colour conversion; source bytes are untouched."""
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    row = width * 3
    scan = b''.join(b'\0' + rgb[y * row:(y + 1) * row] for y in range(height))
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
                     + chunk(b'sRGB', b'\0') + chunk(b'IDAT', zlib.compress(scan)) + chunk(b'IEND', b''))


def summary(expected, actual, width, height):
    if len(expected) != len(actual) or len(actual) != width * height * 3:
        raise ValueError('Dimensions differ')
    histogram = [0] * 256
    changed = 0
    min_x, min_y, max_x, max_y = width, height, -1, -1
    first = []
    for p in range(width * height):
        offset = p * 3
        delta = [abs(expected[offset + c] - actual[offset + c]) for c in range(3)]
        for d in delta:
            histogram[d] += 1
        if max(delta):
            changed += 1
            x, y = p % width, p // width
            min_x, min_y, max_x, max_y = min(min_x, x), min(min_y, y), max(max_x, x), max(max_y, y)
            if len(first) < 20:
                first.append({'x': x, 'y': y, 'deltaRgb': delta})
    rank = math.ceil(len(actual) * .999)
    count = 0
    p999 = 0
    for i, frequency in enumerate(histogram):
        count += frequency
        if count >= rank:
            p999 = i
            break
    return {'maxDelta': max(i for i, n in enumerate(histogram) if n), 'p999AllRgb': p999,
            'changedPixels': changed, 'changedFraction': changed / (width * height),
            'boundingBox': [min_x, min_y, max_x, max_y] if changed else None, 'firstChangedPixels': first}


def new_directory(path):
    path.mkdir(parents=True, exist_ok=False)


def review(root, output):
    capture, host = validate(root)
    new_directory(output)
    cards = []
    for case in capture['cases']:
        name = case['id'] + '.png'
        png(output / name, case['width'], case['height'], pixels(root, case))
        label = ' '.join(f'{key}={case[key]}' for key in FIELDS)
        cards.append(f'<figure><a href="{name}"><img loading="lazy" src="{name}"></a><figcaption>{html.escape(label)}</figcaption></figure>')
    page = '<!doctype html><meta charset="utf-8"><title>GPU candidate review</title>'
    page += '<style>body{background:#202124;color:white;font:16px sans-serif}main{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:16px}figure{margin:0}img{width:100%;height:260px;object-fit:contain}figcaption{overflow-wrap:anywhere}</style>'
    page += '<h1>Stage Clear GPU: candidate, not an approved baseline</h1><p>90 synthetic full-frame outputs. Open each image for native dimensions. No CPU image is an acceptance reference.</p>'
    page += f'<p>Source: {host["sourceCommit"]}; {capture["totalImageBytes"]:,} compressed image bytes.</p><main>' + ''.join(cards) + '</main>'
    (output / 'index.html').write_text(page, encoding='utf-8')


def identities(root):
    return {name: digest((root / name).read_bytes()) for name in ('capture.json', 'host.json')}


def approve(root, output, approval_ref):
    validate(root)
    if not approval_ref.strip():
        raise ValueError('Owner visual approval reference required')
    new_directory(output)
    for path in root.iterdir():
        if path.name in ('capture.json', 'host.json') or path.name.endswith('.rgb8.gz'):
            shutil.copyfile(path, output / path.name)
    (output / 'approval.json').write_text(json.dumps({'schema': 1, 'ownerVisualApprovalRef': approval_ref,
        'approvedFiles': identities(root)}, indent=2) + '\n', encoding='utf-8')


def compare(baseline, candidate, output):
    old, old_host = validate(baseline)
    new, new_host = validate(candidate)
    approval = load_json(baseline / 'approval.json')
    if approval.get('schema') != 1 or not approval.get('ownerVisualApprovalRef') or approval.get('approvedFiles') != identities(baseline):
        raise ValueError('Baseline has no matching owner visual approval record')
    if [[c[k] for k in FIELDS] for c in old['cases']] != [[c[k] for k in FIELDS] for c in new['cases']]:
        raise ValueError('Case definitions changed; owner approval needed before changing matrix')
    environment_changed = old['android'] != new['android'] or old_host['environment'] != new_host['environment']
    new_directory(output)
    results = []
    changed = []
    for before, after in zip(old['cases'], new['cases']):
        a, b = pixels(baseline, before), pixels(candidate, after)
        result = {'id': after['id'], **summary(a, b, after['width'], after['height'])}
        results.append(result)
        if result['changedPixels']:
            case_id = after['id']
            png(output / f'{case_id}-baseline.png', after['width'], after['height'], a)
            png(output / f'{case_id}-candidate.png', after['width'], after['height'], b)
            difference = bytes(min(255, abs(x - y) * 32) for x, y in zip(a, b))
            png(output / f'{case_id}-diff32.png', after['width'], after['height'], difference)
            changed.append(f'<h2>{case_id}</h2><p>{html.escape(json.dumps(result))}</p>' + ''.join(
                f'<a href="{case_id}-{kind}.png"><img width="320" src="{case_id}-{kind}.png"></a>' for kind in ('baseline', 'candidate', 'diff32')))
    passed = not environment_changed and not changed
    report = {'status': 'pass' if passed else 'hold', 'environmentChanged': environment_changed,
              'baselineCommit': old_host['sourceCommit'], 'candidateCommit': new_host['sourceCommit'],
              'policy': 'Same environment: exact RGB8. Changed driver/AVD: owner review, no automatic update.', 'cases': results}
    (output / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    (output / 'index.html').write_text('<!doctype html><meta charset="utf-8"><h1>GPU baseline comparison</h1><p>' +
        html.escape(report['policy']) + f'</p><p>Status: {report["status"]}; environmentChanged={environment_changed}</p>' + ''.join(changed), encoding='utf-8')
    return passed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('validate', 'review', 'approve'):
        cmd = sub.add_parser(name)
        cmd.add_argument('candidate', type=Path)
        if name != 'validate':
            cmd.add_argument('output', type=Path)
        if name == 'approve':
            cmd.add_argument('--approval-ref', required=True, help='Exact owner visual approval reference, not an agent or CI approval')
    cmd = sub.add_parser('compare')
    cmd.add_argument('baseline', type=Path)
    cmd.add_argument('candidate', type=Path)
    cmd.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'validate':
            capture, _ = validate(args.candidate)
            print(f"Validated 90 cases, {capture['totalImageBytes']} bytes; candidate only")
        elif args.command == 'review':
            review(args.candidate, args.output)
        elif args.command == 'approve':
            approve(args.candidate, args.output, args.approval_ref)
        else:
            return 0 if compare(args.baseline, args.candidate, args.output) else 1
    except (ValueError, KeyError, OSError) as error:
        print(f'HOLD: {error}', file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())

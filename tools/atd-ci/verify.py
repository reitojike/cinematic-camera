"""Reject missing, extra, duplicated or skipped tests and missing software-Canvas PNGs."""
import collections
import json
from pathlib import Path, PurePosixPath
from shutil import copyfile
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zlib
from PIL import Image


class ResultVerificationError(ValueError):
    """Public diagnostics assembled only from the checked-in allowlist and counters."""


_SAFE_ERROR_TYPES = {
    'java.lang.AssertionError',
    'java.lang.IllegalArgumentException',
    'java.lang.IllegalStateException',
    'java.lang.NullPointerException',
    'java.lang.SecurityException',
    'java.io.FileNotFoundException',
    'java.io.IOException',
    'kotlinx.coroutines.TimeoutCancellationException',
    'org.junit.AssumptionViolatedException',
    'org.junit.ComparisonFailure',
}


def verify_tests(paths, expected):
    wanted = collections.Counter((c, m) for c, methods in expected.items() for m in methods)
    actual, failures, rejected_suites = [], [], 0
    for path in paths:
        root = ET.parse(path).getroot()
        for node in root.iter():
            if node.tag in ('testsuite', 'testsuites'):
                if any(float(node.get(key, '0')) != 0 for key in ('failures', 'errors', 'skipped', 'disabled')):
                    rejected_suites += 1
        for case in root.iter('testcase'):
            key = (case.get('classname'), case.get('name'))
            actual.append(key)
            for tag in ('failure', 'error', 'skipped'):
                detail = case.find(tag)
                if detail is None:
                    continue
                if key not in wanted:
                    failures.append(f'unexpected method: {tag}')
                    continue
                # Names come from the checked-in exact allowlist; error type is a fixed enum.
                # No raw JUnit text, arbitrary frames, exception message, path or URI is emitted.
                cls, method = key
                source = cls.rsplit('.', 1)[-1]
                lines = re.findall(r'\(' + re.escape(source) + r'\.kt:([0-9]{1,6})\)', detail.text or '')
                error = detail.get('type')
                kind = error if error in _SAFE_ERROR_TYPES else 'unclassified'
                hint = f' test_line={lines[0]}' if lines else ''
                if len(lines) > 1:
                    hint += f' caller_line={lines[1]}'
                failures.append(f'{cls}#{method}: {tag} type={kind}{hint}')
    if failures:
        raise ResultVerificationError('ATD_METHOD_FAILURE ' + '; '.join(failures))
    if rejected_suites:
        raise ResultVerificationError(f'ATD_SUITE_FAILURE rejected={rejected_suites} (no per-method diagnosis)')
    if collections.Counter(actual) != wanted:
        raise ResultVerificationError(f'ATD_METHOD_COUNT_MISMATCH expected={sum(wanted.values())} observed={len(actual)}')
    return len(actual)

def verify_png(path):
    data = path.read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError(f'{path}: not PNG')
    pos, compressed, dimensions, ended = 8, bytearray(), None, False
    while pos < len(data):
        length = struct.unpack('>I', data[pos:pos+4])[0]
        kind = data[pos+4:pos+8]
        payload = data[pos+8:pos+8+length]
        crc = data[pos+8+length:pos+12+length]
        if len(crc) != 4 or zlib.crc32(kind + payload) != struct.unpack('>I', crc)[0]:
            raise ValueError(f'{path}: invalid PNG CRC/truncated chunk')
        if kind == b'IHDR':
            width, height, depth, color, compression, filt, interlace = struct.unpack('>IIBBBBB', payload)
            if not (0 < width <= 8192 and 0 < height <= 8192 and depth == 8 and color in (2, 6) and compression == filt == interlace == 0):
                raise ValueError(f'{path}: unexpected dimensions/PNG layout')
            dimensions = width, height, 3 if color == 2 else 4
        elif kind == b'IDAT':
            compressed.extend(payload)
        elif kind == b'IEND':
            ended = True
        pos += 12 + length
    if not ended or dimensions is None:
        raise ValueError(f'{path}: missing PNG structure')
    w, h, channels = dimensions
    if len(zlib.decompress(compressed)) != h * (1 + w * channels):
        raise ValueError(f'{path}: invalid decoded length')
    with Image.open(path) as image:
        image.load()  # Decoder checks scanline filters, not just compressed byte count.
        if image.size != (w, h):
            raise ValueError(f"{path}: decoder/header dimension mismatch")
    return w, h


def verify_and_stage_canvas(root, expected, stage):
    resolved_root = root.resolve()
    pngs = [p for p in root.rglob('*.png')
            if p.is_file() and not p.is_symlink() and p.resolve().is_relative_to(resolved_root)]
    approved = []
    for suffix in expected:
        relative = PurePosixPath(suffix)
        if (relative.is_absolute() or '..' in relative.parts
                or relative.as_posix() != suffix or relative.suffix != '.png'):
            raise ValueError('Invalid reviewed image path')
        matches = [p for p in pngs if p.relative_to(root).as_posix() == suffix
                   or p.relative_to(root).as_posix().endswith('/' + suffix)]
        if len(matches) != 1:
            raise ValueError('Missing or duplicate expected Canvas image')
        size = verify_png(matches[0])
        if min(size) < 320:
            raise ValueError('Unexpectedly small Canvas fixture')
        approved.append((matches[0], relative, size))
    stage.mkdir(parents=True, exist_ok=False)
    for path, relative, size in approved:
        target = stage / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        copyfile(path, target)
        print(f'SOFTWARE_CANVAS {relative}: {size}')


def main():
    spec = json.loads(Path(__file__).with_name('allowlist.json').read_text())
    if len(sys.argv) == 2 and sys.argv[1] == '--selection':
        print(','.join(f'{c}#{m}' for c, methods in spec['tests'].items() for m in methods))
        return
    build = Path(sys.argv[1])
    paths = list((build / 'outputs/androidTest-results/connected').rglob('TEST-*.xml'))
    count = verify_tests(paths, spec['tests'])
    verify_and_stage_canvas(build / 'outputs/connected_android_test_additional_output',
                            spec['images'], Path(sys.argv[2]))
    print(f'ATD_EXPERIMENT_PASS tests={count} failures=0 errors=0 skipped=0 PNGs={len(spec["images"])}')
    print('Software Canvas fixtures only; NOT display compositor / camera / GPU regression evidence.')


if __name__ == '__main__':
    try:
        main()
    except ET.ParseError:
        print('ATD_INVALID_RESULT_XML', file=sys.stderr)
        sys.exit(1)
    except ResultVerificationError as error:
        print(error, file=sys.stderr)
        sys.exit(1)
    except (ValueError, OSError, struct.error, zlib.error):
        print('ATD_VERIFICATION_FAILURE', file=sys.stderr)
        sys.exit(1)

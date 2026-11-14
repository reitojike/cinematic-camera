"""Reject missing, extra, duplicated or skipped tests and missing software-Canvas PNGs."""
import collections
import json
from pathlib import Path
import struct
import sys
import xml.etree.ElementTree as ET
import zlib
from PIL import Image


def verify_tests(paths, expected):
    actual = []
    for path in paths:
        root = ET.parse(path).getroot()
        for node in root.iter():
            if node.tag in ('testsuite', 'testsuites'):
                for key in ('failures', 'errors', 'skipped', 'disabled'):
                    if float(node.get(key, '0')) != 0:
                        raise ValueError(f'{path}: {key} != 0')
        for case in root.iter('testcase'):
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError(f'{path}: non-success testcase')
            actual.append((case.get('classname'), case.get('name')))
    wanted = collections.Counter((c, m) for c, methods in expected.items() for m in methods)
    if collections.Counter(actual) != wanted:
        raise ValueError(f'Expected exactly {wanted}, got {collections.Counter(actual)}')
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


def main():
    spec = json.loads(Path(__file__).with_name('allowlist.json').read_text())
    if len(sys.argv) == 2 and sys.argv[1] == '--selection':
        print(','.join(f'{c}#{m}' for c, methods in spec['tests'].items() for m in methods))
        return
    build = Path(sys.argv[1])
    paths = list((build / 'outputs/androidTest-results/managedDevice').rglob('TEST-*.xml'))
    count = verify_tests(paths, spec['tests'])
    pngs = list((build / 'outputs').rglob('*.png'))
    for suffix in spec['images']:
        matches = [p for p in pngs if p.as_posix().endswith('/' + suffix)]
        if len(matches) != 1:
            raise ValueError(f'{suffix}: expected one artifact, got {matches}')
        size = verify_png(matches[0])
        if min(size) < 320:
            raise ValueError(f'{suffix}: unexpectedly small fixture: {size}')
        print(f'SOFTWARE_CANVAS {suffix}: {size}')
    print(f'ATD_EXPERIMENT_PASS tests={count} failures=0 errors=0 skipped=0 PNGs={len(spec["images"])}')
    print('Software Canvas fixtures only; NOT display compositor / camera / GPU regression evidence.')


if __name__ == '__main__':
    main()

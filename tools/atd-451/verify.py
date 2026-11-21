"""Read only Gradle connected output; stage allowlisted evidence after exact PASS."""
import sys
from pathlib import Path
from collect import collect

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'atd-ci'))
from verify import verify_tests

EXPECTED = {'dev.cinematic.p0.photoroll.DevelopCompactAccessibilityAndroidTest':
            ['shortViewportAtSystemDoubleFontReachesSaveAndComparison']}

if __name__ == '__main__':
    build, stage = map(Path, sys.argv[1:])
    paths = list((build / 'outputs/androidTest-results/connected').rglob('TEST-*.xml'))
    verify_tests(paths, EXPECTED)
    collect(build / 'outputs/connected_android_test_additional_output', stage, False)
    print('Exact synthetic method and Canvas evidence verified; visual review pending')

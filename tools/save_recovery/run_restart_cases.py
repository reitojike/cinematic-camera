"""Emulator-only #377 host kill protocol. Default prints plan; --execute needs a reserved AVD.

Does not build, install, boot, wipe, clear app data, or delete fixture evidence. Local output may
contain synthetic URIs/PIDs and must not be published as raw logs. Requires Python 3 and adb.
"""
import argparse
import json
import re
import subprocess
import time
import uuid
from pathlib import Path

PACKAGE = "dev.cinematic.p0"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = PACKAGE + ".rawcapture.SaveRecoveryRestartTest"
BOUNDARIES = (
    "intent", "private", "journal", "insert-before", "insert", "copy", "close", "publish", "ack", "final-ack",
    "unknown-empty", "unknown-partial", "unknown-complete", "unknown-public",
    "b-consent", "b-cursor", "b-intent", "b-gpu-start",
)


def run_cases(args):
    cases = [(b, "backend") for b in BOUNDARIES] if args.suite == "boundaries" else [
        (b, mode) for b in ("intent", "b-cursor") for mode in ("ui", "fgs")
    ]
    if args.boundary:
        cases = [(args.boundary, args.resume)]
    if any(mode != "backend" and b not in ("intent", "b-cursor") for b, mode in cases):
        raise ValueError("UI/FGS probes support intent and b-cursor only")
    if not args.execute:
        print(json.dumps({"execute": False, "cases": cases, "resources": "one reserved emulator; no build/install"}, indent=2))
        return
    if not args.serial or not re.fullmatch(r"emulator-\d+", args.serial):
        raise ValueError("Explicit emulator serial required; physical devices are prohibited")
    adb = [args.adb, "-s", args.serial]

    def call(*command, check=True):
        return subprocess.run(adb + list(command), text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=30, check=check).stdout.strip()

    if call("shell", "getprop", "ro.hardware") not in ("ranchu", "goldfish"):
        raise RuntimeError("Not a supported emulator")
    if call("shell", "run-as", PACKAGE, "id", check=False).startswith("run-as:"):
        raise RuntimeError("Ordinary debuggable APK with instrumentation must already be installed")
    root = Path(args.output).resolve() / ("run-" + uuid.uuid4().hex[:12])
    root.mkdir(parents=True, exist_ok=False)
    results = []
    for boundary, mode in cases:
        case = uuid.uuid4().hex
        relative = "files/save-recovery-test-" + case
        common = ["shell", "am", "instrument", "-w", "-r", "-e", "isolated", "true",
                  "-e", "saveRecoveryRestart", "true", "-e", "recoveryCase", case,
                  "-e", "recoveryBoundary", boundary, "-e", "recoveryResume", mode]
        started = time.monotonic()
        # Caller reserved this AVD; force-stop only this test's app, never clear its data.
        call("shell", "am", "force-stop", PACKAGE)
        log = root / (boundary + "-" + mode + "-seed.txt")
        with log.open("w", encoding="utf-8") as stream:
            seed = subprocess.Popen(adb + common + ["-e", "class", TEST + "#seedAwaitHostKill", RUNNER],
                                    stdout=stream, stderr=subprocess.STDOUT, text=True)
            try:
                until = time.monotonic() + 150
                old_pid = None
                while time.monotonic() < until:
                    armed = call("shell", "run-as", PACKAGE, "cat", relative + "/armed.txt", check=False)
                    match = re.fullmatch(re.escape(boundary) + r" (\d+)", armed)
                    if match:
                        old_pid = match.group(1)
                        if old_pid not in call("shell", "pidof", PACKAGE, check=False).split():
                            raise RuntimeError("Armed PID is not the live target process")
                        break
                    if seed.poll() is not None:
                        raise RuntimeError("Seed ended before its kill gate; inspect private log")
                    time.sleep(.2)
                if old_pid is None:
                    raise RuntimeError("Kill gate timeout")
                call("shell", "am", "force-stop", PACKAGE)
                until = time.monotonic() + 15
                while call("shell", "pidof", PACKAGE, check=False) and time.monotonic() < until:
                    time.sleep(.1)
                if call("shell", "pidof", PACKAGE, check=False):
                    raise RuntimeError("Target PID absence not proven")
                seed.wait(timeout=20)  # Killed instrumentation is expected, not a successful test.
            finally:
                if seed.poll() is None:
                    call("shell", "am", "force-stop", PACKAGE)
                    seed.terminate()
                    seed.wait(timeout=10)
        resume = subprocess.run(adb + common + ["-e", "class", TEST + "#resumeAfterHostKill", RUNNER],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300)
        (root / (boundary + "-" + mode + "-resume.txt")).write_text(resume.stdout, encoding="utf-8")
        if resume.returncode or not re.search(r"OK \(1 test\)", resume.stdout) or "FAILURES" in resume.stdout:
            raise RuntimeError("Resume did not pass exactly one test; inspect private log")
        receipt = call("shell", "run-as", PACKAGE, "cat", relative + "/resume-ok.txt")
        match = re.fullmatch(re.escape(boundary + " " + mode) + r" old=(\d+) new=(\d+)", receipt)
        if not match or match.group(1) != old_pid or match.group(2) == old_pid:
            raise RuntimeError("New-process receipt missing or PID reused; rerun with a new case")
        results.append({"boundary": boundary, "mode": mode, "status": "PASS", "new_process": True,
                        "seconds": round(time.monotonic() - started, 1)})
        (root / "summary.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(json.dumps(results[-1]))  # No URI, device identifier or PID in shareable summary.


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--suite", choices=("boundaries", "ui"), default="boundaries")
    parser.add_argument("--boundary", choices=BOUNDARIES)
    parser.add_argument("--resume", choices=("backend", "ui", "fgs"), default="backend")
    parser.add_argument("--output", default="save-recovery-private-results")
    run_cases(parser.parse_args())

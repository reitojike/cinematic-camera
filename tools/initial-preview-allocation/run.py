"""Fresh Android processes on explicitly requested Mesa lavapipe. Stdlib only.
Never publish raw command output, device IDs or paths. Fail closed on backend mismatch.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

PACKAGE = "dev.cinematic.p0"
CLASS = PACKAGE + ".photoroll.InitialPreviewAllocationIsolationAndroidTest"
OUT = "/sdcard/Android/data/" + PACKAGE + "/files/additional_test_output"


class DiagnosticFailure(RuntimeError):
    """Only constant reason codes, never command output or exception payloads."""
    CODES = {"COMMAND_FAILED", "NONEMPTY_RUNNER", "AVD_CREATE_FAILED", "BOOT_EXIT", "BOOT_TIMEOUT", "TEST_NOT_PASSED"}
    def __init__(self, code):
        if code not in self.CODES:
            raise ValueError("Unknown diagnostic reason code")
        super().__init__(code)


def failure_summary(error):
    return str(error) if isinstance(error, DiagnosticFailure) else type(error).__name__


def checked(args, timeout=120):
    result = subprocess.run(args, capture_output=True, timeout=timeout)
    if result.returncode:
        raise DiagnosticFailure("COMMAND_FAILED")
    return result.stdout.decode("utf-8", errors="strict")


def numeric(value):
    if type(value) is not int or value < 0:
        raise ValueError("Expected nonnegative integer sample")
    return value


def allocation(data, same_owner):
    labels = (["baseline"] + [f"photo-closed-{i}" for i in range(4)] + ["owner-closed"]
              if same_owner else ["baseline"] + [f"closed-{i}" for i in range(4)])
    samples = data["samples"]
    if [item["label"] for item in samples] != labels:
        raise ValueError("Missing or unexpected sample sequence")
    clean = []
    for item in samples:
        row = {"label": item["label"]}
        for phase in ("idleBeforeGc", "afterGcRequest"):
            row[phase] = {key: numeric(item[phase][key]) for key in
                          ("nativeHeapBytes", "pssKb", "javaUsedBytes")}
        if same_owner and item["label"].startswith("photo-closed-"):
            for key in ("drawCount", "ledgerBytes", "windowCount"):
                row[key] = numeric(item[key])
            if row["ledgerBytes"] != 0:
                raise ValueError("Photo ledger remains allocated")
        clean.append(row)
    return clean


def backend(data):
    result = {}
    for key in ("vendor", "renderer", "version"):
        value = data[key]
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9 ._,():+\-]{1,256}", value):
            raise ValueError("Unsafe or unexpected backend identity")
        result[key] = value
    combined = " ".join(result.values()).lower()
    if "swiftshader" in combined or not any(x in result["renderer"].lower() for x in ("lavapipe", "llvmpipe")):
        raise ValueError("Mesa backend not established")
    return result


def revision(path):
    text = Path(path).read_text()
    match = re.search(r"^Pkg.Revision\s*=\s*([0-9.]+)\s*$", text, re.M)
    if not match:
        raise ValueError("SDK revision unavailable")
    return match.group(1)


def main():
    sdk = Path(os.environ["ANDROID_HOME"])
    emulator = sdk / "emulator/emulator"
    adb_binary = sdk / "platform-tools/adb"
    avdmanager = sdk / "cmdline-tools/latest/bin/avdmanager"
    image = "system-images;android-37.0;default;x86_64"
    serial = "emulator-5554"
    def adb(*args, timeout=120):
        return checked([str(adb_binary), "-s", serial, *args], timeout)
    # Refuse an existing emulator instead of taking over an unrelated process.
    print("PHASE=runner_inventory", flush=True)
    inventory = checked([str(adb_binary), "devices"])
    if any(line.strip() for line in inventory.splitlines()[1:]):
        raise DiagnosticFailure("NONEMPTY_RUNNER")
    print("PHASE=avd_create", flush=True)
    result = subprocess.run([str(avdmanager), "create", "avd", "--name", "allocation_mesa",
                             "--package", image], input=b"no\n", capture_output=True, timeout=120)
    if result.returncode:
        raise DiagnosticFailure("AVD_CREATE_FAILED")
    apk = Path("app/build/outputs/apk/debug/app-debug.apk")
    test_apk = Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    print("PHASE=emulator_boot", flush=True)
    process = subprocess.Popen([str(emulator), "-avd", "allocation_mesa", "-port", "5554",
        "-no-snapshot", "-no-window", "-no-audio", "-no-boot-anim", "-gpu", "lavapipe",
        "-accel", "on", "-memory", "2048", "-cores", "2"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.monotonic() + 240
        while True:
            if process.poll() is not None:
                raise DiagnosticFailure("BOOT_EXIT")
            try:
                if adb("shell", "getprop", "sys.boot_completed", timeout=10).strip() == "1":
                    break
            except (RuntimeError, subprocess.TimeoutExpired):
                pass
            if time.monotonic() > deadline:
                raise DiagnosticFailure("BOOT_TIMEOUT")
            time.sleep(2)
        print("PHASE=apk_install", flush=True)
        adb("install", str(apk))
        adb("install", str(test_apk))
        def method(name, filename):
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "force-stop", PACKAGE + ".test")
            output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", name,
                         "-e", "isolated", "true", "-e", "gpuInputSafety", "true",
                         "-e", "additionalTestOutputDir", OUT,
                         PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=360)
            if not re.search(r"OK \(1 test\)", output) or "FAILURES" in output or "INSTRUMENTATION_FAILED" in output:
                raise DiagnosticFailure("TEST_NOT_PASSED")
            return json.loads(adb("shell", "cat", OUT + "/initial-preview-allocation/" + filename))
        print("PHASE=backend_observation", flush=True)
        identity = backend(method(PACKAGE + ".photoroll.InitialPreviewMesaBackendAndroidTest#recordActualMesaRenderer", "backend.json"))
        print("PHASE=new_owner_measurement", flush=True)
        new = allocation(method(CLASS + "#gpuOnlyFourClosedOwnersWithOneSharedSyntheticInput", "gpu-only.json"), False)
        print("PHASE=same_owner_measurement", flush=True)
        same = allocation(method(CLASS + "#gpuOnlyFourPhotosOnOneOwner", "gpu-same-owner.json"), True)
        sha = checked(["git", "rev-parse", "HEAD"]).strip()
        if not re.fullmatch("[0-9a-f]{40}", sha):
            raise ValueError("Invalid source identity")
        report = {"scope": "Android Emulator Mesa lavapipe; NOT host llvmpipe JVM CI, Pixel, or a memory safety verdict",
                  "requestedBackend": "lavapipe", "observedBackend": identity,
                  "backendObservationScope": "Separate fresh instrumentation on the same unchanged AVD, not per-allocation-process GL observation",
                  "sourceCommit": sha, "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                  "testApkSha256": hashlib.sha256(test_apk.read_bytes()).hexdigest(),
                  "emulatorRevision": revision(sdk / "emulator/source.properties"),
                  "systemImageRevision": revision(sdk / "system-images/android-37.0/default/x86_64/source.properties"),
                  "systemImage": "AOSP API37 x86_64", "newOwner": new, "sameOwner": same,
                  "gcMeaning": "GC requested only; collection/native release not guaranteed; no threshold assertion"}
        Path("allocation-evidence").mkdir(exist_ok=True)
        Path("allocation-evidence/aggregate.json").write_text(json.dumps(report, indent=2) + "\n")
    finally:
        # Preserve a BOOT_EXIT reason: an already-exited emulator cannot answer adb.
        if process.poll() is None:
            try:
                adb("emu", "kill", timeout=15)
            except (DiagnosticFailure, subprocess.TimeoutExpired):
                process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=15)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Do not echo exception strings: adb/JSON failures may contain raw paths or output.
        print("Allocation diagnostic failed: " + failure_summary(error) + "; no fallback or partial success accepted")
        raise SystemExit(1)

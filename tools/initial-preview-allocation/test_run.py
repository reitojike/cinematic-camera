import unittest
from run import allocation, backend

class CollectorTest(unittest.TestCase):
    def test_backend_must_be_observed_mesa_not_swiftshader(self):
        values = {"vendor": "Google", "renderer": "Android Emulator (llvmpipe (LLVM 19.1))", "version": "OpenGL ES 3.2"}
        self.assertEqual(backend(values), values)
        for renderer in ("SwiftShader", "GPU", "/private/path", "llvmpipe\nsecret"):
            with self.assertRaises(ValueError):
                backend(dict(values, renderer=renderer))
    def test_vendor_or_version_does_not_prove_renderer(self):
        for key in ("vendor", "version"):
            values = {"vendor": "Google", "renderer": "Other GPU", "version": "OpenGL ES 3.2"}
            values[key] = "Mesa llvmpipe"
            with self.assertRaises(ValueError): backend(values)

    def test_samples_whitelist_and_completeness(self):
        samples=[]
        for label in ["baseline"] + [f"closed-{i}" for i in range(4)]:
            sample={"nativeHeapBytes": 42, "javaUsedBytes": 24, "pssKb": 1, "private": "not copied"}
            samples.append({"label": label, "idleBeforeGc": sample, "afterGcRequest": sample})
        result=allocation({"samples":samples},False)
        self.assertNotIn("private", result[0]["afterGcRequest"])
        with self.assertRaises(ValueError): allocation({"samples":samples[:-1]},False)
        samples[0]["afterGcRequest"]["nativeHeapBytes"]=True
        with self.assertRaises(ValueError): allocation({"samples":samples},False)

    def test_same_owner_requires_six_samples_and_zero_photo_ledger(self):
        samples=[]
        for label in ["baseline"] + [f"photo-closed-{i}" for i in range(4)] + ["owner-closed"]:
            values={"nativeHeapBytes": 5, "javaUsedBytes": 7, "pssKb": 2}
            row={"label": label, "idleBeforeGc": values, "afterGcRequest": values}
            if label.startswith("photo-closed-"):
                row.update(drawCount=len(samples), ledgerBytes=0, windowCount=1)
            samples.append(row)
        self.assertEqual(len(allocation({"samples": samples}, True)), 6)
        samples[2]["ledgerBytes"]=16
        with self.assertRaises(ValueError): allocation({"samples": samples}, True)

    def test_workflow_diagnostic_scope(self):
        from pathlib import Path
        workflow=Path(".github/workflows/initial-preview-mesa-allocation.yml").read_text()
        self.assertIn("contents: read", workflow)
        self.assertIn("DIAGNOSTIC_RUNNER_ENVIRONMENT: ${{ runner.environment }}", workflow)
        self.assertIn('test "$hosted" = true && test "$character" = true && test "$readable" = true && test "$writable" = true', workflow)
        self.assertIn("allocation-evidence/aggregate.json", workflow)
        self.assertNotIn("pull_request_target", workflow)
        self.assertNotIn("setfacl", workflow)
        self.assertNotIn("chmod", workflow)
        self.assertNotIn("secrets.", workflow)
        self.assertNotIn("if: always()", workflow)

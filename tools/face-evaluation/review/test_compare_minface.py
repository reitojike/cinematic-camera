import copy
import json
import hashlib
import subprocess
from pathlib import Path
import unittest
import test_convert_private as fixtures
from compare_minface import compare, write_comparison, BASELINE, CANDIDATE
from convert_private import convert

class MinFaceComparisonTest(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.ConverterTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.root = fixture.root
        self.rows = [copy.deepcopy(fixture.rows[1]), copy.deepcopy(fixture.rows[1])]
        self.rows[0]["detector"] = BASELINE
        self.rows[1]["detector"] = CANDIDATE
        self.paths = [self.root/"baseline.jsonl", self.root/"candidate.jsonl"]
    def save(self):
        for path, row in zip(self.paths, self.rows):
            path.write_text(json.dumps(row)+"\n", encoding="utf-8")
    def run_compare(self):
        self.save()
        return compare(self.root, *self.paths)
    def test_explicit_separate_schema(self):
        data = self.run_compare()
        self.assertEqual(data["schema"], "face-minsize-comparison-v1")
        self.assertEqual(data["scope"], "diagnostic-unscored-only")
        self.assertEqual(set(data["samples"][0]["conditions"]), {"accurate-min0.1", "accurate-min0.05"})
        self.assertNotIn("results", data["samples"][0])
        self.assertNotIn("scored", data)
    def test_swapped_roles_rejected(self):
        self.save()
        with self.assertRaises(ValueError): compare(self.root, self.paths[1], self.paths[0])
    def test_duplicate_condition_rejected(self):
        self.rows[1]["detector"] = BASELINE
        with self.assertRaises(ValueError): self.run_compare()
    def test_other_minimum_rejected(self):
        self.rows[1]["detector"] = CANDIDATE.replace("0.05", "0.01")
        with self.assertRaises(ValueError): self.run_compare()
    def test_provenance_mismatch_rejected(self):
        for key, value in [("revision", "b"*64), ("input_sha256", "b"*64),
                           ("manifest_sha256", "b"*64), ("phase", "warm"),
                           ("iteration", 1), ("width", 2)]:
            with self.subTest(key=key):
                before=copy.deepcopy(self.rows)
                self.rows[1][key]=value
                with self.assertRaises(ValueError): self.run_compare()
                self.rows=before
    def test_failure_not_empty_success(self):
        self.rows[1]["status"]="FAILED"
        self.rows[1]["error_type"]="private text"
        result=self.run_compare()["samples"][0]["conditions"]["accurate-min0.05"]
        self.assertEqual(result, {"status":"FAILED", "phase":"first-call"})
    def test_existing_output_unchanged(self):
        self.save()
        output=self.root/"comparison.json"
        write_comparison(self.root,*self.paths,output)
        before=output.read_bytes()
        with self.assertRaises(FileExistsError): write_comparison(self.root,*self.paths,output)
        self.assertEqual(output.read_bytes(),before)
    def test_baseline_output_cannot_be_overwritten(self):
        self.save()
        before=self.paths[0].read_bytes()
        with self.assertRaises(FileExistsError): write_comparison(self.root,*self.paths,self.paths[0])
        self.assertEqual(self.paths[0].read_bytes(),before)
    def test_legacy_converter_rejects_pair(self):
        self.save()
        with self.assertRaises(ValueError): convert(self.root,self.paths)
    def test_tampered_pixels_rejected(self):
        self.save()
        pixels=next(self.root.glob("*.rgba"));pixels.write_bytes(bytes([0,0,0,255]))
        with self.assertRaises(ValueError): compare(self.root,*self.paths)
    def test_legacy_three_way_rejects_candidate_id(self):
        fixture=fixtures.ConverterTest();fixture.setUp()
        try:
            fixture.rows[1]["detector"]=CANDIDATE
            with self.assertRaises(ValueError): fixture.run_convert()
        finally: fixture.doCleanups()
    def test_legacy_html_rejects_comparison_schema(self):
        data=self.run_compare()
        script="const c=require('./core.js');let s='';process.stdin.on('data',x=>s+=x);process.stdin.on('end',()=>{try{c.validate(JSON.parse(s));process.exitCode=2;}catch(e){process.exitCode=0;}});"
        subprocess.run(["node","-e",script],input=json.dumps(data),text=True,
                       cwd=Path(__file__).parent,capture_output=True,check=True)
    def test_two_sample_phase_and_changed_later_detector(self):
        mpath=self.root/"manifest.json"
        manifest=json.loads(mpath.read_text())
        pixels=bytes([1,2,3,255]);second=dict(id="evaluation-fedcba9876543210",width=1,height=1,sha256=hashlib.sha256(pixels).hexdigest())
        manifest["samples"].append(second)
        raw=json.dumps(manifest).encode();mpath.write_bytes(raw)
        (self.root/(second["id"]+".rgba")).write_bytes(pixels)
        pairs=[]
        for path,first in zip(self.paths,self.rows):
            first["manifest_sha256"]=hashlib.sha256(raw).hexdigest()
            warm=dict(first,sample=second["id"],input_sha256=second["sha256"],phase="warm")
            pairs.append([first,warm])
            path.write_text(json.dumps(first)+"\n"+json.dumps(warm)+"\n")
        data=compare(self.root,*self.paths)
        self.assertEqual(data["samples"][1]["conditions"]["accurate-min0.05"]["phase"],"warm")
        pairs[1][1]["detector"]=BASELINE
        self.paths[1].write_text("\n".join(json.dumps(r) for r in pairs[1])+"\n")
        with self.assertRaises(ValueError): compare(self.root,*self.paths)
    def test_dedicated_ui_candidate_only_end_to_end(self):
        data=self.run_compare()
        script="const c=require('./minface-core.js');let s='';process.stdin.on('data',x=>s+=x);process.stdin.on('end',()=>{const d=c.validate(JSON.parse(s));const a=c.aggregate(d,{[d.samples[0].id]:{'accurate-min0.05':{missed:0,falseIndices:[]}}});if(a.conditions[0].scored!==0||a.conditions[0].unscored!==1||a.conditions[1].scored!==1||a.schema!=='face-minsize-aggregate-v1')process.exitCode=2;});"
        subprocess.run(["node","-e",script],input=json.dumps(data),text=True,
                       cwd=Path(__file__).parent,capture_output=True,check=True)
    def test_missing_rows_rejected(self):
        self.save();self.paths[1].write_text("")
        with self.assertRaises(ValueError): compare(self.root,*self.paths)

if __name__ == "__main__": unittest.main()
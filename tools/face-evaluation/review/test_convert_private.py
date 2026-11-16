import copy
import hashlib
import json
import tempfile
import subprocess
import unittest
from pathlib import Path
from convert_private import convert, DETECTORS, GEOMETRY

class ConverterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        pixels = bytes([12, 34, 56, 255])
        self.sample = dict(id="evaluation-0123456789abcdef", width=1, height=1,
                           sha256=hashlib.sha256(pixels).hexdigest())
        (self.root/(self.sample["id"]+".rgba")).write_bytes(pixels)
        manifest = dict(schema=1, geometry=GEOMETRY, revision="a"*64, samples=[self.sample])
        raw = json.dumps(manifest).encode()
        (self.root/"manifest.json").write_bytes(raw)
        self.rows = [dict(schema=1, sample=self.sample["id"], detector=d, iteration=0,
                          phase="first-call", geometry=GEOMETRY, input_sha256=self.sample["sha256"],
                          manifest_sha256=hashlib.sha256(raw).hexdigest(), revision="a"*64,
                          status="SUCCESS", width=1, height=1, boxes=[[0,0,1,1]], sdk_ns=2000000,
                          adapter_ns=3000000) for d in DETECTORS]
    def run_convert(self):
        files=[]
        for i,row in enumerate(self.rows):
            p=self.root/f"result{i}.jsonl"
            p.write_text(json.dumps(row)+"\n",encoding="utf-8")
            files.append(p)
        return convert(self.root,files)
    def test_success_allowlist(self):
        self.rows[0]["private_extra"]="must-not-copy"
        data=self.run_convert()
        self.assertNotIn("must-not-copy",json.dumps(data))
        r=data["samples"][0]["results"]["mlkit-fast"]
        self.assertEqual((r["ms"],r["adapter_ms"],r["phase"]),(2,3,"first-call"))
    def test_html_contract_end_to_end(self):
        data=self.run_convert()
        script="""const c=require('./core.js');let raw='';process.stdin.on('data',b=>raw+=b);process.stdin.on('end',()=>{const d=c.validate(JSON.parse(raw));const labels=Object.fromEntries(d.samples.map(s=>[s.id,Object.fromEntries(c.detectors.map(k=>[k,{missed:0,falseIndices:[]}]))]));const a=c.aggregate(d,labels);if(a.detectors.some(r=>r.scored!==1||r.median_ms!==null||r.timing_by_phase['first-call'].median_sdk_ms!==2))process.exit(2);if(JSON.stringify(a).includes(d.samples[0].id))process.exit(3);});"""
        subprocess.run(["node","-e",script],input=json.dumps(data),text=True,
                       cwd=Path(__file__).parent,check=True,capture_output=True)
    def test_non_object_row(self):
        self.rows[0]=[]
        with self.assertRaises(ValueError):self.run_convert()
    def test_two_samples_phase_and_order(self):
        mpath=self.root/"manifest.json"
        manifest=json.loads(mpath.read_text())
        pixels=bytes([77,88,99,255])
        second=dict(id="evaluation-fedcba9876543210",width=1,height=1,sha256=hashlib.sha256(pixels).hexdigest())
        manifest["samples"].append(second)
        raw=json.dumps(manifest).encode();mpath.write_bytes(raw)
        (self.root/(second["id"]+".rgba")).write_bytes(pixels)
        files=[]
        for i,first in enumerate(self.rows):
            first["manifest_sha256"]=hashlib.sha256(raw).hexdigest()
            warm=dict(first,sample=second["id"],input_sha256=second["sha256"],phase="warm",sdk_ns=1000000)
            p=self.root/f"pair{i}.jsonl";p.write_text(json.dumps(first)+"\n"+json.dumps(warm)+"\n")
            files.append(p)
        data=convert(self.root,files)
        self.assertEqual(data["samples"][0]["results"]["mlkit-fast"]["phase"],"first-call")
        self.assertEqual(data["samples"][1]["results"]["mlkit-fast"]["phase"],"warm")
        rows=files[0].read_text().splitlines();files[0].write_text("\n".join(reversed(rows))+"\n")
        with self.assertRaises(ValueError):convert(self.root,files)
    def test_provenance(self):
        for key,value in [("input_sha256","b"*64),("manifest_sha256","b"*64),
                          ("revision","b"*64),("sample","evaluation-1111111111111111"),
                          ("phase","warm"),("iteration",1),("width",2)]:
            with self.subTest(key=key):
                before=copy.deepcopy(self.rows);self.rows[0][key]=value
                with self.assertRaises(ValueError):self.run_convert()
                self.rows=before
    def test_missing_and_repeated(self):
        self.rows.pop()
        with self.assertRaises(ValueError):self.run_convert()
        self.rows.append(copy.deepcopy(self.rows[0]))
        with self.assertRaises(ValueError):self.run_convert()
    def test_failure_preserved(self):
        self.rows[0]["status"]="FAILED"
        self.rows[0]["error_type"]="private-details"
        out=self.run_convert()["samples"][0]["results"]["mlkit-fast"]
        self.assertEqual(out,{"status":"FAILED","phase":"first-call"})
    def test_pixels_tamper(self):
        (self.root/(self.sample["id"]+".rgba")).write_bytes(bytes([0,0,0,255]))
        with self.assertRaises(ValueError):self.run_convert()
    def test_bad_geometry_and_time(self):
        for key,value in [("boxes",[[0,0,2,1]]),("sdk_ns",-1),("adapter_ns",True)]:
            before=copy.deepcopy(self.rows);self.rows[0][key]=value
            with self.assertRaises(ValueError):self.run_convert()
            self.rows=before
if __name__=="__main__":unittest.main()

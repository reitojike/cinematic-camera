const {test}=require('node:test');
const assert=require('node:assert/strict');
const c=require('./core.js');
function dataset() {return {schema:1,samples:[{id:'evaluation-0123456789abcdef',sha256:'a'.repeat(64),width:10,height:10,results:Object.fromEntries(c.detectors.map(d=>[d,{status:'SUCCESS',boxes:[[1,1,2,2]],ms:10}]))}]};}
test('unreviewed is not success',()=>{const a=c.aggregate(dataset(),{});assert.equal(a.detectors[0].scored,0);assert.equal(a.detectors[0].unscored,1);assert.equal(a.detectors[0].median_ms,null);});
test('counts and no private IDs exported',()=>{const data=dataset();const a=c.aggregate(data,{[data.samples[0].id]:{'mlkit-fast':{missed:2,falseIndices:[0]}}});assert.equal(a.detectors[0].missed,2);assert.equal(a.detectors[0].false_positive,1);assert.equal(a.detectors[0].median_ms,10);assert.ok(!JSON.stringify(a).includes(data.samples[0].id));assert.ok(!JSON.stringify(a).includes(data.samples[0].sha256));});
test('failed result cannot be labelled',()=>{const d=dataset();d.samples[0].results.yunet.status='FAILED';assert.throws(()=>c.label(d.samples[0].results.yunet,{missed:0,falseIndices:[]}));assert.equal(c.aggregate(d,{}).detectors[2].statuses.FAILED,1);});
test('invalid labels rejected',()=>{const r=dataset().samples[0].results.yunet;for(const l of [{missed:-1,falseIndices:[]},{missed:0,falseIndices:[1]},{missed:0,falseIndices:[0,0]}])assert.throws(()=>c.label(r,l));});
test('duplicate identity and geometry rejected',()=>{const d=dataset();d.samples.push(d.samples[0]);assert.throws(()=>c.validate(d));const e=dataset();e.samples[0].results.yunet.boxes=[[9,9,2,2]];assert.throws(()=>c.validate(e));});
test('absent detector stays explicit',()=>{const d=dataset();delete d.samples[0].results.yunet;assert.throws(()=>c.validate(d));});

test('same pixels cannot inflate sample count',()=>{const d=dataset();d.samples.push({...d.samples[0],id:'evaluation-fedcba9876543210'});assert.throws(()=>c.validate(d),/Duplicate pixels/);});
test('cancelled and never run are distinct from failed',()=>{const d=dataset();d.samples[0].results.yunet.status='NOT_RUN';d.samples[0].results['mlkit-fast'].status='CANCELLED';const a=c.aggregate(d,{});assert.deepEqual(a.detectors[2].statuses,{FAILED:0,CANCELLED:0,NOT_RUN:1});assert.deepEqual(a.detectors[0].statuses,{FAILED:0,CANCELLED:1,NOT_RUN:0});});

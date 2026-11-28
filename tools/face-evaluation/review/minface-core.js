/* Local review contract. No image IO, network or identifiers in aggregate output. */
(function(root) {
  'use strict';
  const detectors = ['accurate-min0.1', 'accurate-min0.05'];
  const conditionIds={'mlkit-16.1.7-ACCURATE-min0.1-no-tracking':'accurate-min0.1','mlkit-16.1.7-ACCURATE-min0.05-no-tracking':'accurate-min0.05'};
  function storageKey(hash){if(!/^[0-9a-f]{64}$/.test(hash))throw Error('Invalid data hash');return 'face-minsize-review-v1-'+hash;}
  function validate(data) {
    if (!data || data.schema !== 'face-minsize-comparison-v1' || !Array.isArray(data.samples) || !data.samples.length || data.samples.length > 64) throw Error('Invalid dataset');
    if(data.scope!=='diagnostic-unscored-only' || data.adoption!=='UNDECIDED' || !data.condition_ids || Object.keys(data.condition_ids).length!==2 || Object.entries(conditionIds).some(([k,v])=>data.condition_ids[k]!==v))throw Error('Invalid condition identity');
    if(data.timing_contract !== 'phase-separated-sdk-adapter-v1') throw Error('Unknown timing contract');
    const ids = new Set(), hashes = new Set();
    for (const s of data.samples) {
      if (!/^evaluation-[0-9a-f]{16}$/.test(s.id) || ids.has(s.id)) throw Error('Invalid sample ID');
      ids.add(s.id);
      if (hashes.has(s.sha256)) throw Error('Duplicate pixels');
      hashes.add(s.sha256);
      if (!/^[0-9a-f]{64}$/.test(s.sha256) || !Number.isInteger(s.width) || !Number.isInteger(s.height) || s.width < 1 || s.height < 1 || s.width * s.height > 4194304) throw Error('Invalid pixels');
      if(!s.conditions || Object.keys(s.conditions).length!==2 || Object.keys(s.conditions).some(k=>!detectors.includes(k)))throw Error('Invalid condition keys');
      for (const d of detectors) {
        const r = s.conditions[d];
        if (!r || !['SUCCESS','FAILED','CANCELLED','NOT_RUN'].includes(r.status)) throw Error('Missing detector status');
        if(data.timing_contract && (!['first-call','warm'].includes(r.phase) || (r.status==='SUCCESS' && (!Number.isFinite(r.adapter_ms) || r.adapter_ms<0)))) throw Error('Invalid timing contract');
        if (r.status !== 'SUCCESS') continue;
        if (!Number.isFinite(r.ms) || r.ms < 0 || !Array.isArray(r.boxes)) throw Error('Invalid result');
        for (const b of r.boxes) if (!Array.isArray(b) || b.length !== 4 || !b.every(Number.isFinite) || b[2] <= 0 || b[3] <= 0) throw Error('Invalid box');
      }
    }
    return data;
  }
  // Display only: keep detector coordinates and indices unchanged for human scoring.
  function clipBox(b,width,height) {
    const x=Math.max(0,Math.min(width,b[0])), y=Math.max(0,Math.min(height,b[1]));
    const right=Math.max(0,Math.min(width,b[0]+b[2])), bottom=Math.max(0,Math.min(height,b[1]+b[3]));
    return right>x && bottom>y ? [x,y,right-x,bottom-y] : null;
  }
  function label(result, value) {
    if (result.status !== 'SUCCESS' || !value || !Number.isInteger(value.missed) || value.missed < 0 || value.missed > 1000 || !Array.isArray(value.falseIndices) || new Set(value.falseIndices).size !== value.falseIndices.length || !value.falseIndices.every(i => Number.isInteger(i) && i >= 0 && i < result.boxes.length)) throw Error('Invalid human label');
    return {missed:value.missed, falseIndices:[...value.falseIndices]};
  }
  function aggregate(data, labels) {
    validate(data);
    if(!labels || typeof labels!=='object' || Array.isArray(labels))throw Error('Invalid labels');
    const ids=new Set(data.samples.map(s=>s.id));
    for(const [id,values] of Object.entries(labels)){if(!ids.has(id)||!values||typeof values!=='object'||Array.isArray(values)||Object.keys(values).some(k=>!detectors.includes(k)))throw Error('Labels from other conditions');}
    return {schema:'face-minsize-aggregate-v1', scope:'human-static-minsize-only', adoption:'UNDECIDED', conditions:detectors.map(d => {
      let scored=0, unscored=0, detected=0, missed=0, falsePositive=0; const statuses={FAILED:0,CANCELLED:0,NOT_RUN:0};
      const times=[]; const phases={'first-call':{sdk:[],adapter:[]},warm:{sdk:[],adapter:[]}};
      for(const s of data.samples) {
        const r=s.conditions[d];
        if(r.status !== 'SUCCESS') {statuses[r.status]++; continue;}
        const l=labels[s.id]?.[d];
        if(!l) {unscored++; continue;}
        label(r,l); scored++; detected+=r.boxes.length; missed+=l.missed; falsePositive+=l.falseIndices.length; if(data.timing_contract){phases[r.phase].sdk.push(r.ms);phases[r.phase].adapter.push(r.adapter_ms);}else times.push(r.ms);
      }
      times.sort((a,b)=>a-b);
      const n=times.length; const median=v=>{v.sort((a,b)=>a-b);return v.length?(v[Math.floor((v.length-1)/2)]+v[Math.floor(v.length/2)])/2:null;};
      return {condition:d,scored,unscored,statuses,detected,missed,false_positive:falsePositive,timing_by_phase:data.timing_contract?Object.fromEntries(Object.entries(phases).map(([k,v])=>[k,{n:v.sdk.length,median_sdk_ms:median(v.sdk),median_adapter_ms:median(v.adapter)}])):undefined,median_ms:n ? (times[Math.floor((n-1)/2)]+times[Math.floor(n/2)])/2 : null};
    })};
  }
  const api={detectors,conditionIds,storageKey,validate,label,aggregate,clipBox};
  if(typeof module !== 'undefined') module.exports=api;
  else root.FaceMinReview=api;
})(globalThis);

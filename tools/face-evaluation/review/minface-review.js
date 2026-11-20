'use strict';
const $=id=>document.getElementById(id), C=FaceMinReview;
let data=null, key='', labels={}, index=0, files=new Map(), generation=0, verified=false, datasetLoad=0;
const status=t=>$('status').textContent=t;
const hash=async bytes=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');
function persist(next) {localStorage.setItem(key,JSON.stringify(next));labels=next;}
function current(){const s=data.samples[index];return [s,$('detector').value,s.conditions[$('detector').value]];}
async function render(){
 const g=++generation;verified=false;$('save').disabled=true;$('clear').disabled=true;$('boxes').replaceChildren();$('canvas').width=0;$('canvas').height=0;
 if(!data)return;
 const [s,d,r]=current();$('position').textContent=`${index+1} / ${data.samples.length}`;$('prev').disabled=index===0;$('next').disabled=index===data.samples.length-1;
 const l=labels[s.id]?.[d];$('missed').value=l?.missed??0;
 if(r.status!=='SUCCESS'){status(`未評価: ${r.status}。失敗を顔なしとして扱いません。`);return;}
 const f=files.get(`${s.id}.rgba`);if(!f){status('対応する RGBA を選んでください。');return;}
 try{
  if(f.size!==s.width*s.height*4)throw Error('画像の長さが一致しません');
  const b=await f.arrayBuffer();if(await hash(b)!==s.sha256)throw Error('画像の SHA が一致しません');
  if(g!==generation)return;
  const pixels=new Uint8ClampedArray(b);for(let i=3;i<pixels.length;i+=4)if(pixels[i]!==255)throw Error('不透明な RGBA が必要です');
  const canvas=$('canvas');canvas.width=s.width;canvas.height=s.height;const ctx=canvas.getContext('2d');ctx.putImageData(new ImageData(pixels,s.width,s.height),0,0);ctx.strokeStyle='#00ff8c';ctx.fillStyle='#00ff8c';ctx.lineWidth=Math.max(2,s.width/400);ctx.font=`${Math.max(16,s.width/40)}px sans-serif`;
  r.boxes.forEach((b,i)=>{const visible=C.clipBox(b,s.width,s.height);if(visible){ctx.strokeRect(...visible);ctx.fillText(String(i+1),visible[0],Math.min(s.height,Math.max(20,visible[1])));}const label=document.createElement('label'),input=document.createElement('input');input.type='checkbox';input.value=i;input.checked=l?.falseIndices.includes(i)??false;label.append(input,document.createTextNode(` 枠 ${i+1} は誤検出`));$('boxes').append(label);});
  verified=true;$('save').disabled=false;$('clear').disabled=false;status(`${r.boxes.length} 個検出 / ${r.ms.toFixed(1)} ms / ${l?'判定済み':'未判定'}。同じ端末のブラウザー内に保存します。`);
 }catch(e){if(g===generation)status(e.message);}
}
$('dataset').onchange=async e=>{
 const token=++datasetLoad, f=e.target.files[0];
 data=null;key='';labels={};verified=false;render();
 if(!f){status('比較JSONを選択してください。');return;}
 try{
  if(f.size>2*1024*1024)throw Error('JSON が大きすぎます');
  const raw=await f.arrayBuffer();if(token!==datasetLoad)return;
  const parsed=C.validate(JSON.parse(new TextDecoder().decode(raw)));
  const digest=await hash(raw);if(token!==datasetLoad)return;
  const k=C.storageKey(digest), saved=JSON.parse(localStorage.getItem(k)||'{}');
  C.aggregate(parsed,saved);data=parsed;key=k;labels=saved;index=0;await render();
 }catch(e){if(token!==datasetLoad)return;data=null;verified=false;await render();if(token===datasetLoad)status('読込不可: '+e.message);}
};
$('pixels').onchange=e=>{files=new Map(Array.from(e.target.files).map(f=>[f.name,f]));render();};
$('prev').onclick=()=>{if(data&&index>0){index--;render();}};$('next').onclick=()=>{if(data&&index<data.samples.length-1){index++;render();}};$('detector').onchange=render;
$('save').onclick=()=>{if(!data||!verified)return;try{if (!($('missed').value.trim()) || !$('missed').checkValidity()) throw Error('見逃し数を明示的に入力してください');const [s,d,r]=current(),l=C.label(r,{missed:Number($('missed').value),falseIndices:Array.from($('boxes').querySelectorAll('input:checked'),x=>Number(x.value))});persist({...labels,[s.id]:{...labels[s.id],[d]:l}});render();}catch(e){status('保存できません: '+e.message);}};
$('clear').onclick=()=>{if(!data||!verified)return;try{const[s,d]=current(),next=structuredClone(labels);if(next[s.id])delete next[s.id][d];persist(next);render();}catch(e){status('変更できません: '+e.message);}};
$('export').onclick=()=>{if(!data)return;try{const summary=C.aggregate(data,labels),url=URL.createObjectURL(new Blob([JSON.stringify(summary,null,2)],{type:'application/json'})),a=document.createElement('a');a.href=url;a.download='face-minsize-aggregate.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}catch(e){status('集計できません: '+e.message);}};
render();

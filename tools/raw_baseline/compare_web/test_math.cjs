"use strict";
const assert = require("node:assert/strict");
const {geometry, anchoredCenter, focusZoom} = require("./app.js");
let cases = 0;
for (const [iw, ih] of [[4042,6064],[6000,4000],[4000,6000]]) {
  for (const [vw,vh] of [[750,700],[300,490],[390,480]]) {
    for (const mode of ["fit","actual"]) for (const zoom of [.1,1,3,64]) {
      for (const [cx,cy] of [[0,0],[.5,.5],[1,1]]) {
        const g=geometry(iw,ih,vw,vh,mode,zoom,cx,cy);
        assert.ok(Number.isFinite(g.scale) && g.scale>0);
        if(g.width>vw) assert.ok(g.left<=0 && g.left>=vw-g.width);
        else assert.ok(Math.abs(g.left-(vw-g.width)/2)<1e-8);
        if(g.height>vh) assert.ok(g.top<=0 && g.top>=vh-g.height);
        else assert.ok(Math.abs(g.top-(vh-g.height)/2)<1e-8);
        if(mode==="actual" && zoom===1) assert.equal(g.scale,1);
        cases++;
      }
    }
    const g=geometry(iw,ih,vw,vh,"actual",2,.5,.5);
    const x=vw*.4,y=vh*.6,factor=1.3;
    const anchor=anchoredCenter(g,vw,vh,x,y,factor);
    const next=geometry(iw,ih,vw,vh,"actual",2*factor,anchor.cx,anchor.cy);
    assert.ok(Math.abs((x-g.left)/g.width-(x-next.left)/next.width)<1e-8);
    assert.ok(Math.abs((y-g.top)/g.height-(y-next.top)/next.height)<1e-8);
    const box=[.3,.35,.6,.55],z=focusZoom(iw,ih,vw,vh,box);
    const focused=geometry(iw,ih,vw,vh,"fit",z,.45,.45);
    assert.ok(focused.width*.3<=vw && focused.height*.2<=vh);
  }
}
console.log(`Coordinate checks passed: ${cases} layout cases plus anchor/focus checks`);

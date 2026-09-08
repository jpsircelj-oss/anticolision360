const monoSpeed=document.getElementById('monoSpeed'),monoHeading=document.getElementById('monoHeading'),monoRisk=document.getElementById('monoRisk'),barL=document.getElementById('barL'),barR=document.getElementById('barR'),barF=document.getElementById('barF'),monoControls=document.getElementById('monoControls'),monoCam=document.getElementById('monoCam'),monoAi=document.getElementById('monoAi');

function zOf(b,m){
  const x=(b.x+b.w/2)/Math.max(1,innerWidth);
  if(m?.sideBias&&x>.28&&x<.72)return x<.5?'left':'right';
  return x<.34?'left':x>.66?'right':'front';
}
function rk(l){return l==='ALTO'?2:l==='MEDIO'?1:0}
function sb(el,r){el.className='rbar'+(!r||r.level==='BAJO'?'':r.level==='ALTO'?' high':' med')}

motionFor=function(cls,b,used){
  const now=performance.now()/1000,sw=Math.max(1,innerWidth),sh=Math.max(1,innerHeight),cx=b.x+b.w/2,cy=b.y+b.h/2;
  const scale=Math.sqrt(Math.max(.000001,(b.w*b.h)/(sw*sh)));
  tracks=tracks.filter(t=>now-t.last<1.7);
  let best=null,bestD=1e9;
  for(const t of tracks){
    if(t.cls!==cls||used.has(t.id))continue;
    const dx=(cx-t.cx)/sw,dy=(cy-t.cy)/sh,d=Math.hypot(dx,dy);
    if(d<.24&&d<bestD){best=t;bestD=d}
  }
  if(!best){
    best={id:nextTrackId++,cls,cx,cy,scale,last:now,growth:0,ttc:Infinity,seen:1,age:0,vx:0,vy:0,sideBias:false,opening:false};
    tracks.push(best);used.add(best.id);return best;
  }
  const dt=now-best.last;
  if(dt>.06&&dt<1.5&&best.scale>.00001){
    const vx=((cx-best.cx)/sw)/dt,vy=((cy-best.cy)/sh)/dt;
    const raw=(scale-best.scale)/(best.scale*dt);
    best.vx=(best.vx||0)*.66+vx*.34;
    best.vy=(best.vy||0)*.66+vy*.34;
    best.growth=(best.growth||0)*.68+raw*.32;
    best.ttc=best.growth>.02?1/best.growth:Infinity;
    best.sideBias=Math.abs(best.vx)>.055&&Math.abs(best.vx)>Math.abs(best.vy)*.90&&best.growth<.05;
    best.opening=best.growth<-.018;
    best.age=(best.age||0)+dt;
  }
  best.cx=cx;best.cy=cy;best.scale=scale;best.last=now;best.seen=(best.seen||1)+1;used.add(best.id);return best;
};

assessRisk=function(b,cls,motion){
  const sw=Math.max(1,innerWidth),sh=Math.max(1,innerHeight),cx=b.x+b.w/2,bottom=b.y+b.h;
  const center=Math.abs(cx-sw/2)/(sw/2),area=(b.w*b.h)/(sw*sh),kmh=Number.isFinite(gps.speed)?Math.max(0,gps.speed*3.6):0;
  const thr=adaptiveThresholds(),ttc=Number.isFinite(motion.ttc)?motion.ttc:Infinity,closing=Number.isFinite(motion.growth)?motion.growth:0;
  const frontCore=center<.18,frontCorridor=center<.30,veryClose=area>.15&&bottom>sh*.84,nearSide=bottom>sh*.76&&area>.018;
  const twoWheel=cls==='bicycle'||cls==='motorcycle';
  const sameDirectionLike=twoWheel&&motion.seen>=3&&frontCorridor&&(motion.sideBias||motion.opening||closing<.028)&&ttc>thr.redTTC;
  let level='BAJO';

  if(kmh<5){
    if(frontCore&&veryClose&&closing>.022&&ttc<4.5)level='MEDIO';
  }else if(vehicles.has(cls)){
    if(!sameDirectionLike&&frontCore&&!motion.sideBias&&closing>.050&&ttc<=thr.redTTC&&(area>.028||bottom>sh*.70))level='ALTO';
    else if(!sameDirectionLike&&frontCore&&!motion.sideBias&&veryClose&&motion.seen>=4&&closing>.028)level='ALTO';
    else if(!sameDirectionLike&&frontCorridor&&!motion.sideBias&&closing>.020&&ttc<=thr.yellowTTC)level='MEDIO';
    else if(twoWheel&&nearSide&&!motion.opening&&!frontCore)level='MEDIO';
  }else if(vulnerable.has(cls)){
    const priority=sidePriority(b,cls);
    if(frontCore&&!motion.sideBias&&((ttc<=thr.redTTC&&closing>.030)||veryClose&&closing>.022))level='ALTO';
    else if((priority&&nearSide)||(frontCorridor&&ttc<=thr.yellowTTC&&closing>.016))level='MEDIO';
  }else{
    if(frontCore&&veryClose&&closing>.032)level='ALTO';
    else if(frontCorridor&&closing>.022&&ttc<=thr.yellowTTC)level='MEDIO';
  }

  if(sameDirectionLike&&level!=='BAJO')level='BAJO';
  if(motion.opening&&level==='MEDIO')level='BAJO';

  const score=(level==='ALTO'?100:level==='MEDIO'?50:10)+(frontCore?8:frontCorridor?4:0)+(Number.isFinite(ttc)?Math.max(0,20-ttc):0)+Math.max(0,closing*100);
  const color=level==='ALTO'?'rgba(225,52,64,.98)':level==='MEDIO'?'rgba(239,179,22,.98)':'rgba(143,150,157,.55)';
  return{level,color,score,ttc,closing,area,bottom,sideBias:!!motion.sideBias,sameDirectionLike,thresholds:thr};
};

let v61HwResetPending=false;
updateZoom=function(){
  zoomHoldUntil=0;testZoomUntil=0;
  if(zoomOn)resetDigitalZoom();
  if(hwZoomOn&&!v61HwResetPending){v61HwResetPending=true;Promise.resolve(setHardwareZoom(false)).finally(()=>v61HwResetPending=false)}
  return false;
};
if(testZoomBtn)testZoomBtn.disabled=true;

marker=function(b,r){
  const x=b.x,y=b.y,w=b.w,h=b.h,l=Math.max(9,Math.min(22,Math.min(w,h)*.18));
  ctx.save();
  ctx.strokeStyle=r.level==='ALTO'?'rgba(20,24,29,.96)':r.level==='MEDIO'?'rgba(62,68,75,.82)':'rgba(100,107,115,.56)';
  ctx.lineWidth=r.level==='ALTO'?2.8:r.level==='MEDIO'?2.1:1.3;ctx.lineCap='round';ctx.beginPath();
  ctx.moveTo(x,y+l);ctx.lineTo(x,y);ctx.lineTo(x+l,y);
  ctx.moveTo(x+w-l,y);ctx.lineTo(x+w,y);ctx.lineTo(x+w,y+l);
  ctx.moveTo(x+w,y+h-l);ctx.lineTo(x+w,y+h);ctx.lineTo(x+w-l,y+h);
  ctx.moveTo(x+l,y+h);ctx.lineTo(x,y+h);ctx.lineTo(x,y+h-l);ctx.stroke();ctx.restore();
};

// Geometry is stored in the launcher's 1920×1080 design space, displayed in output pixels.
export const pixelRatio = resolution => Number(resolution || 1080) / 1080;
export function snapBox(box, guides, boxes, threshold, grid, interact) {
 const result={x:box.x,y:box.y,guideX:null,guideY:null};
 for(const [axis,size,limit] of [['x','width',1920],['y','height',1080]]){
  const targets=[0,limit/2,limit,...guides.filter(g=>g.axis===axis).map(g=>g.position)];
  for(const other of boxes)targets.push(other[axis],other[axis]+other[size]/2,other[axis]+other[size]);
  let best=threshold,delta=null,line=null;
  for(const anchor of [0,box[size]/2,box[size]])for(const target of targets){const d=target-(box[axis]+anchor);if(Math.abs(d)<best){best=Math.abs(d);delta=d;line=target;}}
  if(delta!==null){result[axis]+=delta;result[axis==='x'?'guideX':'guideY']=line;}
  else if(grid){const snapped=interact.snappers.grid({x:grid,y:grid})(box.x,box.y);result[axis]=snapped[axis];}
 }
 return result;
}
export function distance(a,b,axis){
 const size=axis==='x'?'width':'height',cross=axis==='x'?'y':'x',crossSize=axis==='x'?'height':'width';
 const after=a[axis]+a[size]/2>=b[axis]+b[size]/2;
 const start=after?b[axis]+b[size]:a[axis]+a[size],end=after?a[axis]:b[axis];
 const lo=Math.max(a[cross],b[cross]),hi=Math.min(a[cross]+a[crossSize],b[cross]+b[crossSize]);
 return {axis,after,start,end,gap:end-start,cross:(lo+hi)/2};
}
export function setDistance(a,b,axis,gap){const size=axis==='x'?'width':'height',after=distance(a,b,axis).after;return after?b[axis]+b[size]+gap:b[axis]-a[size]-gap;}

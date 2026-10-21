const S=require('./sheet');
const WIDTH=794,HEIGHT=1123,SCALE=3;
function render(ctx,doc){
 ctx.fillStyle='#fff';ctx.fillRect(0,0,WIDTH,HEIGHT);
 const x=44,w=706,c=[x,x+74,x+154,x+214,x+316,x+416,x+506,x+w];
 function text(value,left,top,width,height,size=14,bold=false,color='#17202b',align='center'){
  const str=String(value==null?'':value);if(!str)return;
  ctx.font=(bold?'bold ':'')+size+'px "SimSun", "Songti SC", serif';
  let lines=[];for(const paragraph of str.split('\n')){let line='';for(const char of paragraph){if(line&&ctx.measureText(line+char).width>width-10){lines.push(line);line=char;}else line+=char;}lines.push(line);}
  const lineHeight=size*1.25;if(lines.length*lineHeight>height-4&&size>9)return text(value,left,top,width,height,size-0.5,bold,color,align);
  ctx.save();ctx.beginPath();ctx.rect(left+2,top+2,width-4,height-4);ctx.clip();ctx.fillStyle=color;ctx.textAlign=align;ctx.textBaseline='middle';
  lines.forEach((line,i)=>ctx.fillText(line,align==='left'?left+6:left+width/2,top+height/2+(i-(lines.length-1)/2)*lineHeight));ctx.restore();
 }
 function line(x1,y1,x2,y2,outer=false){ctx.beginPath();ctx.strokeStyle=outer?'#101010':'#777';ctx.lineWidth=outer?1.6:0.65;ctx.setLineDash(outer?[]:[1.5,1.5]);ctx.moveTo(x1,y1);ctx.lineTo(x2,y2);ctx.stroke();ctx.setLineDash([]);}
 function cell(left,top,width,height,value,size=14,bold=false){line(left,top,left+width,top);line(left,top,left,top+height);line(left+width,top,left+width,top+height);line(left,top+height,left+width,top+height);text(value,left,top,width,height,size,bold);}
 function band(label,y,height){ctx.fillStyle='#a38780';ctx.fillRect(x,y,w,height);text(label,x,y,w,height,15,true,'#fff');}
 function options(items,left,top,width,height,size=11,columns=items.length){const count=Math.ceil(items.length/columns),rowHeight=height/count,colWidth=width/columns;items.forEach((item,index)=>{const cx=left+(index%columns+0.5)*colWidth,cy=top+(Math.floor(index/columns)+0.5)*rowHeight;ctx.font=size+'px "SimSun", "Songti SC", serif';const labelWidth=ctx.measureText(item.label).width,bx=cx+(labelWidth+14)/2-10; text(item.label,cx-(labelWidth+14)/2-6,cy-rowHeight/2,labelWidth+12,rowHeight,size);ctx.strokeStyle='#333';ctx.lineWidth=0.8;ctx.strokeRect(bx,cy-4.5,9,9);if(item.checked){ctx.beginPath();ctx.moveTo(bx+1.5,cy);ctx.lineTo(bx+4,cy+3);ctx.lineTo(bx+8,cy-3);ctx.stroke();}});}
 const selected=(values,value)=>values.map(label=>({label,checked:label===value}));
 text(doc.title+' V'+doc.version,x,17,w,22,14,true);
 let y=52;const start=y;band('客户基本信息',y,40);y+=40;
 cell(c[0],y,74,56,'业主信息',14,true);cell(c[1],y,80,28,'业主姓名');cell(c[2],y,162,28,doc.name);cell(c[4],y,100,28,'联系方式');cell(c[5],y,290,28,doc.phone);y+=28;
 cell(c[1],y,80,28,'地址');cell(c[2],y,w-154,28,doc.address);y+=28;
 cell(c[0],y,74,56,'宅基地尺寸',14,true);cell(c[1],y,80,28,'面宽（米）',12);cell(c[2],y,60,28,doc.form.landWidth);cell(c[1],y+28,80,28,'进深（米）',12);cell(c[2],y+28,60,28,doc.form.landDepth);
 cell(c[3],y,102,56,'拟建筑规模',14);cell(c[4],y,100,28,'面宽（米）',12);cell(c[5],y,90,28,doc.width);cell(c[6],y,200,28,'建筑面积 '+doc.area+'㎡',12);
 cell(c[4],y+28,100,28,'进深（米）',12);cell(c[5],y+28,90,28,doc.depth);cell(c[6],y+28,200,28,doc.floors?doc.floors+'层':'',12);y+=56;
 cell(c[0],y,74,44,'屋檐尺寸\n要求',14,true);cell(c[1],y,242,44,'左右两侧屋檐：'+(doc.form.eaveSides||'____')+' mm\n前后屋檐：'+(doc.form.eaveFront||'____')+' mm',12);
 cell(c[4],y,100,44,'结构形式');cell(c[5],y,290,44,'');options(selected(['砖混结构','框剪结构','框架结构'],doc.form.structure),c[5],y,290,44);y+=44;
 cell(c[0],y,74,44,'基础选型',14,true);cell(c[1],y,242,44,'');options([...selected(['筏板基础','独立基础','桩基础','条形基础'],doc.form.foundation),{label:'是否打桩',checked:doc.form.piling}],c[1],y,242,44,10,3);
 cell(c[4],y,100,44,'是否要露台');cell(c[5],y,290,44,'');options(selected(['是','否'],doc.form.terrace),c[5],y,290,44,13);y+=44;
 cell(c[0],y,74,44,'外观造型\n风格',14,true);cell(c[1],y,242,44,'');options(selected(['现代','新中式','简欧','中式'],doc.form.style),c[1],y,242,44);
 cell(c[4],y,100,44,'空间需求');cell(c[5],y,290,44,'');options(['doubleHeight','spiralStair','tripleStair'].map(k=>({label:S.options.find(o=>o[0]===k)[1],checked:doc.form[k]})),c[5],y,290,44);y+=44;
 band('报价明细',y,26);y+=26;
 const head=y;['分部工程','名称','面积','单价','合价','备注'].forEach((v,i)=>cell(c[i+1],y,c[i+2]-c[i+1],28,v,13));y+=28;
 function rows(label,items){const top=y;for(const r of items){cell(c[1],y,80,30,r.label,13);cell(c[2],y,60,30,r.name,11);cell(c[3],y,102,30,r.quantity,12);cell(c[4],y,100,30,r.unitPrice,12,true);cell(c[5],y,90,30,r.cents==null?'':S.money(r.cents),12,true);cell(c[6],y,200,30,r.note,11);y+=30;}cell(c[0],label==='主体工程'?head:top,74,y-(label==='主体工程'?head:top),label,14,true);}
 function subtotal(cents){cell(c[0],y,74,28,'小计',14,true);cell(c[1],y,342,28,'');cell(c[5],y,290,28,'¥ '+S.money(cents),14,true);y+=28;}
 rows('主体工程',doc.main);subtotal(doc.mainTotal);rows('装饰工程',doc.decor);subtotal(doc.decorTotal);
 cell(c[0],y,74,30,'保险费用',14,true);cell(c[1],y,140,30,'人身安全生产险',12);cell(c[3],y,202,30,doc.insurance===null?'':S.money(doc.insurance),13,true);cell(c[5],y,290,30,doc.insurance===null?'未单列，按原报价范围执行':'详情咨询中国人保',12);y+=30;
 cell(c[0],y,74,30,'管理费用',14,true);cell(c[1],y,256,30,doc.management===null?'':S.money(doc.management),13,true);cell(x+330,y,w-330,30,doc.management===null?'未单列，未另加收3%管理费':'按原报价约定计取',12);y+=30;
 cell(c[0],y,74,32,'总计价款',14,true);cell(c[1],y,w-74,32,'人民币 ¥ '+S.money(doc.total)+' 元',17,true);y+=32;
 line(x,start,x+w,start,true);line(x,y,x+w,y,true);line(x,start,x,y,true);line(x+w,start,x+w,y,true);
 text(doc.basis+'  |  '+(doc.date||'日期未记录')+'  |  '+(doc.demo?'模拟计价，非正式对外报价':doc.kind==='quote'?(doc.approved?'本版报价已批准':'本版报价待老板审批'):''),x,y+10,w,25,11,false,'#555','left');
 text('空白项表示尚未录入或未单独报价；汇总项目按原计价范围执行，各项合计与原记录一致。',x,y+35,w,25,10,false,'#555','left');
 text('项目：'+doc.projectId,x,y+60,w,22,9,false,'#777','left');
 return {width:WIDTH,height:HEIGHT,bottom:y};
}
module.exports={render,WIDTH,HEIGHT,SCALE};

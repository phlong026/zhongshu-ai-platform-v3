const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const Canvas=require(process.env.QUOTE_EXPORT_NODE_MODULES?path.join(process.env.QUOTE_EXPORT_NODE_MODULES,'@napi-rs/canvas'):'@napi-rs/canvas');
const S=require('../miniprogram/pages/workflow/quote-export/sheet'),R=require('../miniprogram/pages/workflow/quote-export/render'),PDF=require('../miniprogram/pages/workflow/quote-export/pdf');
const out=path.resolve('qa/quote-export-20261020');fs.mkdirSync(out,{recursive:true});
if(fs.existsSync('C:/Windows/Fonts/simsun.ttc'))Canvas.GlobalFonts.registerFromPath('C:/Windows/Fonts/simsun.ttc','SimSun');
const fixture=require('../qa/design-coordination-20261020/revision-state.json'),entry=require('../qa/design-coordination-20261020/budget-entry-state.json');
for(const [kind,c]of [['budget',entry.customer],['quote',fixture]]){
 const r=c[kind==='budget'?'budgets':'quotes'].at(-1),doc=S.build(c,kind,r.version,{landWidth:'16',landDepth:'18',county:'江夏区',town:'五里界街道',village:'样张测试村',eaveSides:'800',eaveFront:'1000',foundation:'独立基础',structure:'框架结构',terrace:'是',style:'新中式',doubleHeight:true});doc.demo=true;doc.approved=true;
 const canvas=Canvas.createCanvas(R.WIDTH*R.SCALE,R.HEIGHT*R.SCALE),ctx=canvas.getContext('2d');ctx.scale(R.SCALE,R.SCALE);const size=R.render(ctx,doc);assert(size.bottom<R.HEIGHT-90);
 const jpeg=canvas.toBuffer('image/jpeg',98),pdf=Buffer.from(PDF.pdf(jpeg,R.WIDTH*R.SCALE,R.HEIGHT*R.SCALE));fs.writeFileSync(path.join(out,kind+'-sheet.png'),canvas.toBuffer('image/png'));fs.writeFileSync(path.join(out,kind+'-sheet.pdf'),pdf);fs.writeFileSync(path.join(out,kind+'-document.json'),JSON.stringify(doc,null,2));console.log(kind+' PDF '+pdf.length+' bytes, PNG '+canvas.width+'x'+canvas.height);
}

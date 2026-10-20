// Document data is derived only from the saved version. Layout metadata never changes prices.
const amount=n=>{const v=Number(n);if(!Number.isFinite(v)||v<0)throw Error('报价金额无效，请核对原记录');return Math.round(v*100);};
const money=n=>(n/100).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g,',');
const fields=[['county','县／区'],['town','乡／镇'],['village','村及详细地址'],['landWidth','宅基地面宽（米）'],['landDepth','宅基地进深（米）'],['eaveSides','左右两侧屋檐（毫米）'],['eaveFront','前后屋檐（毫米）']];
const choices=[{key:'foundation',label:'基础选型',values:['未填写','筏板基础','独立基础','桩基础','条形基础']},{key:'structure',label:'结构形式',values:['未填写','砖混结构','框剪结构','框架结构']},{key:'terrace',label:'是否要露台',values:['未填写','是','否']},{key:'style',label:'外观造型风格',values:['未填写','现代','新中式','简欧','中式']}];
const options=[['doubleHeight','挑空客厅'],['spiralStair','旋转楼梯'],['tripleStair','三跑楼梯'],['piling','需要打桩']];
function clean(f={}){const out={};for(const [key]of fields){const v=String(f[key]||'').trim();if(v.length>(key==='village'?36:16))throw Error('补充信息过长，请缩短后生成');if(['landWidth','landDepth','eaveSides','eaveFront'].includes(key)&&v&&(!/^\d+(\.\d{1,2})?$/.test(v)||Number(v)>100000||key.startsWith('land')&&Number(v)<=0))throw Error('请填写有效尺寸，最多两位小数');out[key]=v;}for(const p of choices){const v=String(f[p.key]||'');if(v&&v!=='未填写'&&!p.values.includes(v))throw Error('请选择有效的'+p.label);out[p.key]=v==='未填写'?'':v;}for(const [key]of options)out[key]=f[key]===true;return out;}
function quoteBasis(c,r){const b=(c.budgets||[]).find(b=>b.version===r.budgetVersion);return b?.parameters||null;}
function defaults(c,kind,r){const p=kind==='budget'?r.parameters||{}:(r.measurementVersion?(c.measurements||[]).find(m=>m.version===r.measurementVersion)||{}:quoteBasis(c,r)||{}),style=(c.facades||[]).find(f=>f.version===r.facadeVersion)?.style||p.style||'';return {foundation:p.foundation||'',structure:p.structure||'',style:style==='现代简约'?'现代':style};}
function record(c,kind,version){if(!['budget','quote'].includes(kind))throw Error('请选择预算或正式报价');const r=(kind==='budget'?c.budgets:c.quotes)?.find(r=>String(r.version)===String(version));if(!r)throw Error('报价版本不存在或无权查看，请返回客户档案');return r;}
function row(label,r={},note=''){return {label,name:r.name||r.label||'',quantity:r.quantity==null?'':String(r.quantity),unitPrice:r.unitPrice==null?'':money(amount(r.unitPrice)),cents:r.value==null?null:amount(r.value),note:note||r.note||''};}
function build(c,kind,version,metadata={}){
 const r=record(c,kind,version),basis=Object.fromEntries(Object.entries(defaults(c,kind,r)).filter(([,v])=>v)),f=clean({...metadata,...basis}),main=['基础','主体工程','天沟','屋面结构','其他'].map(s=>row(s)),decor=['门','窗','欧式','外墙涂料','防水','栏杆','文化石','灯具','防雷','其他'].map(s=>row(s));
 let insurance=null,management=null;
 const put=(rows,index,source,note)=>{if(rows[index].cents!==null){index=rows.findIndex(r=>r.cents===null&&r.label==='其他');if(index<0)throw Error('报价分项超出模板容量，请先整理原报价');}rows[index]=row(rows[index].label,source,note);};
 if(kind==='budget'&&r.bodyRows?.length){for(const source of r.bodyRows){const label=source.label||'';put(main,/基础/.test(label)?0:/结构/.test(label)?1:/屋顶|屋面/.test(label)?3:/天沟/.test(label)?2:4,source);}for(const source of r.exteriorRows||[]){const label=source.label||'';if(/保险/.test(label)){insurance=amount(source.value);continue;}put(decor,/门窗|^门/.test(label)?0:/^窗/.test(label)?1:/墙漆|涂料/.test(label)?3:/防水/.test(label)?4:/栏杆/.test(label)?5:/文化石/.test(label)?6:/灯/.test(label)?7:/防雷/.test(label)?8:/欧式/.test(label)?2:9,source,/门窗/.test(label)?'门窗合并计价，未拆分':'');}}
 else {main[1]=row('主体工程',{name:'主体工程（汇总）',quantity:r.area,unitPrice:r.unitPrice,value:r.main},'按原报价汇总口径');decor[9]=row('其他',{name:kind==='quote'?'屋面门窗（汇总）':'外装工程（汇总）',value:r.exterior},'按原报价汇总口径，未拆分');}
 if(r.other!=null&&amount(r.other)>0)put(main,4,{name:'其他约定项目',value:r.other},'原报价其他约定项目');
 if(r.management!=null)management=amount(r.management);
 const sum=rows=>rows.reduce((s,r)=>s+(r.cents||0),0),mainTotal=sum(main),decorTotal=sum(decor),total=amount(r.total);
 if(mainTotal+decorTotal+(insurance||0)+(management||0)!==total)throw Error('原报价分项与总价不一致，请先核对报价后再导出');
 const p=kind==='budget'?r.parameters||{}:(r.measurementVersion?(c.measurements||[]).find(m=>m.version===r.measurementVersion)||{}:quoteBasis(c,r)||{}),address=[c.province,c.city,f.county,f.town,f.village].filter(Boolean).join(' ');
 return {kind,version:r.version,date:r.date||'',name:c.name||'',phone:c.phone||'',projectId:c.projectId||c.id,address,form:f,width:p.width||'',depth:p.depth||'',area:r.area||p.area||'',floors:p.floors||'',main,decor,mainTotal,decorTotal,insurance,management,total,title:kind==='budget'?'建造参考预算':'正式建造报价',basis:kind==='budget'?'前期预算，非施工结算依据':r.measurementVersion?'实测资料 V'+r.measurementVersion:'预算参数 V'+(r.budgetVersion||'—')+' · 已选AI方案',demo:/^demo/.test(r.priceVersion||'')||r.sample===true,priceVersion:r.priceVersion||''};
}
function filename(doc,extension){const id=String(doc.projectId||'project').replace(/[^a-zA-Z0-9_-]/g,'_').slice(-24);return (doc.name+'_'+doc.title+'_V'+doc.version).replace(/[\\/:*?"<>|\x00-\x1f]/g,'_').slice(0,54)+'_'+id+'.'+extension;}
module.exports={fields,choices,options,clean,defaults,record,build,money,filename};

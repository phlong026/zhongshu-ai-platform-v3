// 进度计划 Excel 导入：只认约定格式的 xlsx，A列节点名称、B列开始日期、C列结束日期，
// 最多20行；解析为可逐项调整的节点清单。缺封顶验收（主体封顶）节点时自动补在最后并提示，
// 因为开工准备保存必须有封顶里程碑。
const XLSX=require('./vendor/xlsx.mini.min');
const MAX=20,pad=n=>(n<10?'0':'')+n,addDay=(base,n)=>{const x=new Date(base+'T12:00:00');x.setDate(x.getDate()+n);return new Date(x.getTime()-x.getTimezoneOffset()*60000).toISOString().slice(0,10);};
// 日期只认两种可靠来源：单元格是日期格式（解析为Date），或文本"2026-11-1/2026年11月1日"等；
// 纯数字拒绝，避免把"30天"这类工期误判成1900年的序列日期
function asDate(value,row,name){
 if(value instanceof Date&&value.getFullYear()>1990)return value.getFullYear()+'-'+pad(value.getMonth()+1)+'-'+pad(value.getDate());
 const s=String(value==null?'':value).trim(),m=s.match(/^(\d{4})[-/年.](\d{1,2})[-/月.](\d{1,2})日?$/);
 if(m)return m[1]+'-'+pad(+m[2])+'-'+pad(+m[3]);
 if(/^\d+(\.\d+)?$/.test(s))throw Error('第'+row+'行“'+name+'”是数字，请把日期列设为日期格式，或改填 2026-11-01 这样的文本日期');
 throw Error('第'+row+'行“'+name+'”无法识别为日期：'+(s||'（空）'));
}
function importNodes(buffer){
 if(buffer instanceof ArrayBuffer)buffer=new Uint8Array(buffer);
 if(!(buffer instanceof Uint8Array)||buffer.length<4||buffer[0]!==0x50||buffer[1]!==0x4b||buffer[2]!==0x03||buffer[3]!==0x04)throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');
 let rows;try{const wb=XLSX.read(buffer,{type:'array',cellDates:true}),ws=wb.Sheets[wb.SheetNames[0]];rows=XLSX.utils.sheet_to_json(ws,{header:1,raw:true,defval:''}).filter(r=>r.some(c=>String(c==null?'':c).trim()));}
 catch(e){throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');}
 if(!rows.length)throw Error('表格没有内容，请按“节点名称、开始日期、结束日期”三列填写');
 const looksHeader=r=>/节点|名称|日期|开始|结束/.test(String(r[0]==null?'':r[0])+String(r[1]==null?'':r[1])+String(r[2]==null?'':r[2]));
 const header=looksHeader(rows[0]),list=header?rows.slice(1):rows,firstRow=header?2:1;
 if(!list.length)throw Error('没有找到数据行，首行请填表头或直接从节点名称开始');
 if(list.length>MAX)throw Error('数据最多'+MAX+'行，当前'+list.length+'行，请精简后重新导入');
 const nodes=[];for(const[i,row]of list.entries()){const rowNo=firstRow+i,name=String(row[0]==null?'':row[0]).trim();if(!name)throw Error('第'+rowNo+'行缺少节点名称');const start=asDate(row[1],rowNo,'开始日期'),end=asDate(row[2],rowNo,'结束日期');if(end<start)throw Error('第'+rowNo+'行“'+name+'”结束日期早于开始日期');nodes.push({id:'n'+(i+1),name,start,end,status:'待开工',milestone:name.includes('封顶')?'topping':''});}
 let notice='';if(!nodes.some(n=>n.milestone==='topping')){const last=nodes[nodes.length-1];nodes.push({id:'n'+(nodes.length+1),name:'封顶验收',start:addDay(last.end,1),end:addDay(last.end,1),status:'待开工',milestone:'topping'});notice='已自动补充封顶验收节点，请核对其日期';}
 return {nodes,notice};
}
module.exports={importNodes,MAX};

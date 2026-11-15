// 进度计划 Excel 导入：只认约定格式的 xlsx，A列节点名称、B列开始日期、C列结束日期，
// 最多20行；解析为可逐项调整的节点清单。缺封顶验收（主体封顶）节点时自动补在最后并提示，
// 因为开工准备保存必须有封顶里程碑。
const XLSX=require('./vendor/xlsx.mini.min');
const MAX=20,MAX_FILE_BYTES=2*1024*1024,MAX_EXPANDED_BYTES=8*1024*1024,MAX_ZIP_ENTRIES=128,MAX_COMPRESSION_RATIO=200;
const pad=n=>(n<10?'0':'')+n,addDay=(base,n)=>{const x=new Date(base+'T12:00:00');x.setDate(x.getDate()+n);return new Date(x.getTime()-x.getTimezoneOffset()*60000).toISOString().slice(0,10);};
// 日期只认两种可靠来源：单元格是日期格式（解析为Date），或文本"2026-11-1/2026年11月1日"等；
// 纯数字拒绝，避免把"30天"这类工期误判成1900年的序列日期
function asDate(value,row,name){
 if(value instanceof Date&&value.getFullYear()>1990)return value.getFullYear()+'-'+pad(value.getMonth()+1)+'-'+pad(value.getDate());
 const s=String(value==null?'':value).trim(),m=s.match(/^(\d{4})[-/年.](\d{1,2})[-/月.](\d{1,2})日?$/);
 if(m)return m[1]+'-'+pad(+m[2])+'-'+pad(+m[3]);
 if(/^\d+(\.\d+)?$/.test(s))throw Error('第'+row+'行“'+name+'”是数字，请把日期列设为日期格式，或改填 2026-11-01 这样的文本日期');
 throw Error('第'+row+'行“'+name+'”无法识别为日期：'+(s||'（空）'));
}
// SheetJS 会在筛选工作表前解压 ZIP 中的全部条目。这里按 PKWARE 中央目录先核对声明大小，
// 让 20 行三列表格的压缩体积、条目数和展开量都保持有界，再交给运行库解析。
function inspectArchive(bytes){
 if(bytes.byteLength>MAX_FILE_BYTES)throw Error('进度表文件不能超过2MB');
 if(bytes.byteLength<22||bytes[0]!==0x50||bytes[1]!==0x4b||bytes[2]!==0x03||bytes[3]!==0x04)throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');
 const view=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),min=Math.max(0,bytes.byteLength-22-65535);let end=-1;
 for(let i=bytes.byteLength-22;i>=min;i--)if(view.getUint32(i,true)===0x06054b50){end=i;break;}
 if(end<0)throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');
 const disk=view.getUint16(end+4,true),directoryDisk=view.getUint16(end+6,true),diskEntries=view.getUint16(end+8,true),entries=view.getUint16(end+10,true),directorySize=view.getUint32(end+12,true),directoryOffset=view.getUint32(end+16,true),commentLength=view.getUint16(end+20,true);
 if(disk||directoryDisk||diskEntries!==entries||entries===0||entries===0xffff||directorySize===0xffffffff||directoryOffset===0xffffffff||end+22+commentLength!==bytes.byteLength||directoryOffset+directorySize>end)throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');
 if(entries>MAX_ZIP_ENTRIES)throw Error('进度表内部文件过多，请只保留第一张三列计划表');
 let offset=directoryOffset,expanded=0;
 for(let i=0;i<entries;i++){
  if(offset+46>end||view.getUint32(offset,true)!==0x02014b50)throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');
  const flags=view.getUint16(offset+8,true),method=view.getUint16(offset+10,true),compressed=view.getUint32(offset+20,true),uncompressed=view.getUint32(offset+24,true),nameLength=view.getUint16(offset+28,true),extraLength=view.getUint16(offset+30,true),entryCommentLength=view.getUint16(offset+32,true),entryDisk=view.getUint16(offset+34,true),localOffset=view.getUint32(offset+42,true);
  if(flags&1||entryDisk||localOffset>=directoryOffset||![0,8].includes(method)||compressed===0xffffffff||uncompressed===0xffffffff)throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');
  if(localOffset+30>directoryOffset||view.getUint32(localOffset,true)!==0x04034b50)throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');
  const localFlags=view.getUint16(localOffset+6,true),localMethod=view.getUint16(localOffset+8,true),localCompressed=view.getUint32(localOffset+18,true),localUncompressed=view.getUint32(localOffset+22,true),localNameLength=view.getUint16(localOffset+26,true),localExtraLength=view.getUint16(localOffset+28,true);
  if(localFlags&1||localMethod!==method||localCompressed===0xffffffff||localUncompressed===0xffffffff||(!(localFlags&8)&&(localCompressed!==compressed||localUncompressed!==uncompressed))||localOffset+30+localNameLength+localExtraLength+compressed>directoryOffset)throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');
  if(localUncompressed>MAX_EXPANDED_BYTES)throw Error('进度表展开内容不能超过8MB，请删除无关工作表、图片或超长内容');
  expanded+=uncompressed;
  if(expanded>MAX_EXPANDED_BYTES)throw Error('进度表展开内容不能超过8MB，请删除无关工作表、图片或超长内容');
  if(uncompressed>0&&(compressed===0||uncompressed/compressed>MAX_COMPRESSION_RATIO))throw Error('进度表压缩比异常，请重新导出仅含三列计划的 .xlsx 文件');
  offset+=46+nameLength+extraLength+entryCommentLength;
 }
 if(offset>directoryOffset+directorySize)throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');
}
function importNodes(buffer){
 if(buffer instanceof ArrayBuffer)buffer=new Uint8Array(buffer);
 if(!(buffer instanceof Uint8Array))throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');
 inspectArchive(buffer);
 let rows,ws;try{const wb=XLSX.read(buffer,{type:'array',cellDates:true,sheets:0,sheetRows:MAX+2});ws=wb.Sheets[wb.SheetNames[0]];rows=XLSX.utils.sheet_to_json(ws,{header:1,raw:true,defval:''}).filter(r=>r.some(c=>String(c==null?'':c).trim()));}
 catch(e){throw Error('文件无法解析，请确认为 .xlsx 格式的进度计划表');}
 if(ws['!fullref'])throw Error('首张工作表在读取窗口后仍有内容，请删除空白间隔并确保数据不超过20行');
 if(!rows.length)throw Error('表格没有内容，请按“节点名称、开始日期、结束日期”三列填写');
 const looksHeader=r=>/节点|名称|日期|开始|结束/.test(String(r[0]==null?'':r[0])+String(r[1]==null?'':r[1])+String(r[2]==null?'':r[2]));
 const header=looksHeader(rows[0]),list=header?rows.slice(1):rows,firstRow=header?2:1;
 if(!list.length)throw Error('没有找到数据行，首行请填表头或直接从节点名称开始');
 if(list.length>MAX)throw Error('数据最多'+MAX+'行，当前'+list.length+'行，请精简后重新导入');
 const nodes=[];for(const[i,row]of list.entries()){const rowNo=firstRow+i,name=String(row[0]==null?'':row[0]).trim();if(!name)throw Error('第'+rowNo+'行缺少节点名称');const start=asDate(row[1],rowNo,'开始日期'),end=asDate(row[2],rowNo,'结束日期');if(end<start)throw Error('第'+rowNo+'行“'+name+'”结束日期早于开始日期');nodes.push({id:'n'+(i+1),name,start,end,status:'待开工',milestone:name.includes('封顶')?'topping':''});}
 let notice='';if(!nodes.some(n=>n.milestone==='topping')){const last=nodes[nodes.length-1];nodes.push({id:'n'+(nodes.length+1),name:'封顶验收',start:addDay(last.end,1),end:addDay(last.end,1),status:'待开工',milestone:'topping'});notice='已自动补充封顶验收节点，请核对其日期';}
 return {nodes,notice};
}
module.exports={importNodes,MAX,MAX_FILE_BYTES,MAX_EXPANDED_BYTES,MAX_ZIP_ENTRIES};

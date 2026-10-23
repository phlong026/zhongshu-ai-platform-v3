// Sample attachments are a test aid: they are offered only outside the release build.
const release=()=>{try{return wx.getAccountInfoSync().miniProgram.envVersion==='release';}catch(e){return false;}};
const legacyFileNames={
 '示例附件.pdf':'参考附件.pdf',
 '图稿示例凭据.pdf':'图稿参考凭据.pdf',
 '工程记录示例凭据.pdf':'工程记录参考凭据.pdf',
 '闭环流程示例凭据.pdf':'闭环流程参考凭据.pdf',
 '模拟供应方报价.pdf':'参考供应方报价.pdf'
};
const legacyText={
 '需求已提交（本地演示）':'需求已提交',
 '预置本地模拟跟进':'预置客户跟进记录',
 '本地演示已记录':'发送记录已保存'
};
const owns=(record,key)=>Object.prototype.hasOwnProperty.call(record,key);
function displayFile(file){
 if(!file||file.sample!==true||!owns(legacyFileNames,file.name))return file;
 return {...file,name:legacyFileNames[file.name]};
}
const displayText=value=>owns(legacyText,value)?legacyText[value]:value;
const displayNames=record=>Object.fromEntries(Object.entries(record||{}).filter(([,file])=>file&&typeof file==='object'&&file.name).map(([key,file])=>[key,displayFile(file).name]));
const displaySections=(sections,key='items')=>(sections||[]).map(section=>({...section,[key]:(section[key]||[]).map(item=>{const row={...item},file=displayFile(item.file),savedFile=displayFile(item.savedFile);if(file!==undefined)row.file=file;if(savedFile!==undefined)row.savedFile=savedFile;if(item.file&&item.name===item.file.name)row.name=file.name;if(item.savedFile&&item.label===item.savedFile.name)row.label=savedFile.name;return row;})}));
function reference(input={}){
 const actor=require('./access').context();
 if(!actor.demo||release())throw Error('当前版本不能生成模拟文件，请上传实际文件');
 const scope=input.scope==='company'?'company':'project',customerId=String(input.customerId||''),projectId=String(input.projectId||''),companyId=String(input.companyId||''),version=String(input.version||'');
 if(scope==='project'&&!customerId)throw Error('生成本项目模拟文件前必须解析客户ID');
 if(scope==='project'&&!projectId)throw Error('生成本项目模拟文件前必须解析项目ID');
 if(scope==='company'&&!companyId)throw Error('生成本公司模拟文件前必须解析公司ID');
 if(!version||['当前版本','项目当前版本','当前原单版本'].includes(version))throw Error('生成模拟文件前必须解析具体版本');
 const details=(input.details||[]).filter(Boolean).map(String),meta={scope,companyId,customerId,projectId,version,actorId:actor.staffId,purpose:String(input.purpose||'参考凭据'),sourceParty:String(input.sourceParty||''),confirmationParty:String(input.confirmationParty||'')};
 const subject=scope==='project'?['客户：'+String(input.customer||'未填写')+'（'+customerId+'）','项目：'+String(input.project||input.customer||'未填写'),'项目ID：'+projectId]:['公司：'+String(input.company||actor.companyId),'公司ID：'+companyId];
 const text=['体验版模拟文件',...subject,'版本：'+version,meta.sourceParty?'来源责任人：'+meta.sourceParty:'',meta.confirmationParty?'确认方：'+meta.confirmationParty:'','当前登记人：'+actor.name+'（'+actor.staffId+'）','用途：'+meta.purpose,...details,'说明：本文件仅用于体验版流程联调，可在页面内直接预览。'].filter(Boolean).join('\n');
 return {name:String(input.name||meta.purpose+'参考凭据.pdf'),sample:true,text,meta};
}
const items=list=>release()||!require('./access').context().demo?list.slice(0,1):list;
module.exports={release,items,reference,displayFile,displayNames,displaySections,displayText};

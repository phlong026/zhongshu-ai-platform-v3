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
module.exports={release,items:list=>release()?list.slice(0,1):list,displayFile,displayNames,displaySections,displayText};

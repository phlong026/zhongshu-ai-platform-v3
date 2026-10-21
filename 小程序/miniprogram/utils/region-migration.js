// Preserve organization IDs and attachment paths while updating existing local text.
const KEY='zhongshu-region-hubei-v1';
const replacements=[['泉州市','武汉市'],['福建省','湖北省'],['福建','湖北'],['晋江市','麻城市'],['晋江','麻城'],['惠安县','红安县'],['惠安','红安'],['南安市','武穴市'],['南安','武穴'],['厦门市','宜昌市'],['福州市','襄阳市'],['泉州','湖北']];
function convert(value,key=''){
 if(typeof value==='string'){
  if(/(?:^id$|Id$|Ids$)/.test(key)||/^(?:path|filePath|tempFilePath|savedFilePath|sourcePath|url|src|image|avatar)$/i.test(key))return value;
  if(/^(?:https?:|wxfile:|data:|\/pages\/|\/assets\/)/.test(value))return value;
  if(key==='city'&&['泉州','泉州市'].includes(value))return '武汉市';
  return replacements.reduce((text,[from,to])=>text.split(from).join(to),value);
 }
 if(Array.isArray(value))return value.map(item=>convert(item,key));
 if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([name,item])=>[name,convert(item,name)]));
 return value;
}
function run(){
 if(wx.getStorageSync(KEY)?.version===1)return;
 const changes=wx.getStorageInfoSync().keys.filter(key=>key.startsWith('zhongshu-')&&key!==KEY).map(key=>{const before=wx.getStorageSync(key);return {key,before,after:convert(before)};}).filter(row=>JSON.stringify(row.before)!==JSON.stringify(row.after));
 const saved=[];
 try{for(const row of changes){wx.setStorageSync(row.key,row.after);saved.push(row);}wx.setStorageSync(KEY,{version:1});}
 catch(error){for(const row of saved.reverse())wx.setStorageSync(row.key,row.before);throw error;}
}
module.exports={run,convert};

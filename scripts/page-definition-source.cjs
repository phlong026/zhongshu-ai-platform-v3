const fs=require('node:fs'),path=require('node:path');
// Exercise the same workspace definition after native Tab extraction. Routing
// and lifecycle wrappers have separate role/lifecycle integration coverage.
module.exports=function(root,file){
 const factories={'miniprogram/pages/creation/index.js':'miniprogram/utils/sales-creation-page.js','miniprogram/pages/design/index.js':'miniprogram/utils/sales-design-page.js','miniprogram/pages/customers/index.js':'miniprogram/utils/sales-customers-page.js'};
 const actual=factories[file];if(!actual)return fs.readFileSync(path.join(root,file),'utf8');
 return fs.readFileSync(path.join(root,actual),'utf8').replace('module.exports=()=>({','Page({').replace(/require\((['"])(\.[^'"]+)\1\)/g,(all,quote,id)=>{
  let resolved=path.posix.relative(path.posix.dirname(file),path.posix.normalize(path.posix.join(path.posix.dirname(actual),id)));
  if(!resolved.startsWith('.'))resolved='./'+resolved;return 'require('+quote+resolved+quote+')';
 });
};

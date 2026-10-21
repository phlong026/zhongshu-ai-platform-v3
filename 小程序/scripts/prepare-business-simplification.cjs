const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'..'),out=path.join(root,'qa/business-module-simplification-20261021/baseline');
if(!fs.existsSync(path.join(out,'manifest.json'))){
 const rows=[];
 function save(file){const rel=path.relative(root,file),data=fs.readFileSync(file),dest=path.join(out,rel);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.writeFileSync(dest,data);rows.push({path:rel.replace(/\\/g,'/'),sha256:crypto.createHash('sha256').update(data).digest('hex')});}
 function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,e.name);if(e.isDirectory()){if(!['assets','node_modules'].includes(e.name))walk(p);}else if(/\.(js|cjs|wxml|wxss|json|md)$/.test(e.name))save(p);}}
 for(const dir of ['miniprogram','scripts','docs'])walk(path.join(root,dir));for(const file of ['README.md','package.json','design-qa.md'])save(path.join(root,file));
 fs.writeFileSync(path.join(out,'manifest.json'),JSON.stringify({createdAt:new Date().toISOString(),files:rows},null,2));
 console.log('Saved source baseline:',rows.length,'files');
}else console.log('Existing task baseline retained');

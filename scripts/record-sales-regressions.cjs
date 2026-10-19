const fs=require('fs'),assert=require('assert/strict');
const specs=[
 ['多人主链','qa/franchise-phase-20261018/model-main/results.json'],
 ['同人兼岗主链','qa/role-auto-implementation-20261019/model-same/results.json'],
 ['异常检查','qa/franchise-phase-20261018/model-exceptions/results.json'],
 ['自动流转','qa/role-auto-implementation-20261019/model-auto/results.json'],
 ['全身份页面','qa/franchise-phase-20261018/role-pages/results.json'],
 ['岗位导航','qa/guest-home-role-nav-20261019/model-results.json']
];
const rows=specs.map(([name,file])=>{
 const r=JSON.parse(fs.readFileSync(file,'utf8'));
 assert.equal((r.checks||[]).filter(x=>x.result==='failed').length,0,name);
 assert.equal(r.failed||0,0,name);
 return {name,file,checks:r.checks?.length,identities:r.identities,pages:r.pagesChecked,failed:0,scope:r.scope||r.kind};
});
const output={result:'passed',checks:rows};
fs.writeFileSync('qa/business-module-redesign-20261019/regression-summary.json',JSON.stringify(output,null,2));
console.log(JSON.stringify(output));

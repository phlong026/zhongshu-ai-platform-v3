const fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict');
const root=path.resolve('qa/role-complete-20261018'),report=path.join(root,'多角色全流程与异常分支完整验收报告.html');
const summary=JSON.parse(fs.readFileSync(path.join(root,'summary.json'),'utf8')),manifest=JSON.parse(fs.readFileSync(path.join(root,'delivery-manifest.json'),'utf8'));
const html=fs.readFileSync(report,'utf8'),references=[...html.matchAll(/(?:href|src)="([^"]+)"/g)].map(m=>m[1]).filter(s=>!/^https?:|^data:|^#/.test(s));
for(const ref of references)assert(fs.existsSync(path.resolve(root,decodeURIComponent(ref.replace(/[?#].*$/,'')))),'Missing report reference '+ref);
for(const row of manifest.files){const data=fs.readFileSync(row.path);assert.equal(crypto.createHash('sha256').update(data).digest('hex'),row.sha256,'Changed after manifest '+row.path);}
assert.equal(summary.result,'passed');assert.equal(summary.accounts,15);assert.equal(summary.routeStates,2775);assert.equal(summary.layoutStates,2775);assert(summary.footerChecks>=40);assert(summary.storageRestored);
const result={result:'passed',at:new Date().toISOString(),reportReferences:references.length,hashedFiles:manifest.files.length,accounts:summary.accounts,routeStates:summary.routeStates,footerChecks:summary.footerChecks};
fs.writeFileSync(path.join(root,'delivery-validation.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));

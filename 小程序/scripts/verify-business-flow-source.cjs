const fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict');
const fingerprint=require('./engineering-audit-fingerprint.cjs');
const root=path.resolve('qa/business-flow-priority-20261021');
const engineering=path.resolve('qa/engineering-finance-audit-20261021');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const hash=buffer=>crypto.createHash('sha256').update(buffer).digest('hex');
const baseline=read(path.join(root,'tested-source-final.json'));
const original=Object.fromEntries(baseline.hashes.map(r=>[r.path.replaceAll('\\','/'),r.sha256]));
const treeOf=files=>{const d=crypto.createHash('sha256');for(const f of Object.keys(files).sort())d.update(f+'\0'+files[f]+'\n');return {files:Object.keys(files).length,sha256:d.digest('hex')};};
const walk=dir=>fs.readdirSync(path.join('miniprogram',dir),{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.posix.join(dir,e.name)):[path.posix.join(dir,e.name)]);
const current=Object.fromEntries(walk('').sort().map(f=>[f,hash(fs.readFileSync(path.join('miniprogram',f)))]));
const primaryTree=treeOf(original),finalTree=treeOf(current);
const delta=[...new Set([...Object.keys(original),...Object.keys(current)])].sort().filter(f=>original[f]!==current[f]).map(file=>({file,old:original[file]||null,current:current[file]||null}));
assert.deepEqual(delta.map(r=>r.file),['pages/build/index.js','pages/build/model.js','pages/build/views.js','utils/engineering-context.js'],'Unexpected product changes since the original 35-step native suite');
const display=read(path.join(engineering,'delivery-display-fix-scope.json'));
const routing=read(path.join(engineering,'routing-context-fix-scope.json'));
const labels=read(path.join(engineering,'delivery-form-label-scope.json'));
const readonly=read(path.join(engineering,'legacy-document-route-scope.json'));
assert(display.verifiedExpectedSourceText&&routing.verified&&labels.verified&&readonly.verified,'All incremental scopes must be independently verified');
assert.deepEqual(primaryTree,display.oldTree);
assert.deepEqual(display.currentTree,routing.oldTree);
assert.deepEqual(routing.currentTree,labels.oldTree);
assert.deepEqual(labels.currentTree,readonly.oldTree);
assert.deepEqual(finalTree,readonly.currentTree);
assert.deepEqual(fingerprint('miniprogram').fullTree,finalTree);
assert.deepEqual(fingerprint('.tools/engineering-finance-audit-20261021/miniprogram').fullTree,finalTree,'Final source and native test project must match');
const evidence=[];
for(const [file,expected]of [['native-business-continuation/continuous-results.json',[routing.currentTree]],['native-navigation/continuous-results.json',[routing.currentTree]],['native-export-pair/results.json',[routing.currentTree]],['native-record-context/results.json',[routing.currentTree]],['native-entry-links/results.json',[routing.currentTree]],['native-menus/results.json',[routing.currentTree,labels.currentTree]],['native-delivery-identity/results.json',[labels.currentTree]],['native-legacy-document/results.json',[finalTree]]]){
  const r=read(path.join(engineering,file));assert.equal(r.result,'passed',file);assert(expected.some(tree=>JSON.stringify(tree)===JSON.stringify(r.fingerprint.fullTree)),file+' source');
  if(file==='native-menus/results.json'){
    const pageStates=r.checks.filter(c=>c.name.startsWith('页面 '));assert.equal(pageStates.length,42,'All engineering menu states must be verified');
    assert(pageStates.every(c=>[routing.currentTree.sha256,labels.currentTree.sha256].includes(c.lastVerifiedSourceTree)),'Every menu state must be rechecked after the routing context fix');
  }
  evidence.push({file:path.relative(root,path.join(engineering,file)),result:r.result,checks:r.checks?.length??r.files?.length,source:r.fingerprint.fullTree});
}
const sourceFile=path.join(root,'native-final/continuous-final-state.json'),continuation=read(path.join(engineering,'native-business-continuation/continuous-results.json')).continuation;
const regressions=read(path.join(engineering,'fixed-regressions.json'));assert.equal(regressions.result,'passed');assert(regressions.checks.every(c=>c.result==='passed'));assert(regressions.checks.some(c=>c.name==='来源项目旧补资料直链只读且模型拒绝改写，旧独立工程仍兼容'&&c.result==='passed'),'The source mutation guard and old independent project compatibility must both pass');
assert(continuation.sourceUnchanged);assert.equal(hash(fs.readFileSync(sourceFile)),continuation.sha256);
assert.equal(continuation.customerId,continuation.finalCustomerId);assert.equal(continuation.projectId,continuation.finalProjectId);
const chain=[['delivery-display-fix-scope.json',display],['routing-context-fix-scope.json',routing],['delivery-form-label-scope.json',labels],['legacy-document-route-scope.json',readonly]].map(([file,scope])=>({file:path.relative(root,path.join(engineering,file)),...scope}));
fs.writeFileSync(path.join(root,'post-primary-engineering-fixes.json'),JSON.stringify({verified:true,primaryTree,finalTree,delta,chain},null,2));
fs.writeFileSync(path.join(root,'tested-source-incremental-final.json'),JSON.stringify({files:Object.keys(current).length,verifiedAt:new Date().toISOString(),fullTree:finalTree,hashes:Object.entries(current).map(([file,sha256])=>({path:file,sha256}))},null,2));
const proof={result:'passed',primaryTree,finalTree,delta,evidence,continuation,scope:'Original business native evidence is retained. Engineering routing changes were rechecked with the exact business output; two form labels were verified by a separate mock-prefilled form and native save/archive/receipt run. The later source-project legacy document edit route and mutation guard were verified by a separate native two-stage old-link and regular-entry test. Unaffected business files match the original native suite byte for byte.',verifiedAt:new Date().toISOString()};
fs.writeFileSync(path.join(root,'final-native-source-consistency.json'),JSON.stringify(proof,null,2));
console.log(JSON.stringify({result:proof.result,primaryTree,finalTree,delta:delta.map(r=>r.file),nativeEvidence:evidence.length}));

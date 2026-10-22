const fs=require('fs'),path=require('path'),{spawnSync}=require('child_process');
const root=path.resolve(__dirname,'..'),out=path.join(root,'qa/business-module-simplification-20261021'),checks=[];
const stages={
 1:['verify-sales-offline-flow.cjs'],
 2:['verify-sales-offline-pages.cjs'],
 3:['verify-sales-offline-exceptions.cjs','verify-sales-todo-handoffs.cjs','verify-sales-offline-residual.cjs','verify-sales-customers.cjs','verify-sales-publication.cjs','verify-sales-creation-chain.cjs','verify-sales-design.cjs','verify-business-four-flows.cjs','verify-guest-home-role-nav.cjs','verify-finance-navigation-restore.cjs','verify.cjs','verify-closed-loop-remediation.cjs']
};
const selected=process.argv[2]?[process.argv[2]]:['1','2','3'];
fs.mkdirSync(out,{recursive:true});
for(const stage of selected){if(!stages[stage])throw Error('Unknown batch '+stage);if(stage!=='1'&&!fs.existsSync(path.join(out,'page-scenes.json')))throw Error('Run batch 1 before batches 2/3');for(const script of stages[stage]){const r=spawnSync(process.execPath,[path.join(__dirname,script)],{cwd:root,encoding:'utf8',env:{...process.env,SALES_QA_DIR:path.join(out,'regressions/sales')}}),log=script.replace('.cjs','.log');fs.writeFileSync(path.join(out,log),(r.stdout||'')+(r.stderr||'')+(r.error?.stack||''));const check={batch:Number(stage),script,result:r.status===0?'passed':'failed',exitCode:r.status,log};checks.push(check);console.log(check.result.toUpperCase()+' batch '+stage+' '+script);if(r.status!==0){console.error((r.stderr||r.stdout||r.error?.message||'').slice(-4500));process.exitCode=1;break;}}if(process.exitCode)break;}
const report={result:checks.every(c=>c.result==='passed')?'passed':'failed',scope:'Isolated models and actual Page/WXML events; native rendering is recorded separately.',checks};fs.writeFileSync(path.join(out,selected.length===3?'regression-results.json':'batch'+selected[0]+'-run.json'),JSON.stringify(report,null,2));

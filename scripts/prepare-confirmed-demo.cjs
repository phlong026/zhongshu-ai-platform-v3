// Rebuild stage data only when explicitly invoked. Ordinary checks do not rewrite it.
const fs=require('fs'),path=require('path'),{spawnSync}=require('child_process'),data=require('../miniprogram/pages/demo/fixtures'),Demo=require('../miniprogram/utils/demo-session');
const folder=path.resolve('qa/business-followups-20261019/captures');fs.mkdirSync(folder,{recursive:true});
const other=data.scenes.filter(s=>s.group==='其他模块').map(s=>({...s,before:Demo.materialize(data,s.before),after:Demo.materialize(data,s.after)}));
function run(script,capture){const env={...process.env,DEMO_CAPTURE_DIR:folder};delete env.DEMO_CAPTURE;if(capture)env.DEMO_CAPTURE=path.join(folder,capture+'-capture.json');const r=spawnSync(process.execPath,['scripts/'+script],{stdio:'inherit',env});if(r.status!==0)process.exit(r.status||1);}
run('verify-role-same-person-main.cjs','chain');run('verify-confirmed-approval-policy.cjs','policy');
const policy=JSON.parse(fs.readFileSync(path.join(folder,'policy-capture.json'),'utf8')).steps;
fs.writeFileSync(path.join(folder,'extra-capture.json'),JSON.stringify({steps:other.concat(policy)}));
fs.writeFileSync(path.join(folder,'exceptions-capture.json'),'{"steps":[]}');run('build-demo-fixtures.cjs');run('verify-demo-pages.cjs');

const {spawnSync}=require('child_process');
for(const script of ['verify-role-same-person-main.cjs','verify-confirmed-approval-policy.cjs','verify-design-reference-flow.cjs','verify-demo-pages.cjs','verify.cjs']){const env={...process.env};delete env.DEMO_CAPTURE;const r=spawnSync(process.execPath,['scripts/'+script],{stdio:'inherit',env});if(r.status!==0)process.exit(r.status||1);}

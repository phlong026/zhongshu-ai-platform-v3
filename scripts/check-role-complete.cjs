const {spawnSync}=require('child_process');
const mode=process.argv[2]||'model',suites={
 layout:['verify-footer-layout.cjs','verify-footer-dialog.cjs'],
 model:['verify-role-remediation.cjs','verify-role-remediation-extra.cjs','verify-role-complete.cjs','verify-business-transition-complete.cjs','verify.cjs'],
 pages:['verify-role-route-matrix.cjs','verify-role-main-complete.cjs','verify-role-main-final-complete.cjs','verify-role-complete-pages.cjs','verify-role-exceptions-complete.cjs'],
 native:['verify-role-complete-pages.cjs','verify-role-main-complete.cjs','verify-role-main-final-complete.cjs','verify-role-exceptions-complete.cjs','verify-role-creation-complete.cjs','verify-role-creation-extra-complete.cjs','verify-footer-layout.cjs','verify-footer-dialog.cjs','verify-role-workbenches-complete.cjs']
};if(!suites[mode])throw Error('Use model, pages, native or layout');const env={...process.env};delete env.RESUME_ROLE_COMPLETE;delete env.RESUME_ROLE_REMEDIATION;delete env.RESUME_ROLE_EXCEPTIONS;delete env.RESUME_FOOTER;delete env.DIALOG_COMPONENT_ONLY;if(mode==='pages')env.ROLE_HEADLESS='1';else delete env.ROLE_HEADLESS;for(const script of suites[mode]){const r=spawnSync(process.execPath,['scripts/'+script],{stdio:'inherit',env});if(r.status!==0)process.exit(r.status||1);}

const fs=require('fs'),path=require('path'),{spawnSync}=require('child_process');
const out=path.resolve('qa/business-flow-optimization-20261020');fs.mkdirSync(out,{recursive:true});
const jobs=[
 ['AI方案至工程完整主链','scripts/verify-role-same-person-main.cjs',{DESIGN_FIRST_QA_DIR:path.join(out,'main'),DEMO_CAPTURE:path.join(out,'trace.json'),BUSINESS_BUDGET_ONLY:''}],
 ['独立预算无AI平面完整主链','scripts/verify-role-same-person-main.cjs',{DESIGN_FIRST_QA_DIR:path.join(out,'independent-main'),DEMO_CAPTURE:path.join(out,'independent-trace.json'),BUSINESS_BUDGET_ONLY:'1'}],
 ['预算至工程交接专项','scripts/verify-business-handover-optimization.cjs',{}],
 ['统一返回导航','scripts/verify-page-navigation.cjs',{}],
 ['最终页面显示复核','scripts/verify-business-ui-polish.cjs',{}],
 ['既定审批与岗位规则','scripts/verify-confirmed-approval-policy.cjs',{}],
 ['分包与资源检查','scripts/verify-design-package.cjs',{}],
 ['项目静态检查','scripts/verify.cjs',{}]
];
const checks=[];for(const [name,script,env]of jobs){const result=spawnSync(process.execPath,[script],{cwd:process.cwd(),env:{...process.env,...env},encoding:'utf8'});process.stdout.write(result.stdout||'');if(result.stderr)process.stderr.write(result.stderr);checks.push({name,result:result.status===0?'passed':'failed'});if(result.status!==0){fs.writeFileSync(path.join(out,'suite-results.json'),JSON.stringify({result:'failed',checks},null,2));process.exit(result.status||1);}}
fs.writeFileSync(path.join(out,'suite-results.json'),JSON.stringify({result:'passed',checks,scope:'两条完整Page/模型主链、16组业务专项、10组导航回归、审批权限与包体资源检查。原生连续办理请另运行 check:business:handover:native。'},null,2));

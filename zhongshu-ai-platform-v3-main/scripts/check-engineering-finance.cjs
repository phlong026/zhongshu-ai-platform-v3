const fs=require('fs'),path=require('path'),{spawnSync}=require('child_process');
const out=path.resolve('qa/engineering-finance-audit-20261021');fs.mkdirSync(out,{recursive:true});
const jobs=[
 ['AI方案至工程及财务结清','verify-role-same-person-main.cjs',{DESIGN_FIRST_QA_DIR:path.join(out,'fixed-main'),DEMO_CAPTURE:path.join(out,'fixed-trace.json'),BUSINESS_INLINE_ASSIGN:'1',BUSINESS_HQ_DESIGNER_ROLE:'1'}],
 ['独立预算至工程及财务结清','verify-role-same-person-main.cjs',{DESIGN_FIRST_QA_DIR:path.join(out,'fixed-independent-main'),DEMO_CAPTURE:path.join(out,'fixed-independent-trace.json'),BUSINESS_INLINE_ASSIGN:'1',BUSINESS_HQ_DESIGNER_ROLE:'1',BUSINESS_BUDGET_ONLY:'1'}],
 ['工程与财务断点修复回归','verify-engineering-finance-closure.cjs',{}],
 ['同一项目50步Page/WXML逐控件连续办理','replay-engineering-pages.cjs',{}],
 ['原工程模块','verify-engineering-module.cjs',{}],
 ['既定审批与实际人员回避','verify-confirmed-approval-policy.cjs',{}],
 ['导航返回与身份隔离','verify-page-navigation.cjs',{}],
 ['新旧财务及往来','verify-finance-desk.cjs',{}],
 ['工程原生模拟数据准备','build-engineering-native-fixtures.cjs',{}],
 ['项目静态与包体检查','verify.cjs',{}]
];
const checks=[];for(const[name,file,env]of jobs){const r=spawnSync(process.execPath,[path.join(__dirname,file)],{cwd:process.cwd(),env:{...process.env,BUSINESS_BUDGET_ONLY:'',...env},encoding:'utf8'});const log=name.replace(/[\\/:*?"<>|]/g,'-')+'.log';fs.writeFileSync(path.join(out,log),(r.stdout||'')+(r.stderr||''));checks.push({name,result:r.status===0?'passed':'failed',log});console.log((r.status===0?'PASS ':'FAIL ')+name);if(r.status!==0){console.error(r.stderr||r.stdout);break;}}
const result=checks.length===jobs.length&&checks.every(c=>c.result==='passed')?'passed':'failed';fs.writeFileSync(path.join(out,'suite-results.json'),JSON.stringify({result,scope:'当前代码模型及Page/WXML，不代替微信原生Element.tap验收',checks},null,2));if(result!=='passed')process.exitCode=1;

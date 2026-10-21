const fs=require('fs'),path=require('path'),{spawnSync}=require('child_process');
module.exports=function(file,{ai=false,refresh=false,out}={}){
 file=path.resolve(file);if(refresh||!fs.existsSync(file)){
  fs.mkdirSync(path.dirname(file),{recursive:true});const env={...process.env,DEMO_CAPTURE:file,DESIGN_FIRST_QA_DIR:out||path.join(path.dirname(file),ai?'model-final-ai':'model-final'),BUSINESS_INLINE_ASSIGN:'1',BUSINESS_HQ_DESIGNER_ROLE:'1'};if(ai)delete env.BUSINESS_BUDGET_ONLY;else env.BUSINESS_BUDGET_ONLY='1';
  const r=spawnSync(process.execPath,[path.join(__dirname,'verify-role-same-person-main.cjs')],{cwd:path.resolve(__dirname,'..'),env,encoding:'utf8'});if(r.status!==0)throw Error('模拟业务主链生成失败：'+r.stdout+'\n'+r.stderr);
 }
 return JSON.parse(fs.readFileSync(file,'utf8'));
};

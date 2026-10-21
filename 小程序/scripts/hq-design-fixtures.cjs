const fs=require('fs'),path=require('path'),{spawnSync}=require('child_process');
module.exports=function(){
 const directory=path.resolve(__dirname,'../qa/receipt-design-handoff-20261021'),file=path.join(directory,'after-trace.json');
 if(!fs.existsSync(file)){
  fs.mkdirSync(directory,{recursive:true});
  const result=spawnSync(process.execPath,[path.join(__dirname,'verify-role-same-person-main.cjs')],{cwd:path.resolve(__dirname,'..'),encoding:'utf8',env:{...process.env,DEMO_CAPTURE:file,DESIGN_FIRST_QA_DIR:path.join(directory,'after-main'),BUSINESS_BUDGET_ONLY:'1'}});
  if(result.status!==0)throw Error('Unable to generate simulated design prerequisites: '+result.stdout+'\n'+result.stderr);
 }
 return JSON.parse(fs.readFileSync(file,'utf8')).steps;
};

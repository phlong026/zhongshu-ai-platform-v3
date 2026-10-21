const {spawnSync}=require('child_process');
for(const [script,...args]of [['scripts/verify-dashboard-simulator.cjs'],['scripts/verify-business-simulator.cjs'],['scripts/capture-business.cjs','final']]){
 console.log('\nRUN '+script);
 const result=spawnSync(process.execPath,[script,...args],{stdio:'inherit'});
 if(result.status!==0)process.exit(result.status||1);
}

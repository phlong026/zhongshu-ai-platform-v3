const assert=require('assert/strict');const fs=require('fs');const vm=require('vm');const path=require('path');
const root=path.resolve(__dirname,'..');const source=fs.readFileSync(path.join(root,'miniprogram/pages/studio/index.js'),'utf8');
const checks=[];
for(const scenario of [
  {name:'pending task cannot present a completed result',task:{id:'target',status:'generating'},expected:'tasks'},
  {name:'missing task returns to works',task:{id:'other',status:'completed'},expected:'works'},
  {name:'completed task can present a result',task:{id:'target',status:'completed'},expected:'result'}
]){
  let page,redirect,refreshed=false;
  const state={title:'test',original:'test',script:'test',tasks:[scenario.task],works:[],records:[]};
  vm.runInNewContext(source,{Page:p=>page=p,require:name=>name.endsWith('/studio')?{read:()=>state,guard:()=>true,scope:()=>'result-guard'}:{unavailable(){},assets:[],token:()=>''},wx:{showToast(){},redirectTo:options=>redirect=options.url}});
  const instance={data:{},setData(value){Object.assign(this.data,value)},refreshData(){refreshed=true}};
  page.onLoad.call(instance,{view:'result',task:'target'});
  assert.equal(instance.data.view,scenario.expected);
  if(scenario.expected==='result'){assert.equal(redirect,undefined);assert(refreshed)}
  else assert.equal(redirect,'/pages/studio/index?view='+scenario.expected);
  checks.push({name:scenario.name,result:'passed'});
}
fs.writeFileSync(path.join(root,'qa/result-guard-results.json'),JSON.stringify({result:'passed',method:'Source behavior checks in isolated JS context; does not claim simulator navigation coverage',checks},null,2));
console.log('3 result-state guard checks passed.');

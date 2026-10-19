// Preserve old acceptance evidence; run the same assertions against this revision with explicit identities.
const fs=require('fs');
let s=fs.readFileSync('scripts/verify-role-remediation-simulator.cjs','utf8');
s=s.replace("'qa/role-remediation-20261018/'+(headless?'pages':'native')", "'qa/role-complete-20261018/'+(headless?'pages-main':'native-main')");
const a=s.indexOf(' const login=async id=>'),b=s.indexOf(' const payid=',a);
s=s.slice(0,a)+` const login=async id=>{const person=Demo.people.find(p=>p.id===id);assert(person,id);who=id;await open('/pages/account/index?view=login');await mp.evaluate(id=>{const p=getCurrentPages().slice(-1)[0];p.setData({selectedDemoUser:id});p.loginDemo();},id);await until(async()=>(await mp.currentPage()).path==='pages/profile/index','simulated login settled');const a=await stored(A.KEY);assert.equal(a.session.staffId,person.staffId);assert.equal(a.session.role,person.roles[0]);assert.deepEqual(a.companies[0].roles,person.roles);};
`+s.slice(b);fs.writeFileSync('scripts/verify-role-main-complete.cjs',s);
s=fs.readFileSync('scripts/verify-role-final-pages.cjs','utf8').replace("qa/role-remediation-20261018","qa/role-complete-20261018").replace("headless?'pages':'native','final-business-state.json'", "headless?'pages-main':'native-main','final-business-state.json'");
const c=s.indexOf(' const login=async id=>'),d=s.indexOf(' const capture=',c);
s=s.slice(0,c)+` const login=async id=>{await open('/pages/account/index?view=login');await mp.evaluate(id=>{const p=getCurrentPages().slice(-1)[0];p.setData({selectedDemoUser:id});p.loginDemo();},id);await until(async()=>(await mp.currentPage()).path==='pages/profile/index','simulated login settled');};
`+s.slice(d);fs.writeFileSync('scripts/verify-role-main-final-complete.cjs',s);
for(const [input,output,result] of [['verify-creation.cjs','verify-role-creation-complete.cjs','creation'],['verify-creation-extra.cjs','verify-role-creation-extra-complete.cjs','creation-extra']]){
 s=fs.readFileSync('scripts/'+input,'utf8').replaceAll("ws://127.0.0.1:9420","ws://127.0.0.1:9720");
 s=s.replace("const keys=['zhongshu-studio','zhongshu-video-draft','zhongshu-creation-draft'];", "const keys=['zhongshu-studio','zhongshu-video-draft','zhongshu-creation-draft','zhongshu-account-v1','zhongshu-management-v1'];").replace("const keys=['zhongshu-studio'", "const keys=['zhongshu-studio'");
 const auth=`const testAccount=require('../miniprogram/utils/account').seed();testAccount.session.loggedIn=true;await mp.evaluate(x=>{wx.setStorageSync('zhongshu-account-v1',x.a);wx.setStorageSync('zhongshu-management-v1',x.m);},{a:testAccount,m:require('../miniprogram/pages/admin/model').seed()});`;
 if(input==='verify-creation.cjs'){
  s=s.replace("await mp.evaluate(keys=>keys.forEach(key=>wx.removeStorageSync(key)),keys);", "await mp.evaluate(keys=>keys.forEach(key=>wx.removeStorageSync(key)),keys);"+auth);
  s=s.replace("pass('demo submit → record → progress → receipt → records closes publication loop');", "await mp.screenshot({path:path.join(root,'qa/role-complete-20261018/native-creation-receipt.png')});pass('demo submit → record → progress → receipt → records closes publication loop');");
  s=s.replace("qa/creation-interaction-results.json", "qa/role-complete-20261018/native-creation-results.json");
 }else{s=s.replace("  try{", "  try{"+auth);s=s.replaceAll('qa/creation-supplemental-results.json','qa/role-complete-20261018/native-creation-extra-results.json');}
 s=s.replace("await mp.disconnect();", "await mp.callWxMethod('reLaunch',{url:'/pages/home/index'});await mp.disconnect();");fs.writeFileSync('scripts/'+output,s);
}

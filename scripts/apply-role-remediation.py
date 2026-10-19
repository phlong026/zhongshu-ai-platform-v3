from pathlib import Path
root=Path(__file__).resolve().parents[1]
def edit(name,old,new,count=1):
 p=root/name;s=p.read_text(encoding='utf-8');assert old in s,(name,old[:100]);p.write_text(s.replace(old,new,count),encoding='utf-8')
edit('miniprogram/utils/account.js',"const KEY = 'zhongshu-account-v1';","const X=require('./access'),Demo=require('./demo-users');\nconst KEY = 'zhongshu-account-v1';")
edit('miniprogram/utils/account.js',"loggedIn: true, userId: 'demo-zhang'","loggedIn: false, staffId:'zhang', userId: 'demo-zhang'")
edit('miniprogram/utils/account.js',"roles: ['sales', 'manager', 'owner', 'finance']","roles: ['sales']")
edit('miniprogram/utils/account.js',"const roles={sales:'业务员',manager:'项目经理',owner:'门店负责人',finance:'财务专员'};","const roles=X.roles;")
p=root/'miniprogram/utils/account.js';s=p.read_text(encoding='utf-8');a=s.index('function staffActive(');b=s.index('function scoped(',a);s=s[:a]+"function staffActive(s){return X.context(s).active;}\nfunction authorized(s,role){const c=company(s);return !!(X.context(s).active&&c&&c.roles.includes(role));}\nfunction permitted(s,area){const c=X.context(s);return c.active&&(area!=='admin'||['owner','boss','sales-lead','finance','finance-lead'].includes(c.role));}\n"+s[b:];s=s.replace("s.session.role==='owner'","['owner','boss'].includes(s.session.role)").replace("s.session.role!=='owner'","!['owner','boss'].includes(s.session.role)");s=s.replace('module.exports={KEY,','module.exports={demoUsers:Demo.people,loginDemo:(s,id)=>Demo.login(s,id),access:X,KEY,');p.write_text(s,encoding='utf-8')
edit('miniprogram/utils/management-seed.js','function seed(){','function baseSeed(){')
edit('miniprogram/utils/management-seed.js','module.exports=seed;',"function seed(){const s=baseSeed();for(const p of require('./demo-users').people.filter(p=>p.companyId==='quanzhou'&&p.roles[0]!=='customer')){const x=s.staff.find(x=>x.id===p.staffId);if(x){x.roleId=p.roles[0];x.roles=p.roles.slice();}else s.staff.push({id:p.staffId,name:p.name,role:require('./access').roles[p.roles[0]],roleId:p.roles[0],roles:p.roles.slice(),active:true});}for(const w of s.work){if(w.id==='work-chen'){w.customerId='customer-chen';w.projectId='project-chen';w.kind='customer-responsibility';}if(w.id==='work-lin'){w.customerId='customer-lin';w.projectId='project-lin';w.kind='customer-responsibility';}}return s;}\nmodule.exports=seed;")
# Avoid access -> seed -> access recursion: role names are only read after access is initialized.
edit('miniprogram/pages/account/index.js',"services:A.services,chapters:A.chapters","services:A.services,chapters:A.chapters,demoUsers:A.demoUsers.map(p=>({id:p.id,label:p.name+' · '+A.roles[p.roles[0]]})),selectedDemoUser:'zhang',demoUserLabel:'张业务 · 业务员'")
edit('miniprogram/pages/account/index.js',"switchRole(){try{", "selectDemoUser(e){const p=this.data.demoUsers[Number(e.detail.value)];if(p)this.setData({selectedDemoUser:p.id,demoUserLabel:p.label});},switchRole(){try{")
edit('miniprogram/pages/account/index.js',"(role==='manager'?'engineering':['owner','finance'].includes(role)?'management':'business')","A.access.home(role)")
edit('miniprogram/pages/account/index.js',"A.mutate(s=>{s.session.loggedIn=true;s.session.demo=true;});","A.mutate(s=>A.loginDemo(s,this.data.selectedDemoUser));")
edit('miniprogram/pages/account/index.js',"(account|admin)","(account|admin|workflow|build)")
edit('miniprogram/pages/account/index.js',"[a-z]+=[\\w%.-]+","[a-zA-Z]+=[\\w%.-]+")
edit('miniprogram/pages/account/index.js',"role:A.roles[s.session.role],form,","role:A.roles[s.session.role],accessLabel:this.options.content||((this.options.return||'').match(/view=([a-z-]+)/)||[])[1]||'企业业务',form,")
edit('miniprogram/pages/account/index.wxml','访问内容</text><text>财务核算','访问内容</text><text>{{accessLabel}}')
edit('miniprogram/pages/account/index.wxml','<view class="card login-card">','<view class="card login-card"><view class="heading">本地模拟账号</view><picker range="{{demoUsers}}" range-key="label" bindchange="selectDemoUser"><view class="row inset"><text>{{demoUserLabel}}</text><text class="brown">选择人员 ›</text></view></picker><text class="hint">不同人员使用独立身份与授权；仅供本机流程验证。</text>')
# Namespace drafts, so changing identity cannot expose the previous operator's input.
edit('miniprogram/pages/build/index.js',"const E=require('./model');","const X=require('../../utils/access');\nconst E=require('./model');")
edit('miniprogram/pages/build/index.js',"draftKey(){return [this.data.modal","draftKey(){return [X.context().staffId,X.context().role,this.data.modal")
edit('miniprogram/pages/build/index.js',"this.ctx.collectionId=record.id;this.setData({collectionNew:true,form:","this.ctx.collectionId=record.id;this.setData({collectionNew:true,form:") if False else None
edit('miniprogram/pages/build/index.js',"else this.fail('该收款记录无法编辑。');}if(options.modal)","else this.fail('该收款记录无法编辑。');this.refresh();}if(options.modal)")
print('Account and identity changes applied')

const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const root=path.resolve('qa/business-flow-optimization-20261020'),trace=JSON.parse(fs.readFileSync(path.join(root,'independent-trace.json'))).steps,checks=[];
const scene=view=>trace.find(s=>s.area==='workflow'&&new URL(s.url,'http://local').searchParams.get('view')===view);
(async()=>{const r=await require('./role-page-runtime.cjs').connect(),A=require('../miniprogram/utils/account');
 const install=rows=>{for(const k of wx.getStorageInfoSync().keys)wx.removeStorageSync(k);for(const[k,v]of JSON.parse(JSON.stringify(rows)))wx.setStorageSync(k,v);};
 const open=(view,s)=>{wx.reLaunch({url:'/pages/workflow/index?view='+view+'&id='+new URL(s.url,'http://local').searchParams.get('id')});return getCurrentPages().at(-1);};
 const pass=(name,fn)=>{fn();checks.push({name,result:'passed'});console.log('PASS '+name);};
 try{
  let s=scene('deliverable');install(s.before);let p=open('deliverable',s);pass('上传控件不再叠加静态待上传提示',()=>{assert(!p.data.sections.flatMap(s=>s.items).some(i=>i.type==='notice'&&/正式.*待上传/.test(i.value)));});
  s=scene('handover');install(s.before);A.mutate(state=>A.loginDemo(state,'hq-design-dual'));A.mutate(state=>A.switchIdentity(state,'headquarters','hq-design-lead'));p=open('documents',s);pass('签约文件按权限隐藏时仍准确显示已归档状态',()=>{assert(p.data.customer.projectContractArchived);assert.equal(p.data.customer.signedFile,null);const section=p.data.sections.find(s=>s.title==='签约附件');assert.equal(section.badge,'已归档');assert(section.items.some(i=>i.type==='notice'&&i.value.includes('已归档')));assert(!section.items.some(i=>i.type==='notice'&&i.value.includes('待上传')));});
  s=scene('intake');install(s.before);p=open('intake',s);pass('受理页使用适用于门店与总部设计团队的统一标题',()=>assert.equal(p.data.title,'设计受理'));
  let component;global.Component=c=>component=c;delete require.cache[require.resolve('../miniprogram/components/brand-header/index.js')];require('../miniprogram/components/brand-header/index.js');const prior=global.getCurrentPages;
  pass('各工作台首页保留品牌页头，业务子页才展示返回导航',()=>{for(const [route,expected]of [['pages/build/home/index',false],['pages/build/projects/index',false],['pages/build/materials/index',false],['pages/design/index',false],['pages/growth/index',true]]){global.getCurrentPages=()=>[{route}];const c={data:{back:false},setData(p){Object.assign(this.data,p);}};component.lifetimes.attached.call(c);assert.equal(c.data.showNavigation,expected,route);}global.getCurrentPages=prior;});
  fs.writeFileSync(path.join(root,'ui-polish-results.json'),JSON.stringify({result:'passed',checks},null,2));
 }finally{await r.disconnect();}
})().catch(e=>{console.error(e.stack);process.exit(1);});

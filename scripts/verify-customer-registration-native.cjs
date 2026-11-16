// Isolated native project: no live account or server is used.
const fs=require('node:fs'),path=require('node:path'),zlib=require('node:zlib'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),target=process.env.CUSTOMER_NATIVE_PROJECT||path.join(root,'.tools/customer-registration-native-20261116');
const output=path.join(root,'docs/customer-registration-20261116');
fs.mkdirSync(output,{recursive:true});fs.mkdirSync(target,{recursive:true});
let address;
if(process.argv.includes('--prepare')||!fs.existsSync(path.join(target,'miniprogram/customer-registration-fixture.js'))){
fs.cpSync(path.join(root,'miniprogram'),path.join(target,'miniprogram'),{recursive:true});
const config=JSON.parse(fs.readFileSync(path.join(root,'project.config.json'),'utf8'));
Object.assign(config,{appid:'touristappid',projectname:'customer-registration-isolated-20261116'});config.setting.urlCheck=false;config.condition={};
fs.writeFileSync(path.join(target,'project.config.json'),JSON.stringify(config,null,2));
const app=JSON.parse(fs.readFileSync(path.join(root,'miniprogram/app.json'),'utf8'));
fs.writeFileSync(path.join(target,'miniprogram/app.json'),JSON.stringify(app,null,2));
const source=path.resolve(root,'../众墅之家AI赋能平台V3版-后端/平台程序/domains/franchise/franchise-crm/src/main/resources/crm-regions');
const province=JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(source,'42.json.gz'))));
const regions={'':JSON.parse(fs.readFileSync(path.join(source,'root.json'),'utf8'))};
let parent='42';address={};
for(const key of ['city','district','town','village']){
  regions[parent]=province[parent];const option=province[parent][0];address[key]=option.name;parent=option.code;
}
Object.assign(address,{province:'湖北省',regionCode:parent,addressDetail:'一组12号'});
fs.writeFileSync(path.join(target,'miniprogram/customer-registration-fixture.js'),'module.exports='+JSON.stringify({regions,address}));
fs.writeFileSync(path.join(target,'miniprogram/app.js'),String.raw`
const fixture=require('./customer-registration-fixture');
const state={requests:[],customers:[],regions:fixture.regions,address:fixture.address};
wx.setStorageSync('zs-api-token-v1','isolated-customer-registration');wx.setStorageSync('zs-api-post-v1','native-sales');
wx.setStorageSync('zs-api-posts-v1',[{postId:'native-sales',userId:'12',orgId:'99',orgType:'store',roleCode:'sales',name:'登记测试',orgName:'隔离测试公司'}]);
wx.request=function(options){
 const route=String(options.url).split('/admin-api')[1]?.split('?')[0]||'',body=options.data||{};
 state.requests.push({route,method:options.method,body});let data={},code=0;
 if(route==='/franchise/crm/regions')data=state.regions[body.parentCode||'']||[];
 else if(route==='/franchise/customers')data={list:state.customers,total:state.customers.length};
 else if(route==='/franchise/commissions')data={list:[],total:0};
 else if(route==='/franchise/leads'){
  state.customers.push({...body,id:'7001',summary:body.need,status:'REGISTERED',revision:1,ownerUserId:'12'});
  data={result:'CREATED',customerId:'7001'};
 }else if(route==='/franchise/customers/7001'){
  const customer=state.customers[0];if(options.method==='PUT')Object.assign(customer,body,{revision:customer.revision+1});
  data={customer,follows:[],schemes:[]};
 }
 setTimeout(()=>options.success&&options.success({statusCode:200,data:{code,data},header:{}}),15);return{abort(){}};
};
App({globalData:{windowInfo:null,fixture:state}});
`);
}else address=require(path.join(target,'miniprogram/customer-registration-fixture.js')).address;
if(process.argv.includes('--prepare')){console.log(JSON.stringify({target,address}));process.exit(0);}
const automator=require('miniprogram-automator');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
 let mp;
 try{
  mp=await automator.connect({wsEndpoint:process.env.CUSTOMER_FORM_WS||'ws://127.0.0.1:9974'});
  const errors=[];mp.on('exception',error=>errors.push(error));
  const data=()=>mp.evaluate(()=>getCurrentPages().slice(-1)[0].data);
  const call=async(name,arg)=>{await mp.evaluate((name,arg)=>{getCurrentPages().slice(-1)[0][name](arg);},name,arg);await sleep(250);};
  const until=async(check)=>{for(let n=0;n<80;n++){if(await check())return;await sleep(150);}throw Error('Native page timed out');};
  const component=expression=>mp.evaluate(expression);
  await mp.evaluate(()=>{getApp().globalData.fixture.customers=[];getApp().globalData.fixture.requests=[];});
  await mp.callWxMethod('reLaunch',{url:'/pages/workflow/index?view=customers&modal=register'});
  await until(async()=>(await data()).modal==='register');
  await until(()=>component(()=>getCurrentPages().slice(-1)[0].selectComponent('#customer-address')?.data.rows[0]?.options.length===31));
  for(const [key,value] of Object.entries({name:'张登记',phone:'13800000000',summary:'三代同住，建三层',addressDetail:'一组12号'}))await call('input',{currentTarget:{dataset:{key}},detail:{value}});
  // Select the real catalog options through the component's change handlers.
  await mp.evaluate(()=>{const c=getCurrentPages().slice(-1)[0].selectComponent('#customer-address');const index=c.data.rows[0].options.findIndex(row=>row.code==='42');c.choose({currentTarget:{dataset:{level:0}},detail:{value:index}});});
  for(let level=1;level<=4;level++){
   await until(()=>mp.evaluate(level=>getCurrentPages().slice(-1)[0].selectComponent('#customer-address').data.rows[level]?.options.length>0,level));
   await mp.evaluate(level=>getCurrentPages().slice(-1)[0].selectComponent('#customer-address').choose({currentTarget:{dataset:{level}},detail:{value:0}}),level);
  }
  await call('pick',{currentTarget:{dataset:{key:'source',options:'sourceOptions'}},detail:{value:2}});
  assert.equal((await data()).form.regionCode,address.regionCode);
  await sleep(400);await mp.screenshot({path:path.join(output,'01-registration.png')});
  await call('formFieldFocus',{currentTarget:{dataset:{field:'customer-summary-field'}}});
  await call('formKeyboardChanged',{height:300});await sleep(450);
  const rects=await mp.evaluate(()=>new Promise(resolve=>wx.createSelectorQuery().select('.sheet-fields').boundingClientRect().select('.sheet-register .textarea').boundingClientRect().select('.sheet-actions').boundingClientRect().exec(resolve)));
  console.log('Keyboard geometry:',JSON.stringify(rects));
  assert(rects[1].top>=rects[0].top-2,'summary starts inside the scroll area');
  assert(rects[1].bottom<=rects[0].bottom+2,'summary ends above the save action');
  const info=await mp.systemInfo();assert(rects[2].bottom<=info.windowHeight-298,'save action stays above reserved keyboard');
  await mp.screenshot({path:path.join(output,'02-keyboard-space.png')});
  await call('formKeyboardChanged',{height:0});await call('saveModal');
  await until(async()=>(await data()).customer?.id==='7001');
  const saved=await data();assert.match(saved.customer.address,/一组12号/);
  const requests=await mp.evaluate(()=>getApp().globalData.fixture.requests);
  const captured=requests.find(request=>request.route==='/franchise/leads');
  assert.equal(captured.body.regionCode,address.regionCode);assert.equal(captured.body.village,address.village);
  await mp.screenshot({path:path.join(output,'03-saved-address.png')});
  await call('openModal','edit');assert.equal((await data()).form.regionCode,address.regionCode);
  await call('input',{currentTarget:{dataset:{key:'addressDetail'}},detail:{value:'二组8号'}});await call('saveModal');
  await until(async()=>(await data()).customer?.addressDetail==='二组8号');
  assert.equal((await data()).customer.village,address.village);
  assert.equal(errors.length,0,JSON.stringify(errors));
  fs.writeFileSync(path.join(output,'native-results.json'),JSON.stringify({passed:true,checks:['五级地址逐级选择','保存包含村级地址','客户详情回显','编辑地址保留村级字段','300px键盘预留区域内输入框及按钮可见'],keyboardRects:rects,errors,requestCount:requests.length},null,2));
  console.log('Native customer registration passed');
 }finally{if(mp)await mp.disconnect();}
})().catch(error=>{console.error(error);process.exitCode=1;});

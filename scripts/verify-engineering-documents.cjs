const test=require('node:test');
const assert=require('node:assert/strict');
global.wx={getStorageSync:()=>'',getSystemInfoSync:()=>({})};
const {projectView}=require('../miniprogram/utils/platform-engineering');

test('engineering handover preserves the immutable dispatch scope and actual asset',()=>{
  const view=projectView({id:'99',documents:[{name:'施工图',fileRef:JSON.stringify({assetId:'123',name:'施工图.pdf',commissionId:'42',dispatchSeq:2,slot:'DRAWING'})}],nodes:[]});
  assert.equal(view.documents[0].file.assetId,'123');
  assert.equal(view.documents[0].file.commissionId,'42');
  assert.equal(view.documents[0].file.dispatchSeq,2);
  assert.equal(view.documents[0].file.slot,'DRAWING');
});
test('engineering contract is a real asset document; missing legacy bytes do not get a fake URL',()=>{
  const view=projectView({id:'99',managerName:'张工程',contractAssetId:'456',documents:[{name:'旧资料',fileRef:'invalid legacy text'}],nodes:[]});
  const attachment = view.documents.find(d => d.name === '工程合同附件');
  assert.equal(attachment.file.assetId, '456');
  assert.equal(attachment.file.name, '工程合同附件');
  assert.equal(view.manager,'张工程');
  assert.equal(view.documents.find(d=>d.name==='旧资料').file.path,undefined);
});

test('主项目在旧服务上保留表单且不回退写入，增项仍可走父项目入口', async (t) => {
  const http = require('../miniprogram/api/http');
  let definition;
  global.Page = page => { definition = page; };
  require('../miniprogram/pages/build/projects/index');
  const routes = [];
  t.mock.method(require('../miniprogram/utils/page-navigation'), 'open', (url) => routes.push(url));
  const previousToast = wx.showToast;
  wx.showToast = () => {};
  t.after(() => { wx.showToast = previousToast; });
  const paths = [];
  t.mock.method(http, 'post', async (url, body) => {
    paths.push([url, body.independent, body.parentProjectId]);
    if(body.parentProjectId) return {id:'456'};
    throw { code: 404, message: '请求地址不存在' };
  });
  const page = {...definition, data: {creating:true, parentOptions:[{id:''},{id:'123'}],
    createForm:{name:'庭院',customer:'陈先生',city:'武汉',phone:'13800000000',parentIndex:0}},
    setData(patch){Object.assign(this.data,patch);}};
  await page.submitCreateOnline();
  assert.match(page.data.error,/当前服务尚未开通合同建档/);
  assert.equal(page.data.creating,true);
  page.data.createForm.independent = true;
  await page.submitCreateOnline();
  page.data.createForm.parentIndex = 1;
  await page.submitCreateOnline();
  assert.equal(page.data.creating,false);
  assert.equal(page.data.error,'');
  assert.deepEqual(routes,['/pages/build/record/index?id=456&view=prepare']);
  assert.deepEqual(paths,[
    ['/franchise/engineering/projects/manual',false,undefined],
    ['/franchise/engineering/projects/manual',true,undefined],
    ['/franchise/engineering/projects',undefined,'123']
  ]);
});

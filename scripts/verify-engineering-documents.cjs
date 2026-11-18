const test=require('node:test');
const assert=require('node:assert/strict');
global.wx={getStorageSync:()=>'',getSystemInfoSync:()=>({})};
const {projectView}=require('../miniprogram/utils/platform-engineering');

test('变更负责人保留工程师长编号的每一位', async t => {
  const http = require('../miniprogram/api/http');
  const post = t.mock.method(http, 'post', async () => ({}));
  await require('../miniprogram/utils/platform-engineering').perform('project', '42',
    {engineerId: '9007199254740993', note: '转交'}, null, 'transfer-42');
  const [url, body] = post.mock.calls[0].arguments;
  assert.equal(url, '/franchise/engineering/42/manager');
  assert.deepEqual(body, {engineerUserId: '9007199254740993', note: '转交'});
});

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

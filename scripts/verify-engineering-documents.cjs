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
  assert.equal(view.documents.find(d=>d.name==='已签工程合同').file.assetId,'456');
  assert.equal(view.manager,'张工程');
  assert.equal(view.documents.find(d=>d.name==='旧资料').file.path,undefined);
});

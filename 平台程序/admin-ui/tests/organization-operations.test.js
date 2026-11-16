const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
function fixture() {
  const values = new Map()
  const state = { actor:'41',tenant:'1',entity:'profile:120',failWrite:false }
  const localStorage = { getItem:key=>values.get(key)||null,setItem:(key,value)=>{if(state.failWrite)throw Error('storage full');values.set(key,value)},removeItem:key=>values.delete(key) }
  const source=fs.readFileSync(path.join(__dirname,'../src/views/platform/operations/mutation.ts'),'utf8')
  const code=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const module={exports:{}}
  const imports={vue,'@/utils/auth':{getTenantId:()=>state.tenant},'@/store/modules/user':{useUserStore:()=>({getUser:{id:state.actor}})}}
  vm.runInNewContext(code,{module,exports:module.exports,require:key=>imports[key],localStorage})
  return {state,values,create:()=>module.exports.useOperationsMutation(()=>state.entity)}
}
test('lost acknowledgement retries the frozen request and clears only on confirmed success',async()=>{
  const f=fixture(),command=f.create(),sent=[]
  const original={requestKey:'same-request',expectedVersion:3,reason:'original'}
  await assert.rejects(command.run(original,async payload=>{sent.push(JSON.stringify(payload));throw Error('timeout')}),/timeout/)
  assert.equal(f.values.size,1)
  await command.run({...original,requestKey:'new-key',reason:'changed'},async payload=>sent.push(JSON.stringify(payload)))
  assert.equal(sent[0],sent[1]);assert.equal(f.values.size,0);assert.equal(command.pending.value,null)
})
test('reload restores pending input only for the same tenant, actor, entity and action',async()=>{
  const f=fixture(),command=f.create()
  await assert.rejects(command.run({requestKey:'saved',expectedVersion:2},async()=>{throw Error('lost')}))
  const reload=f.create();reload.restore();assert.equal(reload.pending.value.requestKey,'saved')
  f.state.actor='42';const other=f.create();other.restore();assert.equal(other.pending.value,null)
  let calls=0;await assert.rejects(command.run({requestKey:'wrong-actor'},async()=>calls++),/切换/);assert.equal(calls,0)
  f.state.actor='41';f.state.tenant='2';const tenant=f.create();tenant.restore();assert.equal(tenant.pending.value,null)
  f.state.tenant='1';f.state.entity='owner:120';const action=f.create();action.restore();assert.equal(action.pending.value,null)
  f.state.entity='profile:121';const org=f.create();org.restore();assert.equal(org.pending.value,null)
})
test('storage failure prevents sending a request without a recoverable key',async()=>{
  const f=fixture(),command=f.create();f.state.failWrite=true;let calls=0
  await assert.rejects(command.run({requestKey:'never-sent'},async()=>calls++),/storage full/)
  assert.equal(calls,0);assert.equal(command.pending.value,null);assert.equal(command.saving.value,false)
})
test('a second click cannot send while the first request is outstanding',async()=>{
  const f=fixture(),command=f.create();let finish,calls=0
  const first=command.run({requestKey:'one'},()=>{calls++;return new Promise(resolve=>finish=resolve)})
  await assert.rejects(command.run({requestKey:'two'},async()=>calls++),/处理中/)
  finish('ok');await first;assert.equal(calls,1);assert.equal(command.saving.value,false)
})

// Spring's paired regression runs this client and answers requests over stdin/stdout.
const assert = require('node:assert/strict');
const { createInterface } = require('node:readline');
const [commissionId, projectId, engineerId, receiveKey] = process.argv.slice(2);
const replies = createInterface({input: process.stdin})[Symbol.asyncIterator]();
let actor = 'receiver';
let page;
global.Page = definition => { page = definition; };
global.wx = { getStorageSync: () => '', showToast() {}, pageScrollTo() {}, getSystemInfoSync: () => ({}) };
const http = require('../miniprogram/api/http');
http.sessionGuard = () => () => true;
http.post = async (path, body, headers) => {
  process.stdout.write(JSON.stringify({actor, path, body, key: headers['Idempotency-Key']}) + '\n');
  const response = await replies.next();
  assert.equal(response.done, false);
  const result = JSON.parse(response.value);
  if (result.code) throw Object.assign(new Error(result.msg), {code: result.code});
  return result.data;
};
require('../miniprogram/pages/workflow/commission/index');
const engineering = require('../miniprogram/utils/platform-engineering');

async function verify() {
  Object.assign(page, {
    id: commissionId, view: 'receive', action: 'packet-receive', options: {}, initialized: true,
    actor: () => ({}), refresh() {}, cmdKey: () => receiveKey, dropCmdKey() {},
    setData(update) { Object.assign(this.data, update); }
  });
  page.data = {...page.data, platformStatus: 'ready', form: {checked: true}};
  await page.submit();
  assert.equal(page.view, 'receive');
  assert.match(page.data.error, /联系现负责人/);
  actor = 'owner';
  await engineering.perform('project', projectId, {engineerId, note: '同意合同工程转交'}, null, 'approve-' + receiveKey);
  actor = 'receiver';
  await page.submit();
  assert.equal(page.view, 'progress', page.data.error);
}
verify().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => process.stdin.destroy());

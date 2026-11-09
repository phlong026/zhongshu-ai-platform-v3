const assert = require('node:assert/strict');
let definition;
let navigation;
let shouldFail = false;
global.Page = page => { definition = page; };
global.wx = {
  redirectTo(options) {
    navigation = options.url;
    if (shouldFail) options.fail?.({ errMsg: '测试导航失败' });
  },
  switchTab(options) { navigation = options.url; }
};
require('../miniprogram/pages/build/materials/index');
const page = { ...definition, data: structuredClone(definition.data),
  setData(patch) { Object.assign(this.data, patch); } };
page.onLoad({});
assert.equal(navigation, '/pages/build/projects/index?destination=materials', '没有项目上下文应先选项目');
page.onLoad({ id: '9007199254740993', nodeId: '阶段 1' });
assert.equal(navigation, '/pages/build/record/index?view=materials&id=9007199254740993&nodeId=' + encodeURIComponent('阶段 1'));
shouldFail = true;
const originalError = console.error;
const errors = [];
console.error = (...args) => errors.push(args);
try { page.onLoad({}); } finally { console.error = originalError; }
assert.match(page.data.error, /打开失败/);
assert.equal(errors.length, 1);
shouldFail = false;
page.retry();
assert.equal(navigation, '/pages/build/projects/index?destination=materials');
assert.equal(page.data.error, '');
console.log('材料入口：先选项目、保留项目和节点、失败提示与重试均通过');

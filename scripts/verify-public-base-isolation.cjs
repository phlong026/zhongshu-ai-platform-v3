const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '..');
const publicApiBase = 'https://platform.zszhj.com/admin-api';
const oldApiBase = 'https://api2.zszhj.com/admin-api';
const localApiBase = 'http://127.0.0.1:58081/admin-api';

function storage(values) {
  return key => values[key] || '';
}

function loadHttp(platform, values, legacy, fallbackPlatform) {
  global.wx = {
    getStorageSync: storage(values),
    ...(fallbackPlatform !== undefined
      ? {
          getDeviceInfo: () => ({ platform }),
          getSystemInfoSync: () => ({ platform: fallbackPlatform })
        }
      : platform === undefined
      ? {}
      : legacy
      ? { getSystemInfoSync: () => ({ platform }) }
      : { getDeviceInfo: () => ({ platform }) })
  };
  delete require.cache[require.resolve('../miniprogram/api/runtime-base')];
  delete require.cache[require.resolve('../miniprogram/api/http')];
  return require('../miniprogram/api/http');
}

function loadWallet(platform, values) {
  const calls = [];
  const wx = {
    getDeviceInfo: () => ({ platform }),
    getStorageSync: storage({
      ...values,
      'zs-wallet-token-v1': JSON.stringify({ fp: '0:', token: 'wallet-token' })
    }),
    setStorageSync() {},
    removeStorageSync() {}
  };
  global.wx = wx;
  delete require.cache[require.resolve('../miniprogram/api/runtime-base')];
  const runtimeBase = require('../miniprogram/api/runtime-base');
  const http = {
    base: () => publicApiBase,
    sessionGeneration: () => 0,
    currentPostId: () => '',
    request: (...args) => { calls.push(args); return Promise.resolve({}); }
  };
  const source = fs.readFileSync(path.join(root, 'miniprogram/api/wallet.js'), 'utf8');
  const module = { exports: {} };
  vm.runInNewContext(source, {
    module,
    exports: module.exports,
    require(request) {
      if (request === './http') return http;
      if (request === './runtime-base') return runtimeBase;
      throw new Error('unexpected require: ' + request);
    },
    wx,
    Date,
    JSON,
    Promise,
    encodeURIComponent,
    setTimeout,
    clearTimeout
  }, { filename: 'miniprogram/api/wallet.js' });
  return module.exports.pointAccount().then(() => calls[0][4].base);
}

(async () => {
  assert.equal(loadHttp('ios', { 'zs-api-base': oldApiBase }).base(), publicApiBase,
    '真机不得读取指向 A 的历史 API 覆写');
  assert.equal(loadHttp('android', { 'zs-api-base': localApiBase }).base(), publicApiBase,
    '真机不得读取回环联调覆写');
  assert.equal(loadHttp('windows', { 'zs-api-base': oldApiBase }).base(), publicApiBase,
    '桌面微信不得读取历史 API 覆写');
  assert.equal(loadHttp('devtools', { 'zs-api-base': localApiBase }).base(), localApiBase,
    '开发者工具仍允许本地联调覆写');
  assert.equal(loadHttp('devtools', { 'zs-api-base': localApiBase }, true).base(), localApiBase,
    '旧基础库通过 getSystemInfoSync 仍可识别开发者工具');
  assert.equal(loadHttp('ios', { 'zs-api-base': localApiBase }, false, 'devtools').base(), publicApiBase,
    'getDeviceInfo 应优先于旧接口的平台结果');
  assert.equal(loadHttp(undefined, { 'zs-api-base': oldApiBase }).base(), publicApiBase,
    '平台能力缺失时应保守使用 B 默认域名');
  assert.equal(loadHttp('ios', {}).base(), publicApiBase,
    '未配置覆写时应继续使用 B 默认域名');

  assert.equal(await loadWallet('ios', { 'zs-wallet-base': 'https://api2.zszhj.com/app-api' }),
    'https://platform.zszhj.com/app-api', '真机钱包不得读取历史 A 覆写');
  assert.equal(await loadWallet('devtools', { 'zs-wallet-base': 'http://127.0.0.1:58081/app-api' }),
    'http://127.0.0.1:58081/app-api', '开发者工具钱包仍允许本地联调覆写');

  console.log('公网基址隔离：10 组行为通过');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

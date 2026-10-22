const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const auto = require('miniprogram-automator');

const root = path.resolve(__dirname, '..');
const base = path.join(root, 'qa/full-flow-acceptance-20261023');
const project = path.join(root, '.tools/full-flow-acceptance-20261023/native-project');
const port = Number(process.env.FULL_FLOW_PORT || 9983);
const choice = process.argv[2] || '--list';
const available = [];
function read(file) { return JSON.parse(fs.readFileSync(file, 'utf8')); }
for (const [prefix, folder, filename, field] of [['B', 'native-business', 'results.json', 'steps'], ['E', 'native-engineering', 'continuous-results.json', 'steps'], ['D', 'native-design', 'results.json', 'checks'], ['M', 'native-owner', 'results.json', 'scenarios']]) {
  const result = path.join(base, folder, filename);
  if (!fs.existsSync(result)) continue;
  for (const [index, step] of (read(result)[field] || []).entries()) {
    if (step.fixture) available.push({ id: prefix + String(index + 1).padStart(2, '0'), name: step.name, file: path.join(base, folder, step.fixture) });
  }
}
const corrections = path.join(root, 'qa/engineering-record-corrections-20261023/page-scenes.json');
if (fs.existsSync(corrections)) {
  for (const [index, scene] of read(corrections).scenes.entries()) available.push({ id: 'C' + String(index + 1).padStart(2, '0'), name: scene.name, rows: scene.before, url: scene.url });
}

async function main() {
  if (choice === '--list') {
    console.log(available.map(item => item.id + ' ' + item.name).join('\n') || '请先运行 npm run check:flow:native 生成逐环节模拟数据。');
    return;
  }
  const fixture = available.find(item => item.id === choice);
  assert(fixture || choice === '--restore', 'Unknown fixture ' + choice + '; use --list');
  assert(fs.existsSync(path.join(project, 'project.config.json')), '先运行原生验收创建隔离项目');
  execFileSync(process.env.WECHAT_CLI || '/Applications/wechatwebdevtools.app/Contents/MacOS/cli', ['auto', '--project', project, '--auto-port', String(port)], { timeout: 45000, stdio: 'pipe' });
  const mp = await auto.connect({ wsEndpoint: 'ws://127.0.0.1:' + port });
  const snapshot = () => mp.evaluate(() => wx.getStorageInfoSync().keys.filter(key => key.startsWith('zhongshu-')).map(key => [key, wx.getStorageSync(key)]));
  const backup = path.join(project, 'manual-fixture-backup.json');
  try {
    const rows = choice === '--restore' ? read(backup) : fixture.rows || read(fixture.file);
    if (choice !== '--restore' && !fs.existsSync(backup)) fs.writeFileSync(backup, JSON.stringify(await snapshot()));
    await mp.evaluate(() => new Promise(resolve => wx.reLaunch({ url: '/pages/account/index?view=policy', complete: resolve })));
    await mp.evaluate(rows => {
      wx.getStorageInfoSync().keys.filter(key => key.startsWith('zhongshu-')).forEach(key => wx.removeStorageSync(key));
      for (const [key, value] of rows) wx.setStorageSync(key, value);
    }, rows);
    const expected = JSON.stringify([...rows].sort((a, b) => a[0].localeCompare(b[0])));
    assert.equal(JSON.stringify((await snapshot()).sort((a, b) => a[0].localeCompare(b[0]))), expected);
    if (choice === '--restore') fs.rmSync(backup);
    else {
      const session = rows.find(([key]) => key === 'zhongshu-account-v1')?.[1]?.session;
      const home = session?.role === 'hq-design-admin' ? '/pages/workflow/commission/index?view=admin'
        : ['engineer', 'project-manager'].includes(session?.role) ? '/pages/build/home/index'
          : ['finance', 'finance-lead'].includes(session?.role) ? '/pages/management/index'
            : ['boss', 'owner'].includes(session?.role) ? '/pages/admin/home/index' : '/pages/business/index';
      await mp.evaluate(url => wx.reLaunch({ url }), fixture.url || home);
    }
    console.log(choice === '--restore' ? '已恢复隔离项目原模拟数据。' : '已在独立微信项目载入 ' + fixture.id + ' ' + fixture.name + '。六人切换登录会保留同一原项目。');
  } finally { await mp.disconnect(); }
}

main().then(() => process.exit(0), error => { console.error(error.stack); process.exit(1); });

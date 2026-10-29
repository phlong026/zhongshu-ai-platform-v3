const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const directory = path.join(root, '.tools/full-flow-acceptance-20261023/checks');
fs.mkdirSync(directory, { recursive: true });
const checks = [
  ['业务与跨模块回归', ['npm', 'run', 'check:business:simplified']],
  ['管理四页回归', ['npm', 'run', 'check:owner:workbench']],
  ['管理P0缺口', ['node', 'scripts/verify-owner-p0-completion.cjs']],
  ['管理模块完整回归', ['npm', 'run', 'check:management:complete']],
  ['财务页面布局', ['node', 'scripts/verify-finance-layout.cjs']],
  ['工程纠错', ['node', 'scripts/verify-engineering-record-corrections.cjs']],
  ['工程权限及状态约束', ['node', 'scripts/verify-engineering-records-guards.cjs']],
  ['历史与六角色模拟数据', ['npm', 'run', 'check:demo-data']],
  ['六角色登录与迁移', ['npm', 'run', 'check:demo-login']],
  ['六角色连续业务与分支', ['node', 'scripts/verify-six-role-flow.cjs']],
  ['预算导出与历史只读导航', ['node', 'scripts/verify-navigation-current-policy.cjs']],
  ['设计跨设备模拟服务', ['npm', 'run', 'check:design-collaboration']],
  ['待办衔接', ['npm', 'run', 'check:business:todos']],
  ['工程逐步入口连通', ['node', 'scripts/walk-main-chain.cjs', '--depth=4', '--out=qa/full-flow-acceptance-20261023/engineering-navigation']],
  ['六角色入口遍历', ['node', 'scripts/crawl-navigation.cjs', '--strict', '--out=qa/full-flow-acceptance-20261023/role-navigation']],
  ['全角色工作台页面', ['node', 'scripts/verify-franchise-showcase.cjs']],
  ['展示文案', ['npm', 'run', 'check:visible-copy']],
  ['项目静态与包检查', ['npm', 'run', 'check']],
  ['生产依赖漏洞', ['npm', 'audit', '--omit=dev', '--audit-level=high']]
];
const from = Number(process.argv.find(value => value.startsWith('--from='))?.split('=')[1] || 1);
const previous = from > 1 ? JSON.parse(fs.readFileSync(path.join(directory, 'results.json'))) : null;
if (previous && previous.checks.slice(0, from - 1).some(check => check.result !== 'passed')) throw new Error('前序检查未通过，不能续跑');
const report = previous ? { ...previous, result: 'running', resumedAt: new Date().toISOString(), checks: previous.checks.slice(0, from - 1) } : { result: 'running', startedAt: new Date().toISOString(), checks: [] };
const persist = () => fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify(report, null, 2));
persist();
for (const [index, [name, command]] of checks.entries()) {
  if (index < from - 1) continue;
  const log = String(index + 1).padStart(2, '0') + '.log';
  const fd = fs.openSync(path.join(directory, log), 'w');
  const started = Date.now();
  console.log('START ' + (index + 1) + '/' + checks.length + ' ' + name);
  const result = spawnSync(command[0], command.slice(1), { cwd: root, stdio: ['ignore', fd, fd], timeout: 300000 });
  fs.closeSync(fd);
  report.checks.push({ name, command: command.join(' '), result: result.status === 0 ? 'passed' : 'failed', elapsedMs: Date.now() - started, log, error: result.error?.message });
  persist();
  if (result.status !== 0) {
    report.result = 'failed'; persist();
    console.error(fs.readFileSync(path.join(directory, log), 'utf8').slice(-7000));
    process.exit(1);
  }
  console.log('PASS ' + name);
}
report.result = 'passed'; report.finishedAt = new Date().toISOString(); persist();

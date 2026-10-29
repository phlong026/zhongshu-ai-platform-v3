const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const directory = path.resolve(process.env.FULL_FLOW_OUT || 'qa/full-flow-acceptance-20261023', 'native-design');
const source = JSON.parse(fs.readFileSync(path.join(directory, '../native-business/fixtures/B03.json')));
const business = source.find(([key]) => key === 'zhongshu-business-v3')[1];
const customer = business.customers.find(row => row.phone === '13900008823');
assert(customer && customer.ownerId === 'sales-dual');

async function main() {
  const r = await require('./franchise-native-runtime.cjs').connect('six-role-design', { directory, realEvents: true });
  const checks = [];
  const data = () => r.data();
  const waitView = view => r.until(async () => (await data()).view === view, 'design view ' + view, 15000);
  const pass = async (id, name) => {
    const fixture = 'fixtures/' + id + '.json';
    fs.mkdirSync(path.join(directory, 'fixtures'), { recursive: true });
    fs.writeFileSync(path.join(directory, fixture), JSON.stringify(await r.snapshot(), null, 2));
    checks.push({ id, name, result: 'passed', fixture, customerId: customer.id, projectId: customer.projectId });
    await r.capture(id); console.log('PASS ' + id + ' ' + name);
  };
  const input = (key, value) => r.event({ selector: '.explore-input', dataset: { key } }, 'input', { value });
  try {
    await r.open('/pages/account/index?view=policy');
    await r.replace(source);
    await r.login('sales-dual', 'sales');
    await r.open('/pages/workflow/explore/index?view=inspiration', '.explore-case');
    await r.event('.explore-search-open', 'openSearch'); await waitView('search');
    await r.event({ selector: '.explore-input', dataset: { key: 'searchWidth' } }, 'input', { value: '11.6' });
    await r.event({ selector: '.explore-input', dataset: { key: 'searchDepth' } }, 'input', { value: '15.5' });
    await r.event('.explore-search-submit', 'searchPlans');
    assert((await data()).filteredCases.some(row => row.id === 'ZS001'));
    await r.event({ selector: '.explore-case', dataset: { id: 'ZS001' } }, 'openCase'); await waitView('case');
    await r.event({ selector: '.explore-case-tab', dataset: { index: 1 } }, 'caseTab');
    await r.event('.explore-use-case', 'useCase'); await waitView('ai-config');
    assert.equal((await data()).form.width, '11.6');
    await input('floors', '3');
    await r.event({ selector: '.explore-picker', dataset: { key: 'count' } }, 'pick', { value: 2 });
    await r.event('.generate-explore', 'generate'); await waitView('plan-result');
    assert.equal((await data()).result.candidates.length, 4);
    await r.event({ selector: '.explore-candidate', dataset: { index: 2 } }, 'selectCandidate');
    assert.equal((await data()).result.selected, 2);
    await pass('D01', '尺寸检索、分层参考和四个三层候选保留目标尺寸');

    await r.event('.explore-next-facade', 'continueFacade'); await waitView('ai-facade');
    await r.event({ selector: '.explore-picker', dataset: { key: 'count' } }, 'pick', { value: 0 });
    await r.event({ selector: '.explore-picker', dataset: { key: 'resolution' } }, 'pick', { value: 1 });
    await r.event('.generate-explore', 'generate'); await waitView('facade-result');
    assert.equal((await data()).result.candidates.length, 1);
    assert.equal((await data()).result.parameters.resolution, '4K');
    await r.event('.explore-next-budget', 'continueBudget'); await waitView('budget');
    assert.equal((await data()).form.floors, '3');
    await r.event('.calculate-explore', 'calculate'); await waitView('budget-result');
    assert((await data()).result.total > 0);
    await pass('D02', '选用平面后继续立面与预算，层数与分项金额一致');

    await r.event('.save-explore', 'save');
    await r.until(async () => !!(await data()).sheet, 'choose same customer');
    assert((await data()).saveCustomers.some(row => row.id === customer.id));
    await r.event({ selector: '.explore-save-customer', dataset: { id: customer.id } }, 'saveCustomer');
    assert((await data()).saved);
    await r.event('.explore-formal', 'continueFormal'); await waitView('submit');
    assert.equal((await data()).customer.id, customer.id);
    const saved = await r.mp.evaluate(id => wx.getStorageSync('zhongshu-business-v3').customers.find(row => row.id === id), customer.id);
    assert.equal(saved.projectId, customer.projectId);
    assert.equal(saved.ownerId, 'sales-dual');
    assert(saved.plans.length && saved.facades.length && saved.budgets.length);
    await pass('D03', '方案与预算保存至原客户后进入当前设计委托');

    const budget = saved.budgets.at(-1);
    await r.open('/pages/workflow/quote-export/index?id=' + encodeURIComponent(customer.id) + '&kind=budget&version=' + budget.version);
    await r.until(async () => { const d = await data(); if (d.error) throw Error(d.error); return !!d.image && !d.busy; }, 'native budget image', 25000);
    const image = await r.mp.evaluate(file => wx.getFileSystemManager().readFileSync(file, 'base64'), (await data()).image);
    const buffer = Buffer.from(image, 'base64');
    assert.equal(buffer.subarray(1, 4).toString(), 'PNG');
    fs.writeFileSync(path.join(directory, 'same-project-budget.png'), buffer);
    const page = await r.mp.currentPage();
    const pdf = await page.$('.export-pdf'); assert(pdf); await pdf.tap();
    await r.until(async () => { const d = await data(); if (d.error) throw Error(d.error); return !!d.pdfFile; }, 'native budget PDF', 25000);
    const bytes = await r.mp.evaluate(file => wx.getFileSystemManager().readFileSync(file, 'base64'), (await data()).pdfFile);
    const document = Buffer.from(bytes, 'base64'); assert(document.subarray(0, 8).toString().startsWith('%PDF'));
    fs.writeFileSync(path.join(directory, 'same-project-budget.pdf'), document);
    await pass('D04', '同一客户本版预算通过原生Canvas和真实控件生成图片与PDF');
    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result: 'passed', scope: '从主链B03派生的独立方案分支，不覆盖主链最终数据；模拟AI结果，真实微信控件/Canvas/文件', checks, exceptions: r.errors }, null, 2));
  } catch (error) {
    fs.writeFileSync(path.join(directory, 'failure.json'), JSON.stringify({ error: error.stack, checks, page: await r.current() }, null, 2));
    await r.capture('failure'); throw error;
  } finally { await r.restore(); }
}

main().then(() => process.exit(0), error => { console.error(error.stack); process.exit(1); });

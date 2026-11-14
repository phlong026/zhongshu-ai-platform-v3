import test from 'node:test';
import assert from 'node:assert/strict';
import { flatPlanDirectives, formatRequirements, FLAT_DRAWING_TYPES, FLAT_LAYOUT_PRESETS } from '../src/runtime.mjs';

test('平面指令块：默认顶视 + 制式模板 + 负面约束，层数进图名', () => {
  const text = flatPlanDirectives({ floor: '两层', floorCount: 2, drawingType: 'TOP_VIEW' });
  assert.match(text, /正投影顶视视角/);
  assert.match(text, /两层平面布置图/);
  assert.match(text, /指北针/);
  assert.match(text, /禁止透视灭点效果/);
  assert.match(text, /层数严格等于需求层数/);
});

test('图纸类型联动：鸟瞰放行鸟瞰视角，顶视禁止鸟瞰', () => {
  const bird = flatPlanDirectives({ drawingType: 'BIRD_EYE', floorCount: 2 });
  assert.match(bird, /按鸟瞰视角呈现/);
  assert.doesNotMatch(bird, /禁止鸟瞰/);
  const top = flatPlanDirectives({ drawingType: 'TOP_VIEW' });
  assert.match(top, /禁止鸟瞰、轴测与透视/);
  // 非法值回落 TOP_VIEW
  const fallback = flatPlanDirectives({ drawingType: 'HACK' });
  assert.match(fallback, /正投影顶视视角/);
});

test('主入口与院落拓扑注入；缺省时不出块', () => {
  const withAll = flatPlanDirectives({ entranceOrientation: 'SOUTH', courtyardLayout: 'SIHEYUAN' });
  assert.match(withAll, /主入口位于南侧/);
  assert.match(withAll, /四合院落：南侧前院与影壁过渡/);
  const without = flatPlanDirectives({});
  assert.doesNotMatch(without, /主入口位于/);
  assert.doesNotMatch(without, /空间拓扑/);
  assert.equal(FLAT_DRAWING_TYPES.TOP_VIEW.includes('top-down'), true);
  assert.equal(Object.keys(FLAT_LAYOUT_PRESETS).length, 4);
});

test('formatRequirements：新三键不再落入其他需求数据兜底', () => {
  const text = formatRequirements({
    faceWidthM: 22, depthM: 20.5, floorCount: 2, note: '带院子',
    entranceOrientation: 'SOUTH', courtyardLayout: 'SIHEYUAN', drawingType: 'TOP_VIEW'
  });
  assert.match(text, /宅基地面宽22米、进深20.5米/);
  assert.doesNotMatch(text, /其他需求数据/);
});

test('plan 阶段用户消息注入图纸类型/拓扑/负面与参考图优先级句式', async () => {
  const { Provider, Journal } = await import('../src/runtime.mjs');
  const { mkdtempSync, rmSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const dir = mkdtempSync(tmpdir() + '/flat-skel-plan-');
  let captured;
  const provider = new Provider({ base: 'https://fixture.invalid', model: 'fixture', temperature: 0, key: 'k' },
    new Journal(dir, 9),
    async (url, init) => { captured = JSON.parse(init.body); return new Response(JSON.stringify({ choices: [{ message: { content: 'plan' } }] })); });
  const hooks = { reserve: async () => {}, dispatch: async () => {}, confirm: async () => {}, unknown: async () => {}, notSent: async () => {} };
  const images = [{ mimeType: 'image/png', bytes: Buffer.from('x'), sizeBytes: 1, sha256: 'x' }];
  const input = { phase: 'FLAT', requirements: { floorCount: 2, drawingType: 'TOP_VIEW', entranceOrientation: 'SOUTH', courtyardLayout: 'SIHEYUAN', note: '带院子' } };
  await provider.plan({ jobId: '7', phase: 'FLAT', payload: { requestedCount: 1 } }, input, images, new AbortController().signal, hooks);
  const userText = captured.messages[1].content.find(c => c.type === 'text').text;
  assert.match(userText, /【图纸类型】正投影顶视视角/);
  assert.match(userText, /主入口位于南侧/);
  assert.match(userText, /四合院落：南侧前院与影壁过渡/);
  assert.match(userText, /所选平面图是主要设计参考/);
  assert.doesNotMatch(userText, /仅辅助布局|仅用于学习布局组织/);
  rmSync(dir, { recursive: true, force: true });
});

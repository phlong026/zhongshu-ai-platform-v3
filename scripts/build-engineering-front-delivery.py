"""Package the verified native front end and its local acceptance evidence."""
from pathlib import Path
import collections
import hashlib
import html
import json
import zipfile
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parent.parent
qa = root / 'qa/engineering-native-20261021'
read = lambda p: json.loads(p.read_text(encoding='utf-8'))
main = read(qa / 'continuous-results.json')
visual = read(qa / 'visual-final-results.json')
assert main['result'] == visual['result'] == 'passed'
assert visual['storageRestored'] and visual['personnelTransfer']['result'] == 'passed'
assert main['initialSeeds'] == 1 and len(main['steps']) == 16
files = []
for file in sorted((root / 'miniprogram').rglob('*')):
    if not file.is_file():
        continue
    relative = file.relative_to(root).as_posix()
    assert file.read_bytes() == (qa / 'project' / relative).read_bytes(), relative
    files.append({'path': relative, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest(), 'bytes': file.stat().st_size})
(qa / 'final-source.json').write_text(json.dumps(files, ensure_ascii=False, indent=2), encoding='utf-8')

screens = [
    'flow-01-business-handover.png', 'flow-02-engineer-receive.png',
    'flow-04-prepare-form.png', 'flow-08-node-funding.png',
    'flow-07-start.png', 'flow-09-node-material-plan.png',
    'flow-10-arrival-history.png', 'flow-12-node-funding-paid.png',
    'flow-14-node-inspection.png', 'flow-15-prepaid-arrival.png',
    'flow-17-contract-paid.png', 'flow-18-node-expense.png',
    'flow-21-archived.png', 'flow-22-warranty.png',
    'flow-24-financial-closed.png', 'flow-25-final-node.png'
]
short = ['业务移交', '工程接收', '开工准备与金额', '原项目改期', '实收后实际开工', '节点资金与材料',
         '分批确认到货', '财务原单付款', '节点记录与验收', '先款后货', '封顶及两期收齐',
         '节点人工费用', '交付与归档', '原项目保修', '财务结清', '原入口回查']
font_path = Path('C:/Windows/Fonts/msyh.ttc')
font = ImageFont.truetype(str(font_path), 17) if font_path.exists() else ImageFont.load_default()
title_font = ImageFont.truetype(str(font_path), 23) if font_path.exists() else font

# Exact screenshot panels, scaled uniformly; no product pixels are synthesized.
sheet = Image.new('RGB', (920, 2090), '#faf8f4')
draw = ImageDraw.Draw(sheet)
draw.text((22, 15), '微信原生同一项目：业务移交 → 工程交付 → 财务结清', font=title_font, fill='#89512a')
for index, file in enumerate(screens):
    panel = Image.open(qa / file).convert('RGB')
    panel.thumbnail((208, 450))
    x, y = 18 + (index % 4) * 228, 65 + (index // 4) * 505
    draw.text((x, y), f'{index + 1:02d} {short[index]}', font=font, fill='#141312')
    sheet.paste(panel, (x, y + 35))
sheet.save(qa / '主链原生页面总览.png')
comparison = Image.new('RGB', (800, 970), '#faf8f4')
draw = ImageDraw.Draw(comparison)
for index, (file, label) in enumerate([('before-1-prepare.png', '改造前：必填项在多屏之后'), ('final-07-prepare.png', '交付版：必填项前置、底部保存')]):
    panel = Image.open(qa / file).convert('RGB')
    panel.thumbnail((375, 840))
    x = 18 + index * 395
    draw.text((x, 20), label, font=font, fill='#89512a')
    comparison.paste(panel, (x, 65))
comparison.save(qa / '开工准备前后对照.png')

counts = dict(collections.Counter(e['via'] for e in main['events']))
summary = {'result': 'passed', 'date': '2026-10-21', 'nativeSteps': len(main['steps']),
           'initialSeeds': main['initialSeeds'], 'eventCounts': counts, 'final': main['final'],
           'visualPages': len(visual['metrics']), 'personnelTransfer': visual['personnelTransfer'],
           'visualStateRestored': visual['storageRestored'], 'sourceFiles': len(files),
           'scope': 'WeChat DevTools native simulator. Local sample attachments; no live payment, upload, or physical-device claim.'}
(qa / 'delivery-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
steps_md = '\n'.join(f"| {step['step']} | {step['name']} | 通过 | [{screens[i]}]({screens[i]}) |" for i, step in enumerate(main['steps']))
metrics_md = '\n'.join(f"| {m['name']} | {m['body']['font']} | {m.get('primary', {}).get('font', '—')} | {m.get('primary', {}).get('height', '—')} |" for m in visual['metrics'])
report = f'''# 工程模块前端交付验收

2026年10月21日。微信开发者工具原生页面主链、最终页面样式及原项目换人接续均通过。工程每个项目一名经办人，以施工节点集中关联施工记录、验收、材料计划、资金计划；客户工程款仍为开工款、封顶款两期。

## 交付结果

- 同一项目从业务正式移交开始，完成工程接收、合同及两期金额确认、开工款、实际开工、材料和节点、主体封顶及封顶款、人工费用、交付归档、保修，最终财务结清。
- 5个施工节点、2份材料计划、开工款分2笔及封顶款1笔，合计工程合同实收100,000元。材料到货、付款与节点验收分别按实际记录办理。
- 原生主链16组检查通过；原生事件分类为 {json.dumps(counts, ensure_ascii=False)}。文件附件使用本地示例值，其余文本、选择与提交通过原生控件完成。
- 正文28rpx、说明24rpx、区块标题32rpx、导航标题30rpx，主按钮28rpx及至少88rpx高。下表为当前模拟机型实测，不代表所有设备固定为相同px。

## 原生主链逐步结果

| 步骤 | 页面操作 | 结果 | 原始截图 |
| --- | --- | --- | --- |
{steps_md}

连续验收项目：`{main['projectId']}`；工程记录：`{main['engineeringId']}`。最终已交付、已归档、已财务结清。连续主链仅起始安装一次前置测试资料，后续未按阶段替换工程数据。自动化跳转等待、必填资料及目标项目定位的重试沿同一份存储继续，详情保留在[原生事件与重试记录](continuous-results.json)。

![主链原生页面总览](主链原生页面总览.png)

## 节点与入口

施工记录和验收记录留在原节点；材料计划携带原节点，分批实收及实际付款回原计划；节点资金计划记录预计用款，汇总本节点材料及人工等费用的已记录金额、已付及待付，不生成重复付款。项目层汇总进度和两期收款。

工期、工程人员在原项目直接记录并保留历史。已用原生项目选择器更换为李工程师，再通过本地登录页进入同一个项目；此专项使用隔离前置资料，未冒充主链的中途换人。专项结束后已恢复原生主链的结清数据。

归档后节点、材料、资金记录可回查；资金计划呈只读，保修和原款项保留原事项入口。历史移交待办按实际可操作权限生成，工程身份不会看到无法办理的历史接收按钮。

## 样式实测

| 页面 | 同级正文 | 主按钮字号 | 主按钮高度px |
| --- | --- | --- | --- |
{metrics_md}

六类页面的正文及主按钮实测字号一致，主按钮高度均为45px。原生截图逐页检查了标题、卡片、输入框、按钮、底部安全区和原节点关联。未声明完整无障碍合规；真机键盘、系统字体放大及辅助技术仍需设备验收。

![开工准备前后对照](开工准备前后对照.png)

## 本轮修复

| 问题 | 交付版行为 |
| --- | --- |
| 工程页重复返回入口 | 使用统一页头返回与工作台入口 |
| 开工准备必填项过深 | 合同及现场记录前置，计划可展开，保存固定在底部 |
| 不同页面字号与控件不一致 | 共用字号、字体、品牌色、卡片和主按钮规则 |
| 节点四类记录分散 | 节点内直接进入施工、验收、材料及资金 |
| 材料与费用缺少明确节点提示 | 原计划显示所属节点，项目费用自动带入节点 |
| 运费0缺少字段说明 | 明确显示供应方、单价、运费标签 |
| 未记录合同显示0应收 | 改为待记录合同、待约定 |
| 已完成事项仍露出输入表单 | 齐货、付清及归档后的相关页面显示记录 |
| 完成节点后回查现场记录不明确 | 节点菜单可定位原现场与验收记录 |
| 换人后演示登录入口缺失 | 可选工程人员保留登录入口，新经办人原项目继续 |

## 复测

```powershell
npm run check:engineering:records
npm run check:engineering
npm run check:finance
npm run check:business:simplified
node scripts/verify-engineering-finance-closure.cjs
npm run prepare:engineering:record-flow:native
```

随后用微信官方CLI对准备好的 `qa/engineering-native-20261021/project` 启用 `auto --auto-port 9894 --trust-project`，执行 `npm run check:engineering:record-flow:native`。最终样式及人员接续专项使用 `node scripts/verify-engineering-records-native.cjs visual-final`，它保留、恢复当前验收数据。

## 证据与边界

[连续最终状态](continuous-final-state.json)、[最终源码指纹](final-source.json)、[页面样式及人员专项](visual-final-results.json)、[验收摘要](delivery-summary.json)分别保留。样式专项的阶段资料和主链证据分开，结束后恢复原结清状态。

本次交付是原生微信小程序前端源码及可复核页面。使用隔离游客项目、本地模拟账号及示例附件，没有真实转账或外部通知，未上传或发布微信版本。系统文件选择器、真实服务端、多设备同步及iOS/Android真机尚未纳入本次验证。
'''
(qa / '前端交付验收.md').write_text(report, encoding='utf-8')
cards = ''.join(f'<section><h2>{s["step"]:02d} {html.escape(s["name"])}</h2><p>原生页面检查通过</p><a href="{image}"><img src="{image}" alt="{html.escape(s["name"])}"></a></section>' for s, image in zip(main['steps'], screens))
extras = ''.join(f'<section><h2>最终页面 · {html.escape(f)}</h2><img src="{f}" alt="最终页面截图"></section>' for f in visual['screens'])
html_report = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>工程前端交付验收</title><style>body{{font:16px/1.7 -apple-system,"Microsoft YaHei",sans-serif;color:#141312;background:#faf8f4;margin:0;padding:28px}}main{{max-width:1260px;margin:auto}}h1{{color:#89512a}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:24px}}section{{background:white;border:1px solid #eee8df;border-radius:16px;padding:20px}}h2{{font-size:18px;margin:0}}img{{display:block;width:100%;max-width:375px;height:auto;margin:16px auto}}p{{color:#6e737c}}.wide{{max-width:100%;width:auto}}</style><main><h1>工程模块前端交付验收</h1><p>2026年10月21日 · 原生主链16项通过 · 最终样式与人员接续通过</p><p>一项目一名工程人员；每个施工节点关联施工、验收、材料、资金计划；项目保留开工款、封顶款两期。使用本地示例附件，未发起真实转账，未上传发布，尚未真机验收。</p><img class="wide" src="开工准备前后对照.png" alt="改造前后原生截图对照"><h1>同一项目完整主链</h1><div class="grid">{cards}</div><h1>最终源码的页面及专项</h1><p>以下样式与换人专项使用隔离前置资料，结束后恢复已结清的连续主链数据。</p><div class="grid">{extras}</div></main></html>'''
(qa / '验收报告.html').write_text(html_report, encoding='utf-8')

delivery = root / 'deliverables'
delivery.mkdir(exist_ok=True)
archive = delivery / '工程模块前端_20261021.zip'
readme = '''# 工程模块前端交付

解压后，将本目录导入微信开发者工具，项目配置已包含 miniprogramRoot。源码保持原工程项目配置；本次原生验收采用独立游客副本。

工程一项目一名人员，每个节点统一办理施工记录、验收、材料计划、资金计划。客户按开工款、封顶款两期收款，实际财务办理沿原事项回写。工期及人员在原项目直接调整。

新业务移交工程使用简化记录流程；历史项目保留原有记录及兼容入口。本地模拟账号可在登录页选择，工程人员变更后新经办人仍可进入同一项目。

验收资料/验收报告.html 可离线打开查看完整原生页面。验收使用示例附件与本地数据，未发起真实转账、未发布。真实账号、服务器持久化、多设备同步和真机验收沿实际上线工作另行完成。

当前桌面独立微信验收项目保留同一项目的已交付、已归档、财务已结清状态，可继续回查节点。
'''
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
    bundle.writestr('README.md', readme)
    bundle.write(root / 'project.config.json', 'project.config.json')
    for f in files:
        bundle.write(root / f['path'], f['path'])
    evidence = set(screens) | set(main['screens']) | set(visual['screens']) | {'主链原生页面总览.png', '开工准备前后对照.png', '验收报告.html', '前端交付验收.md', 'continuous-results.json', 'continuous-final-state.json', 'visual-final-results.json', 'delivery-summary.json', 'final-source.json'}
    for file in sorted(evidence):
        if (qa / file).exists():
            bundle.write(qa / file, '验收资料/' + file)
with zipfile.ZipFile(archive) as bundle:
    assert bundle.testzip() is None
    for f in files:
        assert hashlib.sha256(bundle.read(f['path'])).hexdigest() == f['sha256']
summary['zip'] = str(archive)
summary['zipBytes'] = archive.stat().st_size
summary['zipSha256'] = hashlib.sha256(archive.read_bytes()).hexdigest()
(qa / 'delivery-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False, indent=2))

const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'..'),dir=path.join(root,'qa/role-remediation-20261018');
const read=f=>JSON.parse(fs.readFileSync(path.join(dir,f),'utf8'));
const model=[read('model-results.json'),read('model-extra-results.json')],pages=read('pages/results.json'),native=read('native/results.json'),final=read('native-final/results.json');
if([...model,pages,native,final].some(r=>r.result!=='passed')||native.checks.length!==25||final.checks.length!==6)throw Error('Final role verification is incomplete');
if(!read('native/restored.json').restored||!read('pages/restored.json').restored||!read('native-final/restored.json').restored)throw Error('Test storage not restored');
const issueRows=[
 ['R01','个人客户与工程项目隔离','按公司、实际人员、项目指派及具体动作校验；详情、列表、数量、提交与草稿统一。','业务员互访、经理互访、销售进入工程均阻止；跨公司、停用、撤岗即时失效。',[3,16]],
 ['R02','跨岗位与组织办理','总部受理、派单、设计、下发、客户确认和项目接收由实际获授人员分别办理。','业务员、老板不能冒充总部；页面导航不再改变实际角色。',[10,11,13,15]],
 ['R03','业务原单退回补证','显示真实状态和退回原因；原单当前责任人可补证，保留金额、流水、文件历史。','1000元原收款退回 → 原业务员补证 → 财务负责人核算。',[5]],
 ['R04','独立审批和财务复核','付款申请、独立审批、财务登记、另一人员复核分开；登记后为待复核，额度先占用。','6000元首款、6000元尾款及300元供应款均复核后才回写；拒绝自批、自核、重复和超额。',[7,8,9,12,20]],
 ['R05','岗位与模拟账号','补齐业务负责人、财务负责人和独立老板身份；15个本地模拟人员通过登录页选择。','六类目标岗位与总部、客户均已验证。真实短信/微信业务登录、服务端权限与跨设备存储仍未接入。',[1,3,9,11,13,22]],
 ['R06','实际操作留痕','统一写入actorId、公司、角色、时间、原对象和项目；交接保留原作者。','跟进、设计、审批、付款、复核、客户确认与接收对应实际登录人员。',[2,9,11,13,14,21]],
 ['R07','无会话访问','默认未登录，缺失会话进入登录；拒绝时清空旧人员对象、表单及列表。','无会话、停用、撤回成员与岗位均不能读取原业务。',[1,16]],
 ['R08','客户责任转交','工作绑定customerId/projectId，明确客户责任语义，原单和工作权限原子更新。','转交刘业务后张业务失去可见权，刘业务取得原对象，历史作者保留；失败整笔回滚。',[21,25]],
 ['R09','真实首页待办','业务、财务、总部和经理队列从当前人员授权的实际对象及事件生成。','新客户与跟进进入真实工作台；指定经理收到同一工程移交。',[2,7,15,22]],
 ['R10','页面与字段权限','普通财务不能进入人员、分工和责任转交；拒绝提示说明原访问功能；财务/HQ字段按岗位投影。','财务人员管理被拒绝；总部不取得未授权费用与付款凭据；个人草稿不串岗。',[10,16,22]],
 ['R11','工程原收款补证','稳定recordId更新；去重同时排除该工程原记录与管理来源投影。','2000元原收款退回 → 原经理保留原流水补证 → 独立核算完成。',[18]],
 ['R12','供应状态和依据','按实付累计区分待付款、部分付款、已付清；结果引用实际来源和本期依据。','10包×30元显示300元供应应付；0实付显示待付款，300元复核后以30000分回写。',[19,20]]
];
const roles=[
 ['业务员','张业务、刘业务','建客、跟进、方案/预算、实测、合同、设计需求、尾款申请、原单补证、资料与责任转交',[2,3,4,5,6,7,12,14,21,23,24]],
 ['业务负责人','赵经理','团队客资分配、服务费核定、独立审批、客户责任转交',[3,6,12,21,23]],
 ['项目经理','李经理、王经理','指定工程接收、计划、六项准备、实际开工、收款补证、材料供应与补图接收；未指派经理被拒绝',[15,16,17,18,19,20,24]],
 ['财务专员','王财务、李财务','审批后登记首尾款与供应款、补充本人付款凭据；重复和超额拦截',[8,9,12,20,22]],
 ['财务负责人','陈财务负责人','独立核算收款、退回付款、核对原财务补证；复核后才计有效金额',[5,9,12,18,20]],
 ['老板','赵老板','本店经营汇总、设计首款及工程供应款独立审批；没有总部办理权',[7,8,10,20,22]],
 ['总部协作','受理员、派单员、设计师、下发员、专业审核人','在受托项目按本人岗位处理；设计实际开始与提交分别记录，专业退回后补图重审',[10,11,12,14,24]],
 ['客户本人','陈先生','仅确认本人项目当前已下发成果及签署协议后的变更成果；员工不能代办',[13,23]]
];
const e=s=>String(s??'').replace(/[&<>"']/g,x=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[x]));
const steps=ns=>ns.map(n=>'<a href="#step-'+n+'">'+n+'</a>').join('、');
const modelCount=model.reduce((n,r)=>n+r.checks.length,0),at=new Date().toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});
const checks=native.checks.map(r=>'<tr id="step-'+r.step+'"><td>'+r.step+'</td><td>'+e(r.name)+'</td><td class="ok">通过</td></tr>').join('')+final.checks.map((r,i)=>'<tr><td>专项'+(i+1)+'</td><td>'+e(r.name)+'</td><td class="ok">通过</td></tr>').join('');
const accepted=native.captures.filter(r=>![11,15,16,24,25,26,30].includes(Number(r.file.slice(12,14)))).map(r=>({...r,href:'native/'+r.file})).concat(final.captures.map(r=>({...r,step:'专项',href:'native-final/'+r.file})));
const gallery=accepted.map(r=>'<figure><a href="'+e(r.href)+'"><img loading="lazy" src="'+e(r.href)+'" alt="'+e(r.name)+'"></a><figcaption>'+e(r.who)+' · 步骤'+r.step+'<br>'+e(r.name)+'</figcaption></figure>').join('');
const walk=folder=>fs.readdirSync(folder,{withFileTypes:true}).flatMap(x=>x.isDirectory()?walk(path.join(folder,x.name)):[path.join(folder,x.name)]);
const manifest=walk(path.join(root,'miniprogram')).map(f=>({path:path.relative(root,f).replace(/\\/g,'/'),bytes:fs.statSync(f).size,sha256:crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex')}));
fs.writeFileSync(path.join(dir,'delivery-manifest.json'),JSON.stringify({at,source:'根目录 miniprogram；原生回归直接测试此目录',files:manifest},null,2));
fs.writeFileSync(path.join(dir,'summary.json'),JSON.stringify({at,result:'passed-local',roles,issues:issueRows,modelChecks:modelCount,pageChecks:pages.checks.length,nativeChecks:native.checks.length,nativeScreenshots:accepted.length,nativeFinalChecks:final.checks.length,nativeExceptions:native.errors.length+final.errors.length,storageRestored:true,limits:['本地演示账号；未接入真实业务身份认证','未验证服务端权限、跨设备同步、真实银行及外部文件服务']},null,2));
const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>多角色权限与业务流转修复验收</title><style>*{box-sizing:border-box}body{margin:0;background:#f5f3ee;color:#263337;font:15px/1.8 "Microsoft YaHei",sans-serif}main{max-width:1220px;margin:auto;padding:30px 24px 70px}h1{font-size:30px;line-height:1.4}h2{font-size:22px;margin-top:38px}p{margin:12px 0}a{color:#985021}header,.box{background:white;padding:24px;border:1px solid #e4ded4;border-radius:12px;margin:18px 0}.ok{color:#196d50;font-weight:700}.scope{background:#fff2d8;padding:15px;border-left:3px solid #b47627}.muted{color:#667379;font-size:13px}table{width:100%;border-collapse:collapse;background:white;font-size:14px}th,td{border:1px solid #ddd7cc;padding:11px;text-align:left;vertical-align:top}th{background:#eee9df}.scroll{overflow:auto}.gallery{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:20px}figure{margin:0;background:white;padding:8px;border:1px solid #e3ded5;border-radius:10px}figure img{width:100%;border-radius:8px}figcaption{font-size:12px;color:#637078;overflow-wrap:anywhere}.stats{display:flex;gap:15px;flex-wrap:wrap}.stats b{font-size:25px;color:#8d4f22}.stats span{flex:1;background:#f7f3eb;padding:12px;min-width:150px}nav{display:flex;gap:20px;flex-wrap:wrap}@media(max-width:600px){main{padding:16px 10px}header,.box{padding:16px}h1{font-size:24px}}@media print{body{background:white}.gallery{grid-template-columns:repeat(4,1fr)}nav{display:none}figure{break-inside:avoid}}</style><main>
<header><p class="muted">${e(at)} · 最终交付源码 · 微信开发者工具官方模拟器</p><h1>多角色权限与业务流转<br>修复验收结果</h1><p class="ok">本地模拟多人流程回归通过；原报告12组问题逐项处理，业务负责人、财务负责人、老板具有独立身份。</p><div class="stats"><span><b>${modelCount}</b><br>模型权限与业务回归通过</span><span><b>${pages.checks.length}</b><br>实际Page/WXML联调通过</span><span><b>${native.checks.length}</b><br>原生跨角色流程通过，另6项页面专项通过</span><span><b>${accepted.length}</b><br>本轮原生截图</span></div><p>验证同一项目依次经过业务、负责人、财务、财务负责人、总部、客户本人及项目经理。付款登记不直接生效；原单退回后由原经办人补证，再由另一人员核对。原生未捕获异常：${native.errors.length}。测试前后本地记录已恢复。</p><p class="scope">范围：现有本地小程序原型。模拟人员通过登录页选择，使用本机共享业务存储。真实短信/微信业务登录、后端鉴权、跨设备并发同步、银行核验和云文件未接入；本报告不代表生产安全或真实付款验收。微信开发者工具的扫码登录仅用于运行模拟器。</p><nav><a href="#roles">逐角色</a><a href="#issues">原报告问题</a><a href="#flow">实测流转</a><a href="#checks">25段回归</a><a href="#evidence">原始证据</a><a href="#shots">截图</a></nav></header>
<h2 id="roles">逐角色结果</h2><div class="scroll"><table><tr><th>角色</th><th>模拟人员</th><th>已完成办理与限制</th><th>原生步骤</th></tr>${roles.map(r=>'<tr><td>'+e(r[0])+'<br><span class="ok">本地通过</span></td><td>'+e(r[1])+'</td><td>'+e(r[2])+'</td><td>'+steps(r[3])+'</td></tr>').join('')}</table></div>
<h2 id="issues">原报告问题逐项处理</h2><div class="scroll"><table><tr><th>编号</th><th>问题</th><th>最终修复</th><th>验证与边界</th><th>步骤</th></tr>${issueRows.map(r=>'<tr><td>'+r[0]+'</td><td>'+e(r[1])+'</td><td>'+e(r[2])+'</td><td>'+e(r[3])+'</td><td>'+steps(r[4])+'</td></tr>').join('')}</table></div>
<h2 id="flow">跨人数据流转</h2><div class="box"><p>业务员建立客户并保留稳定项目编号 → 需求及负责人核费 → 老板或业务负责人独立审批 → 财务登记首款6000元 → 财务负责人退回 → 原财务补证 → 财务负责人独立核对 → 总部受理、派单、设计师实际开始并提交 → 尾款6000元独立审批与复核 → 总部下发 → 客户本人确认 → 施工图独立专业审核 → 指定项目经理接收，同一项目保持待开工。</p><p>经理编制计划、核查六项条件后单独登记实际开工；工程收款2000元被退回后，原经理按原单号和原流水补证并完成核算。10包×30元供应事项经老板审批、财务登记、负责人复核后回写30000分；300.01元超额被拦截。</p><p>客户责任转交给刘业务后，原作者和历史保留。设计变更退回后新版重提、独立审批、双方补充协议、客户本人确认、补图v2审核退回、v3独立审核通过、指定经理接收补充资料连续完成。</p></div>
<h2 id="checks">原生25段主流程与6项页面专项</h2><div class="scroll"><table><tr><th>步骤</th><th>检查结果</th><th>结论</th></tr>${checks}</table></div>
<h2 id="evidence">原始证据与复现</h2><div class="box"><p><a href="native/results.json">原生完整结果</a> · <a href="native-final/results.json">最终6项页面专项</a> · <a href="pages/results.json">Page/WXML联调结果</a> · <a href="model-results.json">32组核心模型结果</a> · <a href="model-extra-results.json">24组补充模型结果</a> · <a href="native/final-business-state.json">本轮同项目最终状态</a> · <a href="native/restored.json">本地记录恢复校验</a> · <a href="delivery-manifest.json">最终源码SHA-256清单</a> · <a href="before-changes.zip">修改前备份</a></p><p>复现命令：<code>npm run check</code>；<code>npm run check:roles:model</code>；<code>npm run check:roles:pages</code>。微信开发者工具以根目录项目启动9720自动化端口后执行<code>npm run check:roles:simulator</code>。测试脚本备份并恢复本机业务记录；仅测试数据使用模拟附件。</p><p>原生主链共25段，另对补证返回、受理阶段文案、总部办理后返回入口、工程补证回执和经理款项只读展示及客户变更只读回执完成6项最终专项复测；对应旧截图已从验收图集排除。原生通过真实登录页选择人员，点击实际渲染的按钮及输入控件；文件字段和复杂表单通过官方自动化设值，不触发真实银行付款或外部通知。Page/WXML联调使用Node运行实际页面函数和模板条件，属于补充集成证据，截图均来自官方模拟器。</p><p>主要实现：<code>utils/access.js</code>、<code>utils/demo-users.js</code>、<code>utils/business.js</code>、<code>utils/finance-ledger.js</code>、<code>pages/admin/finance-actions.js</code>、<code>pages/admin/finance-save.js</code>，以及业务、工程、管理和账号页面。源码位置为根目录<code>miniprogram/</code>。原历史报告保留。</p></div>
<h2 id="shots">原生页面截图</h2><div class="gallery">${gallery}</div></main></html>`;
const report=path.join(dir,'多角色权限与业务流转修复验收报告.html');fs.writeFileSync(report,html);console.log(JSON.stringify({report,modelCount,pageChecks:pages.checks.length,nativeChecks:native.checks.length,nativeFinalChecks:final.checks.length,screenshots:accepted.length}));

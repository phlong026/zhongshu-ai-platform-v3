/**
 * 平台设计赋能视图模型（阶段 1 · P3）：灵感库/AI 平面/AI 立面/快速预算。
 * 纯函数，零 wx/Storage 依赖。契约=docs/backend-parity/13-阶段1-P3设计赋能接入-需求与验收.md（V3，子代理 R3 PASS）。
 * 序列化事实：Long 超界字符串/OffsetDateTime 毫秒；错误原码透传（design 与 1_112 段）；@post.has 拒绝=body.code=403。
 */
const DESIGN_ROLES = ['sales', 'sales-lead', 'hq-design-admin'];

/** 五态归并：design:use 三岗 → api；其余 → demo（继承 P1 pageMode 语义：token 必须有效） */
function pageMode(token, post) {
  return token && post && DESIGN_ROLES.includes(post.roleCode) ? 'api' : 'demo';
}

/** 稳定编码映射（08 §三 P3 + 13 §二） */
const STYLE_CODE = { 新中式: 'NEW_CHINESE', 现代简约: 'MODERN' };
const ROOF_CODE = { 坡屋顶: 'GABLE_ROOF', 平屋顶: 'FLAT_ROOF' };
const MATERIAL_OPTIONS = [['WHITE_STUCCO', '白墙涂料'], ['GREY_STONE', '灰石'], ['WHITE_COAT', '白色涂层']];
const COLOR_OPTIONS = [['DEEP_WOOD', '深木'], ['WARM_GREY', '暖灰'], ['BLACK', '黑']];
const ORIENTATION_OPTIONS = [['PORTRAIT', '竖图'], ['LANDSCAPE', '横图']]; // 平面缺省 PORTRAIT、立面缺省 LANDSCAPE（不传走后端默认）
// 对齐旧版基准（api.zszhj.com）平面表单四项输入（后端需求白名单键已在）
const ENTRANCE_OPTIONS = [['SOUTH', '朝南'], ['NORTH', '朝北'], ['EAST', '朝东'], ['WEST', '朝西']];
const FAMILY_OPTIONS = [['2室1厅1卫', '2室1厅1卫'], ['3室1厅1卫', '3室1厅1卫'], ['3室2厅2卫', '3室2厅2卫'], ['4室2厅2卫', '4室2厅2卫'], ['5室2厅3卫', '5室2厅3卫'], ['6室3厅3卫', '6室3厅3卫']];
const COURTYARD_OPTIONS = [['', 'AI 智能安排'], ['SIHEYUAN', '四合院'], ['FRONT_BACK_YARD', '前后院'], ['FRONT_YARD_SCREEN', '前院影壁'], ['COMPACT', '紧凑布局']];
const DRAWING_OPTIONS = [['TOP_VIEW', '俯视图'], ['COLOR_PLAN', '彩色平面'], ['CAD_LINE', 'CAD 线稿'], ['AXONOMETRIC', '轴测图'], ['BIRD_EYE', '鸟瞰图']];
const STRUCTURE_OPTIONS = [['BRICK', '砖混'], ['FRAME', '框架'], ['STEEL', '钢结构']];
const GRADE_OPTIONS = [['A', 'A 档'], ['B', 'B 档'], ['C', 'C 档']];

function styleCode(style) { return STYLE_CODE[style] || null; }
function roofCode(roof) { return ROOF_CODE[roof] || null; }

/** AI 轮询状态机（S1-2：间隔 3s、上限 90s；终态含 PARTIALLY_SUCCEEDED） */
const POLL_INTERVAL_MS = 3000;
const POLL_MAX_MS = 90000;
function pollDecision(status, elapsedMs) {
  if (status === 'SUCCEEDED' || (status === 'PARTIALLY_SUCCEEDED')) return { action: 'candidates' };
  if (status === 'FAILED') return { action: 'failed', message: '生成失败，请重试或调整参数' };
  if (elapsedMs >= POLL_MAX_MS) return { action: 'timeout', message: '生成仍在进行，可稍后手动刷新' };
  return { action: 'continue' };
}
/** 手动刷新命中未终态（R11/R14 P-10 业务码）→ 仍在生成文案 */
const STILL_RUNNING_MESSAGE = '仍在生成，请稍后再试';
/** 取消状态文案（对齐旧版 1.5 轮询状态机） */
const STATUS_TEXT = { QUEUED: '排队等待中…', RUNNING: 'AI 正在绘制方案…', VALIDATING: '正在校验生成结果…', CANCEL_REQUESTED: '正在取消任务…' };
/** 渐近进度条（旧版公式：RUNNING 14+76*(1-e^(-t/45))、VALIDATING 90+8；只抬高不回落） */
function smoothProgress(status, elapsedMs, serverProgress) {
  const t = Math.max(0, elapsedMs) / 1000;
  const est = status === 'VALIDATING' ? 90 + 8 : 14 + 76 * (1 - Math.exp(-t / 45));
  const server = Number(serverProgress) || 0;
  return Math.max(Math.min(99, Math.round(Math.max(est, server))), 5);
}
/** 预计时间文案：3~4 分钟/张 */
function estimateText(count) { return '预计 ' + (count || 1) * 3 + '~' + (count || 1) * 4 + ' 分钟/张'; }

/** R4 案例 → 列表/详情渲染（coverAssetId 经 R8 现取） */
function caseRow(c) {
  const parameters = c.parameters || c;
  return {
    id: String(c.caseId != null ? c.caseId : c.id),
    title: c.title || '', styleCode: parameters.styleCode || '', styleLabel: c.styleLabel || '',
    floorCount: parameters.floorCount, buildingArea: parameters.buildingArea, faceWidth: parameters.faceWidth, depth: parameters.depth,
    versionId: c.caseVersionId == null ? '' : String(c.caseVersionId), version: c.version,
    coverAssetId: c.coverAssetId || null, description: c.description || '',
    coverUrl: '', coverLoading: !!c.coverAssetId, coverError: false
  };
}

/** 公开案例短链只留在当前页面；单批最多三张并取，避免为列表同时请求二十张图片。 */
async function loadCaseCovers(rows, download, alive = () => true, onRow) {
  const result = rows.map(row => Object.assign({}, row));
  let next = 0, stopped = false;
  async function worker() {
    while (!stopped && alive()) {
      const index = next++;
      if (index >= result.length) return;
      const row = result[index];
      row.coverError = false;
      if (row.coverAssetId) {
        try {
          const value = await download(String(row.id), String(row.coverAssetId));
          if (!value || !value.downloadUrl) throw new Error('图片地址暂不可用');
          row.coverUrl = value.downloadUrl;
        } catch (err) {
          if (err && err.code === 401) { stopped = true; throw err; }
          row.coverUrl = '';
          row.coverError = true;
        }
      }
      row.coverLoading = false;
      if (alive() && onRow) onRow(row, index);
    }
  }
  await Promise.all(Array.from({ length: Math.min(3, result.length) }, () => worker()));
  return result;
}

/** R19 → 预算区间单价展示（元/㎡） */
function ruleInfoView(data) {
  if (!data) return { available: false, lowText: '', highText: '', ruleVersionId: '' };
  return {
    available: true,
    lowText: (data.lowCentsPerSqm / 100).toFixed(2),
    highText: (data.highCentsPerSqm / 100).toFixed(2),
    ruleVersionId: data.ruleVersionId != null ? String(data.ruleVersionId) : ''
  };
}

/** R20 → 预算结果渲染（区间 + disclaimer） */
function estimateView(data) {
  return {
    estimateId: String(data.estimateId),
    lowText: (data.totalLowCents / 100).toFixed(2),
    highText: (data.totalHighCents / 100).toFixed(2),
    ruleVersionId: data.ruleVersionId != null ? String(data.ruleVersionId) : '',
    disclaimer: data.disclaimer || ''
  };
}

/** AI 候选 → 渲染模型（图 assetId 经 R8 现取） */
function candidateRows(candidates) {
  return (candidates || []).map((c, i) => ({
    index: i,
    candidateId: String(c.candidateId != null ? c.candidateId : (c.id != null ? c.id : i)),
    assetId: c.assetId || null
  }));
}

/** 项目列表 → 渲染模型（D1 我的方案：封面/状态/恢复动作徽标） */
const RESUME_TEXT = { VIEW_RESULT: '查看结果', POLL_JOB: '生成中·可恢复', SELECT_FLAT: '待选平面', SELECT_ELEVATION: '待选立面', CREATE_FLAT_JOB: '继续生成平面', CREATE_ELEVATION_JOB: '继续生成立面', CREATE_BUDGET: '可做预算', SUBMIT: '可投稿' };
function projectRow(p) {
  return {
    projectId: String(p.projectId != null ? p.projectId : p.id),
    name: p.name || '未命名方案',
    jobStatus: p.jobStatus || '',
    resumeAction: p.resumeAction || '',
    resumeText: RESUME_TEXT[p.resumeAction] || '',
    coverAssetId: p.coverAssetId || null,
    pointsSpentText: p.pointsSpent != null ? String(p.pointsSpent) : '',
    hasResult: !!p.hasResult, hasDualImages: !!p.hasDualImages,
    createTime: p.createTime || ''
  };
}

/** R17 结果版本 → 恢复渲染（按 kind 取资产字段） */
function resultVersionRow(v) {
  return {
    versionId: String(v.versionId != null ? v.versionId : ''),
    version: v.version,
    superseded: !!v.superseded,
    assetId: v.selectedFlatAssetId || v.selectedElevationAssetId || null,
    createTime: v.createTime || null
  };
}

/** 快速预算表单 → R20 body（footprint/roofArea/roof 不传；buildingArea 整数 1-10000） */
function estimateBody(f) {
  const area = String(f.area == null ? '' : f.area).trim();
  if (!/^\d+$/.test(area) || Number(area) < 1 || Number(area) > 10000) {
    return { error: '建筑面积须为 1–10000 的整数' };
  }
  if (!f.regionCode || !String(f.regionCode).trim()) return { error: '请填写区域代码' };
  if (!['BRICK', 'FRAME', 'STEEL'].includes(f.structureType)) return { error: '请选择结构类型' };
  if (!['A', 'B', 'C'].includes(f.materialGrade)) return { error: '请选择材质档位' };
  return { body: { regionCode: String(f.regionCode).trim(), structureType: f.structureType, materialGrade: f.materialGrade, buildingArea: Number(area) } };
}

/** 表单 → R9 requirementInputs（键白名单：faceWidthM/depthM/floorCount/styleCode/note；floors≤4、note≤200） */
function requirementInputs(f) {
  const w = Number(f.width), d = Number(f.depth), floors = Number(f.floors);
  if (!w || w < 3 || w > 40 || !/^\d{1,2}(\.\d)?$/.test(String(f.width || ''))) return { error: '面宽须为3–40米，最多一位小数' };
  if (!d || d < 3 || d > 40 || !/^\d{1,2}(\.\d)?$/.test(String(f.depth || ''))) return { error: '进深须为3–40米，最多一位小数' };
  if (!Number.isInteger(floors) || floors < 1 || floors > 4) return { error: '建筑层数须为1–4层整数' };
  const sc = styleCode(f.style);
  if (!sc) return { error: '请选择有效风格' };
  const note = String(f.note || '').trim();
  if (note.length > 200) return { error: '需求说明不超过200字' };
  const inputs = { faceWidthM: f.width, depthM: f.depth, floorCount: floors, styleCode: sc };
  if (f.entranceOrientation) inputs.entranceOrientation = f.entranceOrientation;
  if (f.courtyardLayout) inputs.courtyardLayout = f.courtyardLayout;
  if (f.drawingType) inputs.drawingType = f.drawingType;
  if (f.family) {
    const fm = String(f.family), m = fm.match(/(\d)室(\d)厅(\d)卫/);
    if (m) inputs.rooms = { bedroom: Number(m[1]), living: Number(m[2]), bath: Number(m[3]) };
    inputs.family = fm;
  }
  if (note) inputs.note = note;
  return { inputs };
}

/** 错误映射：design/1_112 原码 + 平台共享段 */
function errorMessageDesign(err) {
  const code = err && err.code;
  const map = {
    '1112000001': '方案尺寸不合法，请核对宽深层数与面积',
    '1112000002': '客户不存在或无权访问',
    '1112000003': '方案类型与结果不匹配',
    '1112000004': '服务账号不可用，请联系总部'
  };
  if (code != null && map[String(code)]) return map[String(code)];
  if (code != null && (code === 403)) return '没有该操作权限';
  if (code != null && String(code).indexOf('1071000009') === 0) return STILL_RUNNING_MESSAGE;
  if (code === -1) return '网络异常，请刷新确认';
  return (err && err.message) || '操作失败，请重试';
}

module.exports = {
  DESIGN_ROLES, pageMode,
  STYLE_CODE, ROOF_CODE, MATERIAL_OPTIONS, COLOR_OPTIONS, ORIENTATION_OPTIONS, STRUCTURE_OPTIONS, GRADE_OPTIONS,
  ENTRANCE_OPTIONS, FAMILY_OPTIONS, COURTYARD_OPTIONS, DRAWING_OPTIONS,
  styleCode, roofCode,
  POLL_INTERVAL_MS, POLL_MAX_MS, pollDecision, STILL_RUNNING_MESSAGE, STATUS_TEXT, smoothProgress, estimateText,
  caseRow, loadCaseCovers, projectRow, RESUME_TEXT, ruleInfoView, estimateView, candidateRows, resultVersionRow,
  estimateBody, requirementInputs, errorMessageDesign
};

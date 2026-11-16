'use strict';

/**
 * T15：AI 设计页需求输入的构造与校验（纯函数，供页面与单测共用）。
 * 键与后端 BudgetInputs 白名单一一对应：未知键会被后端拒绝（1_071_000_005）。
 */

var FLOOR_OPTIONS = [
  { label: '一层', count: 1 },
  { label: '两层', count: 2 },
  { label: '三层', count: 3 },
  { label: '四层', count: 4 }
];

var FAMILY_OPTIONS = [
  { label: '2室1厅1卫', rooms: { bedroom: 2, living: 1, bath: 1 } },
  { label: '3室2厅1卫', rooms: { bedroom: 3, living: 2, bath: 1 } },
  { label: '3室2厅2卫', rooms: { bedroom: 3, living: 2, bath: 2 } },
  { label: '4室2厅2卫', rooms: { bedroom: 4, living: 2, bath: 2 } },
  { label: '5室3厅2卫', rooms: { bedroom: 5, living: 3, bath: 2 } },
  { label: '6室3厅3卫', rooms: { bedroom: 6, living: 3, bath: 3 } }
];

function floorLabels() { return FLOOR_OPTIONS.map(function (o) { return o.label; }); }
function familyLabels() { return FAMILY_OPTIONS.map(function (o) { return o.label; }); }

// 需求①拆分（22号文档 7.1）：平面线新增采集维度——主入口朝向、院落布局、图纸类型
var ENTRANCE_OPTIONS = [
  { label: '朝南 · 南入户', code: 'SOUTH' },
  { label: '朝北 · 北入户', code: 'NORTH' },
  { label: '朝东 · 东入户', code: 'EAST' },
  { label: '朝西 · 西入户', code: 'WEST' }
];
var LAYOUT_OPTIONS = [
  { label: 'AI 智能安排', code: '' },
  { label: '四合院落', code: 'SIHEYUAN' },
  { label: '前庭后院', code: 'FRONT_BACK_YARD' },
  { label: '前院影壁', code: 'FRONT_YARD_SCREEN' },
  { label: '紧凑无院', code: 'COMPACT' }
];
var DRAWING_TYPES = [
  { label: '顶视平面图', code: 'TOP_VIEW' },
  { label: '彩平图', code: 'COLOR_PLAN' },
  { label: 'CAD 线稿', code: 'CAD_LINE' },
  { label: '轴测图', code: 'AXONOMETRIC' },
  { label: '鸟瞰图', code: 'BIRD_EYE' }
];
function entranceLabels() { return ENTRANCE_OPTIONS.map(function (o) { return o.label; }); }
function layoutLabels() { return LAYOUT_OPTIONS.map(function (o) { return o.label; }); }

/** 面宽/进深：3~40 米，最多一位小数（与后端上限一致） */
function isValidLength(value) {
  if (typeof value !== 'string' || !/^\d{1,2}(\.\d)?$/.test(value)) return false;
  var n = Number(value);
  return n >= 3 && n <= 40;
}

function indexOfFloor(label) {
  var index = FLOOR_OPTIONS.findIndex(function (o) { return o.label === label; });
  return index < 0 ? 1 : index;
}

function indexOfFamily(label) {
  var index = FAMILY_OPTIONS.findIndex(function (o) { return o.label === label; });
  return index < 0 ? 4 : index;
}

/**
 * @param {number} mode 0=户型设计 1=自主设计
 * @param {{faceWidth:string, depth:string, floorIndex:number, familyIndex:number, note:string, prompt:string}} data
 * @returns {{inputs: Object}|{error: string}}
 */
/**
 * mode：0=平面方案线路（FLAT） 1=立面方案线路（ELEVATION）。
 * 平面：宅基地尺寸 + 主入口朝向 + 层数 + 家庭需求 + 院落布局 + 图纸类型 + 补充需求；
 * 立面：层数 + 补充需求（风格/屋顶/外墙/点缀色由页面直接以编码传入 data.styleCode 等，选了才带）。
 * 自由描述能力并入 note（不再有独立 prompt 键）。
 */
function buildRequirementInputs(mode, data) {
  var inputs = {};
  var floor = FLOOR_OPTIONS[data.floorIndex] || FLOOR_OPTIONS[1];
  inputs.floor = floor.label;
  inputs.floorCount = floor.count;
  if (mode === 1) {
    inputs.note = String(data.note == null ? '' : data.note);
    if (Array.isArray(data.referenceAssetIds) && data.referenceAssetIds.length) {
      inputs.referenceAssetIds = data.referenceAssetIds.map(String);
    }
    var styleCode = String(data.styleCode == null ? '' : data.styleCode);
    if (styleCode) inputs.styleCode = styleCode;
    var roofType = String(data.roofType == null ? '' : data.roofType);
    if (roofType) inputs.roofType = roofType;
    var material = String(data.material == null ? '' : data.material);
    if (material) inputs.material = material;
    var color = String(data.color == null ? '' : data.color);
    if (color) inputs.color = color;
    return { inputs: inputs };
  }
  var faceWidth = String(data.faceWidth == null ? '' : data.faceWidth).trim();
  var depth = String(data.depth == null ? '' : data.depth).trim();
  if (!isValidLength(faceWidth)) return { error: '面宽需在 3~40 米，可带一位小数' };
  if (!isValidLength(depth)) return { error: '进深需在 3~40 米，可带一位小数' };
  inputs.faceWidthM = Number(faceWidth);
  inputs.depthM = Number(depth);
  var family = FAMILY_OPTIONS[data.familyIndex] || FAMILY_OPTIONS[4];
  inputs.family = family.label;
  inputs.rooms = family.rooms;
  inputs.note = String(data.note == null ? '' : data.note);
  var entrance = ENTRANCE_OPTIONS[data.entranceIndex] || ENTRANCE_OPTIONS[0];
  inputs.entranceOrientation = entrance.code;
  var drawing = DRAWING_TYPES[data.drawingIndex] || DRAWING_TYPES[0];
  inputs.drawingType = drawing.code;
  var layout = LAYOUT_OPTIONS[data.layoutIndex] || LAYOUT_OPTIONS[0];
  if (layout.code) inputs.courtyardLayout = layout.code;
  return { inputs: inputs };
}

module.exports = {
  FLOOR_OPTIONS: FLOOR_OPTIONS,
  FAMILY_OPTIONS: FAMILY_OPTIONS,
  ENTRANCE_OPTIONS: ENTRANCE_OPTIONS,
  LAYOUT_OPTIONS: LAYOUT_OPTIONS,
  DRAWING_TYPES: DRAWING_TYPES,
  floorLabels: floorLabels,
  familyLabels: familyLabels,
  entranceLabels: entranceLabels,
  layoutLabels: layoutLabels,
  isValidLength: isValidLength,
  indexOfFloor: indexOfFloor,
  indexOfFamily: indexOfFamily,
  buildRequirementInputs: buildRequirementInputs
};

'use strict';

const design = require('../../api/franchise/design');
const inputs = require('./design-inputs');

// Use the published AI-design catalog; labels and ordering belong to its backend.
function styles() {
  return design.cases.styles().then(response => {
    const rows = Array.isArray(response) ? response : response && response.list;
    if (!Array.isArray(rows)) throw Error('风格分类暂不可用');
    const seen = new Set();
    return rows.filter(row => row && typeof row.code === 'string' && row.code
      && typeof row.label === 'string' && row.label && !seen.has(row.code) && seen.add(row.code))
      .map(row => ({ code: row.code, label: row.label }));
  });
}

// The original design app supports these four floors; no separate floor API exists.
function floors() { return inputs.FLOOR_OPTIONS.map(row => ({ label: row.label, count: row.count })); }

module.exports = { styles, floors };

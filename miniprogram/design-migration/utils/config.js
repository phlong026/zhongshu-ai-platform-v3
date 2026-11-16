module.exports = { env: 'production', get apiBase() { return require('../../api/http').base(); }, get tenantId() { return require('../../api/http').tenantId(); } };

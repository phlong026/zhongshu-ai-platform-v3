/**
 * 平台待办：当前岗位的待办列表（可办理在前）。
 * 对应后端 GET /platform/todos。
 */
const http = require('./http');

function list() {
  return http.get('/platform/todos');
}

module.exports = { list };

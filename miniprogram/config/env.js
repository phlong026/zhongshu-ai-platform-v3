/**
 * 环境配置单源：切生产只改这一个文件。
 *
 * API_BASE：后端 /admin-api 基址（生产必填正式域名，须 HTTPS 且在微信公众平台配置 request 合法域名）。
 * 留空 = 未配置：所有 API 请求将显式失败（「API 基址未配置」），绝不静默回落本地地址。
 * 本地/联调开发：经 storage key `zs-api-base` 覆写（见 api/http.js），不改本文件。
 */
module.exports = {
  // TODO(production): 填入正式生产域名后移除本注释。示例：'https://api.example.com/admin-api'
  API_BASE: '',
};

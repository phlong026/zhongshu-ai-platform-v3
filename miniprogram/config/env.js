/**
 * 环境配置单源：切生产只改这一个文件。
 *
 * API_BASE：后端 /admin-api 基址（生产必填正式域名，须 HTTPS 且在微信公众平台配置 request 合法域名）。
 * 留空 = 未配置：所有 API 请求将显式失败（「API 基址未配置」），绝不静默回落本地地址。
 * 本地/联调开发：经 storage key `zs-api-base` 覆写（见 api/http.js），不改本文件。
 *
 * FR_HOST：/fr Web 门户根地址（仅 scheme+host，不含路径/查询/凭据；小程序「在浏览器打开」复制链接用）。
 * 仓内此前无 Web 门户基址配置，故新增本项；留空 = 未配置：复制动作显式提示，绝不硬编码生产域名。
 * 本地/联调开发：经 storage key `zs-fr-host` 覆写（见 utils/fr-web-link.js），不改本文件。
 */
module.exports = {
  // 生产域名（与服务器现有 API 域名一致——新后端部署于同一域名下）
  API_BASE: 'https://api2.zszhj.com/admin-api',
  // /fr Web 门户根地址（如 'https://fr.zszhj.com'）；留空时小程序只提示未配置，不内置任何生产域名
  FR_HOST: '',
};

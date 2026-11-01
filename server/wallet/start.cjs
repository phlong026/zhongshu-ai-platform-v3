/**
 * 钱包本地服务启动器：PORT 默认 48081，供微信开发者工具联调与 verify 脚本使用。
 * 用法：node server/wallet/start.cjs
 */
const server = require('./server.cjs');

server.listen(Number(process.env.PORT || 48081), process.env.HOST || '127.0.0.1').then((addr) => {
  console.log('[wallet] 本地钱包服务已启动: http://' + addr.address + ':' + addr.port + '/admin-api');
});

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const { createSimulationServer } = require('./server.cjs');

const privateRoot = path.resolve(process.env.DESIGN_COLLAB_PRIVATE_DIR || path.resolve(__dirname, '../../.tools/design-collaboration'));
const dataFile = path.join(privateRoot, 'state.json');
const connectionFile = path.join(privateRoot, 'connection.json');
const invite = label => `${label}-${crypto.randomBytes(8).toString('hex')}`;

(async () => {
  fs.mkdirSync(privateRoot, { recursive: true, mode: 0o700 });
  const invites = {
    [invite('finance')]: 'hq-finance-dual',
    [invite('finance2')]: 'hq-finance-2',
    [invite('admin')]: 'hq-design-admin',
    [invite('sales')]: 'sales-dual'
  };
  const server = await createSimulationServer({ dataFile, invites });
  await server.listen(Number(process.env.PORT || 0), process.env.HOST || '127.0.0.1');
  const address = server.address();
  const connection = {
    mode: 'simulated-cross-device',
    baseUrl: `http://${address.address}:${address.port}`,
    invites: Object.fromEntries(Object.entries(invites).map(([code, userId]) => [userId, code])),
    dataFile,
    notice: '仅用于本机模拟联调；不连接真实银行、微信订阅消息或生产数据。'
  };
  fs.writeFileSync(connectionFile, JSON.stringify(connection, null, 2), { mode: 0o600 });
  fs.chmodSync(connectionFile, 0o600);
  process.stdout.write(`模拟跨端服务已启动：${connection.baseUrl}\n连接信息已写入私有文件：${connectionFile}\n`);
  const stop = async () => { await server.close(); process.exit(0); };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
})().catch(error => {
  process.stderr.write(`模拟跨端服务启动失败：${error.message}\n`);
  process.exitCode = 1;
});

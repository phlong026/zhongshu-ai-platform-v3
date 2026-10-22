const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { createManagementSimulation } = require('./server.cjs');

const privateDirectory = path.resolve(process.env.MANAGEMENT_SIM_PRIVATE_DIR || '.tools/management-simulation');
const dataFile = path.join(privateDirectory, 'state.json');
const connectionFile = path.join(privateDirectory, 'connection.json');
const code = () => crypto.randomBytes(32).toString('base64url');

(async () => {
  fs.mkdirSync(privateDirectory, { recursive: true, mode: 0o700 });
  const invites = { bossOne: code(), bossTwo: code(), sales: code() };
  const server = await createManagementSimulation({
    dataFile,
    invites: { [invites.bossOne]: 'boss', [invites.bossTwo]: 'boss', [invites.sales]: 'sales-dual' }
  });
  await server.listen(Number(process.env.PORT || 8788), process.env.HOST || '127.0.0.1');
  const address = server.address();
  const connection = { mode: 'simulated-cross-device', baseUrl: `http://${address.address}:${address.port}`, invites };
  fs.writeFileSync(connectionFile, JSON.stringify(connection, null, 2), { mode: 0o600 });
  fs.chmodSync(connectionFile, 0o600);
  process.stdout.write(`模拟管理服务已启动：${connection.baseUrl}\n连接信息已保存：${connectionFile}\n`);
  const close = async () => { await server.close(); process.exit(0); };
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
})().catch(error => { process.stderr.write((error.stack || error.message) + '\n'); process.exitCode = 1; });

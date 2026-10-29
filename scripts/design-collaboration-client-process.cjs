// A separate simulated handset process: its only shared state is the HTTP service.
const fs = require('fs');
const crypto = require('crypto');
const { createClient } = require('../miniprogram/pages/collaboration/client');
const file = process.argv[2];
let storage = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {};
const platform = {
  getStorageSync: key => storage[key],
  setStorageSync(key, value) { storage[key] = value; fs.writeFileSync(file, JSON.stringify(storage), { mode: 0o600 }); },
  removeStorageSync(key) { delete storage[key]; fs.writeFileSync(file, JSON.stringify(storage), { mode: 0o600 }); },
  request(options) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), options.timeout);
    fetch(options.url, { method: options.method, headers: options.header, body: options.method === 'GET' ? undefined : JSON.stringify(options.data), signal: controller.signal })
      .then(async response => options.success({ statusCode: response.status, data: await response.json() }))
      .catch(error => options.fail({ errMsg: error.message })).finally(() => clearTimeout(timer));
    return { abort: () => controller.abort() };
  }
};
const client = createClient({ wx: platform, pollInterval: 300 });
process.on('message', async message => {
  try {
    let value;
    if (message.action === 'connect') { value = await client.connect(message.base, message.code); client.start(() => {}); }
    else if (message.action === 'snapshot') value = client.snapshot();
    else if (message.action === 'refresh') value = await client.refresh();
    else if (message.action === 'command') value = await client.command(message.path, message.input);
    else if (message.action === 'fingerprint') value = crypto.createHash('sha256').update(storage['zhongshu-collaboration-client-v1'].token).digest('hex');
    else if (message.action === 'stop') { client.stop(); process.send({ id: message.id, value: true }, () => process.exit(0)); return; }
    else throw Error('Unknown test action');
    process.send({ id: message.id, value });
  } catch (error) { process.send({ id: message.id, error: error.message }); }
});
process.on('disconnect', () => { client.stop(); process.exit(0); });

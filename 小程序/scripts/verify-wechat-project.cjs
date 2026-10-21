const fs = require('fs');
const path = require('path');

const expectedAppId = 'wxe584f6fad7b54487';

function verifyWeChatProject(projectRoot = path.resolve(__dirname, '..')) {
  const root = path.resolve(projectRoot);
  const configPath = path.join(root, 'project.config.json');
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (config.appid !== expectedAppId) {
    throw new Error(`AppID mismatch: expected ${expectedAppId}, found ${config.appid || '(empty)'} in ${configPath}`);
  }
  if (config.compileType !== 'miniprogram') {
    throw new Error('compileType must be miniprogram for this WeChat project');
  }
  const miniRoot = path.resolve(root, config.miniprogramRoot || '');
  if (miniRoot !== path.join(root, 'miniprogram')) {
    throw new Error('miniprogramRoot must point to this project\'s miniprogram/ directory');
  }
  const privatePath = path.join(root, 'project.private.config.json');
  if (fs.existsSync(privatePath)) {
    const overrides = JSON.parse(fs.readFileSync(privatePath, 'utf8'));
    for (const field of ['appid', 'compileType', 'miniprogramRoot']) {
      if (!Object.hasOwn(overrides, field)) continue;
      const matches = field === 'miniprogramRoot'
        ? path.resolve(root, overrides[field] || '') === miniRoot
        : overrides[field] === config[field];
      if (!matches) throw new Error(`Conflicting ${field} in ${privatePath}`);
    }
  }
  const extPath = path.join(miniRoot, 'ext.json');
  if (fs.existsSync(extPath)) {
    const ext = JSON.parse(fs.readFileSync(extPath, 'utf8'));
    if (ext.extEnable && ext.extAppid !== expectedAppId) {
      throw new Error(`Enabled ext.json targets another AppID: ${ext.extAppid || '(empty)'}`);
    }
  }
  const appPath = path.join(miniRoot, 'app.json');
  const app = JSON.parse(fs.readFileSync(appPath, 'utf8'));
  if (!Array.isArray(app.pages) || !app.pages.length) throw new Error('app.json must register the native entry pages');
  return {
    result: 'passed', appid: expectedAppId, projectRoot: root,
    miniprogramRoot: miniRoot, entryPage: app.pages[0],
    nativePages: app.pages.length + (app.subPackages || []).reduce((n, pkg) => n + pkg.pages.length, 0),
    privateConfig: fs.existsSync(privatePath) ? privatePath : null
  };
}

if (require.main === module) {
  try { console.log(JSON.stringify(verifyWeChatProject(), null, 2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}

module.exports = verifyWeChatProject;

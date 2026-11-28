const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');

const root = path.resolve(__dirname, '..');
const miniRoot = path.resolve(root, require('../project.config.json').miniprogramRoot);
const fixtureRoot = path.join(__dirname, 'fixtures/legacy-admin');
const retired = ['owner-business', 'owner-model', 'owner-home', 'owner-performance',
  'model', 'owner-settings', 'finance-actions', 'finance-save'];
const oldPaths = new Set(retired.map(name => path.join(miniRoot, 'pages/admin', name)));
const imports = source => [...source.matchAll(/\brequire\(\s*(['"])(\.[^'"]+)\1\s*\)/g)]
  .map(match => match[2]);
const inside = (parent, file) => file === parent || file.startsWith(parent + path.sep);
function scripts(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? scripts(file) : file.endsWith('.js') ? [file] : [];
  });
}

test('the miniapp package cannot load the retired local admin writers or their fixtures', () => {
  assert.equal(inside(miniRoot, fixtureRoot), false, 'historical fixtures must be outside miniprogramRoot');
  for (const oldPath of oldPaths) {
    assert.equal(fs.existsSync(oldPath + '.js'), false, 'no production forwarding module: ' + oldPath);
  }
  for (const file of scripts(miniRoot)) {
    for (const specifier of imports(fs.readFileSync(file, 'utf8'))) {
      const target = path.resolve(path.dirname(file), specifier);
      assert.equal(inside(fixtureRoot, target), false, file + ' imports a historical fixture');
      assert.equal(oldPaths.has(target.replace(/\.js$/, '')), false, file + ' imports a retired admin writer');
    }
  }
});

test('historical admin fixtures keep their peers and shared helper dependencies resolvable', () => {
  for (const name of retired) {
    const file = path.join(fixtureRoot, name + '.js');
    const resolve = createRequire(file).resolve;
    for (const specifier of imports(fs.readFileSync(file, 'utf8'))) {
      assert.ok(fs.existsSync(resolve(specifier)), name + ': ' + specifier);
    }
  }
});


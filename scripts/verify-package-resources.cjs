const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const PACKAGE_BUDGET = 2 * 1024 * 1024;
const MINIMUM_HEADROOM = 128 * 1024;

function verifyPackageResources(projectRoot = path.resolve(__dirname, '..')) {
  const config = JSON.parse(fs.readFileSync(path.join(projectRoot, 'project.config.json'), 'utf8'));
  const root = path.resolve(projectRoot, config.miniprogramRoot);
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
  const packages = app.subPackages || [];
  const owner = file => packages.find(pkg => file.startsWith(pkg.root + '/'))?.root || 'main';
  const ignored = file => (config.packOptions?.ignore || []).some(rule =>
    rule.type === 'file' ? file === rule.value : rule.type === 'folder' && file.startsWith(rule.value.replace(/\/$/, '') + '/'));
  const files = [];
  function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const file = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(file);
      else files.push(path.relative(root, file).replace(/\\/g, '/'));
    }
  }
  walk(root);
  const failures = [], sizes = { main: 0 }, references = new Set();
  function resource(from, target, dependency = false) {
    const name = target.startsWith('/') ? target.slice(1) : path.posix.normalize(path.posix.join(path.posix.dirname(from), target));
    const resolved = [name, name + '.js', name + '/index.js'].find(file => fs.existsSync(path.join(root, file)) && fs.statSync(path.join(root, file)).isFile());
    if (!resolved || ignored(resolved)) failures.push(`${from}: missing or excluded resource ${target}`);
    else {
      references.add(resolved);
      if (dependency && owner(resolved) !== 'main' && owner(from) !== owner(resolved)) failures.push(`${from}: resource belongs to another package: ${resolved}`);
    }
  }
  for (const file of files) {
    if (ignored(file)) continue;
    sizes[owner(file)] = (sizes[owner(file)] || 0) + fs.statSync(path.join(root, file)).size;
    if (!/\.(js|wxml|wxss|json)$/.test(file) || /\/assets\//.test('/' + file)) continue;
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    // Data records can carry paths for a later page; validate their inclusion here.
    for (const match of source.matchAll(/(?<![\w/.-])\/(?:[\w-]+\/)*assets\/[\w-]+\.(?:png|jpe?g|webp|gif|svg|woff2?|ttf)/g)) resource(file, match[0]);
    if (file.endsWith('.js')) for (const match of source.matchAll(/require\(['"]([./][^'"]+)['"]\)/g)) resource(file, match[1], true);
    if (file.endsWith('.wxml')) {
      for (const match of source.matchAll(/<(?:import|include|wxs)\b[^>]*\bsrc=['"]([^'"{}]+)['"]/g)) resource(file, match[1], true);
      for (const match of source.matchAll(/<image\b[^>]*\bsrc\s*=\s*(["'])(.*?)\1/g)) {
        const src = match[2].trim();
        if (src && !src.includes('{{') && !/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(src)) resource(file, src, true);
        else for (const image of src.matchAll(/(?<![\w/.-])\/(?:[\w-]+\/)*assets\/[\w-]+\.(?:png|jpe?g|webp|gif|svg)/g)) resource(file, image[0], true);
      }
    }
    if (file.endsWith('.wxss')) for (const match of source.matchAll(/@import\s*['"]([^'"]+)['"]/g)) resource(file, match[1], true);
    if (file.endsWith('.json')) for (const component of Object.values(JSON.parse(source).usingComponents || {})) resource(file, component, true);
  }
  const total = Object.values(sizes).reduce((a, b) => a + b, 0);
  const headroom = {};
  for (const [name, size] of Object.entries(sizes)) {
    headroom[name] = PACKAGE_BUDGET - size;
    if (size > PACKAGE_BUDGET) failures.push(`${name}: ${size} bytes exceeds the 2 MiB project budget`);
    else if (headroom[name] < MINIMUM_HEADROOM) failures.push(`${name}: only ${headroom[name]} bytes of headroom; the project requires at least ${MINIMUM_HEADROOM} bytes (128 KiB) for growth`);
  }
  assert.equal(failures.length, 0, failures.join('\n'));
  return { result: 'passed', resourceReferences: references.size, totalBytes: total, packages: sizes,
    packageBudgetBytes: PACKAGE_BUDGET, minimumHeadroomBytes: MINIMUM_HEADROOM, headroomBytes: headroom };
}
module.exports = verifyPackageResources;
if (require.main === module) console.log(JSON.stringify(verifyPackageResources(), null, 2));

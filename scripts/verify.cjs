const fs = require('fs');
const path = require('path');
const vm = require('vm');
const root = path.resolve(__dirname, '..');
const wechatProject = require('./verify-wechat-project.cjs')(root);
const packageResources = require('./verify-package-resources.cjs')(root);
const mini = path.join(root, 'miniprogram');
const project = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json'), 'utf8'));
const ignoredFiles = new Set((project.packOptions?.ignore || []).filter(item => item.type === 'file').map(item => item.value.replace(/\\/g, '/').replace(/^\//, '')));
const isPacked = file => !ignoredFiles.has(path.relative(mini, file).replace(/\\/g, '/'));
let js = 0, json = 0, bytes = 0, ignoredBytes = 0;
function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) { walk(file); continue; }
    if (isPacked(file)) bytes += fs.statSync(file).size;
    else ignoredBytes += fs.statSync(file).size;
    if (file.endsWith('.js')) { new vm.Script(fs.readFileSync(file, 'utf8'), { filename: file }); js++; }
    if (file.endsWith('.json')) { JSON.parse(fs.readFileSync(file, 'utf8')); json++; }
    if(file.endsWith('.wxml')) for(const binding of fs.readFileSync(file,'utf8').matchAll(/\{\{[\s\S]*?\}\}/g)) if(/&(?:amp|lt|gt|quot);/.test(binding[0])) throw Error('WXML expression contains HTML entity rejected by WeChat compiler: '+file+' '+binding[0]);
    if (/\.(?:wxml|js)$/.test(file)) {
      for (const match of fs.readFileSync(file, 'utf8').matchAll(/\/(?:pages\/(?:workflow|build|account|admin|engineering)\/)?assets\/[\w-]+\.(?:png|jpg|woff|ttf)/g)) {
        if (!fs.existsSync(path.join(mini, match[0].slice(1)))) throw new Error('Missing asset: ' + match[0]);
      }
    }
  }
}
walk(mini);
const app = JSON.parse(fs.readFileSync(path.join(mini,'app.json'),'utf8'));
const pages=app.pages.concat((app.subPackages||[]).flatMap(pkg=>pkg.pages.map(page=>pkg.root+'/'+page)));
for (const page of pages) for (const ext of ['.js','.json','.wxml','.wxss']) {
  if (!fs.existsSync(path.join(mini,page+ext))) throw new Error('Missing native page: '+page+ext);
}
for (const item of app.tabBar.list) {
  if (!app.pages.includes(item.pagePath)) throw new Error('Unregistered tab: '+item.pagePath);
}
const manifest = JSON.parse(fs.readFileSync(path.join(mini, 'assets/manifest.json'),'utf8'));
for (const asset of manifest) if (!fs.existsSync(path.join(mini,'assets',asset.asset))) throw new Error('Missing manifest asset: '+asset.asset);
const packageSizes={};for(const pkg of app.subPackages||[]){let size=0;function count(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const file=path.join(dir,e.name);if(e.isDirectory())count(file);else if(isPacked(file))size+=fs.statSync(file).size;}}count(path.join(mini,pkg.root));packageSizes[pkg.name||pkg.root]=size;}
const mainBytes=bytes-Object.values(packageSizes).reduce((a,b)=>a+b,0);
if(Object.values(packageSizes).some(size=>size>2097152))throw new Error('A subpackage exceeds 2MiB');
if(mainBytes>2097152)throw new Error('Main package exceeds 2MiB target: '+mainBytes);
const wxml=require('./verify-wxml-complete.cjs')();
if(packageResources.totalBytes!==bytes)throw new Error('Package resource audit size mismatch');
console.log(JSON.stringify({ result: 'passed', appid:wechatProject.appid, wxmlChecked:wxml.wxmlChecked, nativePages: pages.length, jsChecked: js, jsonChecked: json, assetsChecked: manifest.length, packageBytes: bytes, mainBytes, ignoredFiles:[...ignoredFiles], ignoredBytes, subPackages:packageSizes },null,2));


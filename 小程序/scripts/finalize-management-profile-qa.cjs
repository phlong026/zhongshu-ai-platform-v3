const assert=require('assert/strict'),fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'..'),qa=path.join(root,'qa/management-profile-20261018');
const read=name=>JSON.parse(fs.readFileSync(path.join(qa,name),'utf8'));
const hash=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const capture=read('capture-final.json');
assert.equal(capture.results.length,57);assert.equal(new Set(capture.results.map(x=>x.prefix)).size,57);assert.equal(capture.errors.length,0);
for(const row of capture.results){assert(fs.existsSync(path.join(qa,'final',row.file)),row.prefix);const group=['account','admin'].includes(row.kind)?row.kind:'dashboard';const faces=row.fonts&&row.fonts[group];assert(faces&&Object.values(faces).every(x=>x==='loaded'),'Font loading: '+row.prefix);}
const suites=[['logic-results.json',28],['data-consistency-results.json',21],['simulator-results.json',20],['focus-simulator-results.json',5],['dashboard-regression.json',15]];
const verification=suites.map(([file,count])=>{const result=read(file);assert.equal(result.checks.length,count,file);assert(result.checks.every(x=>x.result==='passed'),file);assert(!result.errors||!result.errors.length,file);return {file,passed:count,sha256:hash(path.join(qa,file))};});
const fontAudit=read('font-audit.json');for(const face of fontAudit.faces)face.sha256=hash(path.join(root,face.module));
fs.writeFileSync(path.join(qa,'font-audit.json'),JSON.stringify(fontAudit,null,2));
const mini=path.join(root,'miniprogram'),copy=path.join(root,'.tools/management-profile-validation/miniprogram'),files=[];
function walk(folder){for(const entry of fs.readdirSync(folder,{withFileTypes:true})){const file=path.join(folder,entry.name);if(entry.isDirectory())walk(file);else{const relative=path.relative(mini,file).replaceAll('\\','/'),digest=hash(file);assert.equal(hash(path.join(copy,relative)),digest,'Validation copy differs: '+relative);files.push({file:'miniprogram/'+relative,bytes:fs.statSync(file).size,sha256:digest});}}}
walk(mini);
const report='管理与我的页面复刻及流程排查报告.md';
const manifest={createdAt:new Date().toISOString(),scope:{management:28,profile:12,supplemental:17,captured:57,references:70},platform:'微信开发者工具，iPhone 12/13 (Pro)，390×844',verification,fonts:{embedded:true,originalDesignFontFileProvided:false,audit:'font-audit.json'},sourceMatchesValidationCopy:true,report:{file:report,sha256:hash(path.join(qa,report))},screenshots:capture.results.map(x=>({prefix:x.prefix,view:x.view,kind:x.kind||'dashboard',file:'final/'+x.file,sha256:hash(path.join(qa,'final',x.file))})),files};
fs.writeFileSync(path.join(qa,'delivery-manifest.json'),JSON.stringify(manifest,null,2));
console.log(JSON.stringify({result:'passed',screenshots:57,checks:verification,sourceFiles:files.length,sourceMatchesValidationCopy:true},null,2));

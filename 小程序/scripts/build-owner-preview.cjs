const fs=require('fs'),path=require('path');
const sharp=require('C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/sharp');
const root=path.resolve(__dirname,'..'),out=path.join(root,'效果图','老板端四页串联预览_20261020');
const qa=path.join(root,'qa','boss-workbench-20261020','native');
const reference=path.join(root,'效果图','管理老板端四页改版方案_20261019');
const pages=[{key:'home',name:'首页',ref:'01_首页_方案1.png',note:'经营总览 → 原单金额审批 → 业务与工程项目'},
{key:'finance',name:'财务',ref:'06_财务_方案3.png',note:'按月份与项目查看有效收支 → 原项目账簿'},
{key:'performance',name:'绩效',ref:'07_绩效_方案1.png',note:'月度目标 → 业务合同与工程节点 → 来源与复核'},
{key:'profile',name:'我的',ref:'11_我的_方案2.png',note:'成员权限 → 审批分工 → 项目经理交接与服务'}];
const xml=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');
(async()=>{
 const results=JSON.parse(fs.readFileSync(path.join(qa,'results.json'),'utf8'));if(results.result!=='passed')throw Error('Native QA must pass before export');
 fs.mkdirSync(path.join(out,'对照'),{recursive:true});
 const width=352,pad=20,top=112,tiles=[];
 for(let i=0;i<pages.length;i++){
  const p=pages[i],prefix=String(i+1).padStart(2,'0');p.file=prefix+'_'+p.name+'.png';p.bottom=prefix+'_'+p.name+'_页底.png';
  fs.copyFileSync(path.join(qa,p.key+'-top.png'),path.join(out,p.file));fs.copyFileSync(path.join(qa,p.key+'-bottom.png'),path.join(out,p.bottom));
  const files=[path.join(reference,p.ref),path.join(out,p.file),path.join(out,p.bottom)],images=await Promise.all(files.map(f=>sharp(f).resize({width}).png().toBuffer()));
  const meta=await Promise.all(images.map(b=>sharp(b).metadata())),height=top+Math.max(...meta.map(m=>m.height))+pad,boardWidth=3*width+4*pad;
  const head=Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="'+boardWidth+'" height="'+height+'"><rect width="100%" height="100%" fill="#f3efe8"/><g fill="#623b20" font-family="Microsoft YaHei,sans-serif"><text x="20" y="40" font-size="29" font-weight="700">'+xml(p.name)+' · 所选方案与微信实测</text>'+['所选效果图','微信原生 · 首屏','微信原生 · 页底'].map((t,j)=>'<text x="'+(pad+j*(width+pad))+'" y="88" font-size="21">'+t+'</text>').join('')+'</g></svg>');
  await sharp({create:{width:boardWidth,height,channels:3,background:'#f3efe8'}}).composite([{input:head,left:0,top:0},...images.map((input,j)=>({input,left:pad+j*(width+pad),top}))]).png().toFile(path.join(out,'对照',prefix+'_'+p.name+'_效果图与微信对照.png'));
  tiles.push({input:images[1],left:pad+i*(width+pad),top});
 }
 const boardWidth=4*width+5*pad,heights=await Promise.all(tiles.map(t=>sharp(t.input).metadata())),height=top+Math.max(...heights.map(m=>m.height))+pad;
 const title=Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="'+boardWidth+'" height="'+height+'"><rect width="100%" height="100%" fill="#f3efe8"/><g fill="#623b20" font-family="Microsoft YaHei,sans-serif"><text x="20" y="40" font-size="30" font-weight="700">众墅之家 · 老板端四页 · 微信原生实测</text>'+pages.map((p,i)=>'<text x="'+(pad+i*(width+pad))+'" y="88" font-size="23">'+p.name+'</text>').join('')+'</g></svg>');
 await sharp({create:{width:boardWidth,height,channels:3,background:'#f3efe8'}}).composite([{input:title,left:0,top:0},...tiles]).png().toFile(path.join(out,'00_老板端四页总览.png'));
 const html='<!DOCTYPE html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>老板端四页串联预览</title><style>*{box-sizing:border-box}body{margin:0;background:#faf8f4;color:#30291f;font:15px/1.6 "Microsoft YaHei",sans-serif}main{max-width:1500px;margin:auto;padding:32px 24px}h1{margin:0 0 12px}h2{margin-top:40px;border-left:4px solid #82522e;padding-left:14px}p{color:#777}nav{display:flex;gap:12px;flex-wrap:wrap}a{color:#82522e}nav a{padding:8px 20px;border:1px solid #e7ddcf;border-radius:24px;text-decoration:none;background:white}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24px}article{background:#fff;padding:20px;border-radius:18px}article img{width:100%;height:auto;display:block}.caption{font-size:18px;font-weight:700;margin-bottom:14px}.small{font-size:14px;color:#777}@media(max-width:760px){.grid{grid-template-columns:1fr}}</style><main><h1>老板端四页串联预览</h1><p>已接入现有微信小程序。首页1＋财务3＋绩效1＋我的2，底部导航：首页 / 财务 / 绩效 / 我的。</p><p>以下为微信开发者工具真实截图，点击打开原图。可在小程序使用赵老板模拟账号体验实际导航与原单流程。</p><nav>'+pages.map(p=>'<a href="#'+p.key+'">'+p.name+'</a>').join('')+'</nav>'+pages.map(p=>'<section id="'+p.key+'"><h2>'+p.name+'</h2><p>'+p.note+'</p><div class="grid"><article><div class="caption">微信首屏</div><a href="'+p.file+'"><img src="'+p.file+'" alt="'+p.name+'微信实测首屏"></a></article><article><div class="caption">微信页底</div><a href="'+p.bottom+'"><img src="'+p.bottom+'" alt="'+p.name+'微信实测页底"></a></article></div></section>').join('')+'<h2>统一文字与间距规范</h2><p>字体：Noto Sans SC（ZSAdmin），400／500／700真实字重。正文28rpx / 行高42rpx；辅助文字24 / 36；栏目标题32 / 46。卡片内边距22rpx、间距20rpx、圆角24rpx。四页已实测一致。</p><p class="small">截图使用显式模拟夹具；原业务数据已恢复。页面显示与图稿不同的日期、姓名、数量和目标以当前业务记录为准。数据服务继续使用现有本地演示模型。</p></main></html>';
 fs.writeFileSync(path.join(out,'00_四页串联图册.html'),html);
 fs.copyFileSync(path.join(qa,'results.json'),path.join(out,'原生验证与文字规范.json'));
 fs.copyFileSync(path.join(root,'qa','boss-workbench-20261020','font-coverage.json'),path.join(out,'字体字重与字符覆盖.json'));
 console.log(JSON.stringify({directory:out,nativeScreens:8,comparisonBoards:4,checks:results.checks.length}));
})().catch(e=>{console.error(e.stack);process.exitCode=1;});


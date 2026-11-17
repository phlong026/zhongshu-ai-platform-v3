const Brief = require('./design-brief');
const money = value => Number.isSafeInteger(value) ? (value / 100).toFixed(2) : '';
const value = v => v === undefined || v === null || v === '' ? '未记录' : String(v);
function model(data, commission = {}, state = '') {
  const b = data.designBrief || {}, customer = data.orderCustomer || {}, basis = data.budgetBasis || {};
  const request = commission.request || {}, quote = data.pricingQuote;
  const sections = Brief.sections(b).map(section => ({title:section.title, rows:section.fields
    .filter(f => f.key !== 'projectName' && (f.key !== 'entranceDirection' || b.entranceAligned === '不一致'))
    .map(f => ({label:f.label.replace(/（.*?）/g,''),value:Array.isArray(f.value)?f.value.join('、'):String(f.value || '')})).filter(f => f.value)})).filter(s => s.rows.length);
  if (!sections.length && request.text) sections.push({title:'设计需求',rows:[{label:'需求说明',value:request.text}]});
  const fees = [];
  const budget = money(basis.totalCents);
  if (budget) fees.push({label:'参考建造预算',value:'¥ ' + budget});
  if (quote) fees.push({label:'设计服务费',value:'¥ ' + quote.amountText}, {label:'计价依据',value:value(quote.area)+'㎡ × ¥'+value(quote.rateText)+'/㎡'});
  if (request.scope || data.form && data.form.scope) fees.push({label:'交付范围',value:request.scope || data.form.scope});
  return {title:b.projectName || (data.customer && data.customer.name) || '设计委托',
    status:data.view === 'submit' ? '待提交 · 核对稿' : (state || '已提交'),
    number:commission.commissionId ? String(commission.commissionId) : '', version:request.version || '',
    customer:[{label:'委托客户',value:value(customer.name || data.customer && data.customer.name)},
      ...(data.showCustomerPhone && customer.phone ? [{label:'联系电话',value:customer.phone}] : []),
      {label:'建房地址',value:value(customer.address)}],
    metrics:[{label:'建筑面积',value:value(basis.area),unit:'㎡'}, {label:'建筑层数',value:value(basis.floors),unit:'层'},
      {label:'面宽 × 进深',value:basis.width && basis.depth ? basis.width+' × '+basis.depth : '未记录',unit:'m'}],
    sections,fees,budgetVersion:basis.version || '',
    note:'本图为委托内容快照，以平台对应版本为准。参考方案用于需求沟通，正式成果以实际交付为准。'};
}
// Layout is shared by screen and export. All text wraps; no field is silently truncated.
function draw(canvas, sheet) {
  const W=750, pad=46, content=W-pad*2, ctx=canvas.getContext('2d');
  const commands=[]; let y=54;
  const font=(size,bold)=>{ctx.font=(bold?'bold ':'')+size+'px sans-serif';};
  function text(raw,x,width,size=25,color='#292823',bold=false) {
    font(size,bold); const lines=[]; let line='';
    for(const c of Array.from(String(raw || ''))) { if(c==='\n'){lines.push(line);line='';continue;} if(line && ctx.measureText(line+c).width>width){lines.push(line);line='';} line+=c; }
    if(line)lines.push(line);
    for(const line of lines){commands.push({type:'text',text:line,x,y,size,color,bold});y+=size*1.5;}
  }
  function rule(){commands.push({type:'rule',y});y+=24;}
  function section(title,rows){y+=15;text(title,pad,content,30,'#24231f',true);y+=10;
    for(const row of rows){const start=y;text(row.label,pad,180,23,'#7d776e');const labelEnd=y;y=start;text(row.value,pad+196,content-196,25);y=Math.max(y,labelEnd)+14;rule();}}
  text('众墅之家  /  DESIGN BRIEF',pad,content,23,'#87502b');y+=25;
  text('设计委托单',pad,content,52,'#1d1d1b',true);y+=6;
  text(sheet.title,pad,content,32,'#262520',true);y+=8;
  text(sheet.status,pad,content,23,'#87502b');
  if(sheet.number)text('委托编号 '+sheet.number+(sheet.version?'  /  V'+sheet.version:''),pad,content,20,'#7d776e');
  y+=18;rule(); const top=y;
  sheet.metrics.forEach((metric,i)=>{y=top;text(metric.value+(metric.value==='未记录'?'':' '+metric.unit),pad+i*224,210,29,'#262520',true);text(metric.label,pad+i*224,210,21,'#7d776e');});
  y=top+100;rule();section('客户与项目',sheet.customer);
  sheet.sections.forEach(s=>section(s.title,s.rows));
  if(sheet.budgetVersion)section('方案依据',[{label:'参考预算',value:'V'+sheet.budgetVersion}]);
  if(sheet.fees.length)section('费用与交付',sheet.fees);
  y+=12;text(sheet.note,pad,content,20,'#7d776e');y+=24;
  const height=Math.ceil(y+30);canvas.width=W;canvas.height=height;
  ctx.fillStyle='#ffffff';ctx.fillRect(0,0,W,height);ctx.textBaseline='top';
  for(const cmd of commands){if(cmd.type==='rule'){ctx.fillStyle='#e6e1da';ctx.fillRect(pad,cmd.y,content,1);}else{font(cmd.size,cmd.bold);ctx.fillStyle=cmd.color;ctx.fillText(cmd.text,cmd.x,cmd.y);}}
  return {width:W,height};
}
async function exportImage(page) {
  if(page.data.exporting || !page.data.orderSheet || !page.data.hasOrderSheet) return;
  const alive=require('../../../api/http').sessionGuard(); const current=()=>alive()&&!page.disposed;
  const sheet=JSON.parse(JSON.stringify(page.data.orderSheet));page.setData({exporting:true});
  try {
    const canvas=await new Promise((resolve,reject)=>wx.createSelectorQuery().in(page).select('#commissionCanvas').fields({node:true}).exec(rows=>rows&&rows[0]&&rows[0].node?resolve(rows[0].node):reject(Error('导出组件未就绪，请重试'))));
    if(!current())return;draw(canvas,sheet);
    const result=await new Promise((resolve,reject)=>wx.canvasToTempFilePath({canvas,fileType:'png',success:resolve,fail:reject},page));
    if(!current())return;
    page.setData({exportPath:result.tempFilePath});
    await new Promise((resolve,reject)=>wx.saveImageToPhotosAlbum({filePath:result.tempFilePath,success:resolve,fail:reject}));
    if(current())wx.showToast({title:'委托单已保存到相册',icon:'success'});
  } catch(e) {
    if(current()) {
      if(/auth|deny/i.test(e.errMsg||''))wx.showModal({title:'开启相册权限',content:'保存委托单需要添加到相册权限。开启后可再次保存。',confirmText:'去设置',success:r=>{if(r.confirm&&current())wx.openSetting({});}});
      else wx.showToast({title:e.message||'保存失败，请重试',icon:'none'});
    }
  } finally {if(current())page.setData({exporting:false});}
}
module.exports={model,draw,exportImage};

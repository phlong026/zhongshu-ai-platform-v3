const http=require('../api/http'),api=require('../api/franchise/design'),auth=require('../api/auth');
const active=['QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED'];
const labels={QUEUED:'排队中',RUNNING:'生成中',VALIDATING:'校验中',CANCEL_REQUESTED:'取消中',SUCCEEDED:'已完成',PARTIALLY_SUCCEEDED:'部分完成',FAILED:'生成失败',CANCELLED:'已取消'};
const read=(path,query={})=>http.post('/franchise/design/flow',{method:'GET',path,query,body:{}});
function time(value){if(!value)return '';const d=new Date(value);if(!Number.isFinite(d.getTime()))return '';const pad=n=>String(n).padStart(2,'0');return (d.getMonth()+1)+'月'+d.getDate()+'日 '+pad(d.getHours())+':'+pad(d.getMinutes());}
function row(p,j={}){
 const id=String(p.projectId||''),jobId=String(j.jobId||p.jobId||''),status=j.status||p.jobStatus||'',stage=(j.phase||p.stage)==='ELEVATION'?'ELEVATION':'FLAT';
 const count=Number(j.requestedCount||p.requestedCount),accepted=Number(j.acceptedCount),resolution=(j.imageOptions||p.imageOptions||p).resolution||'';
 const metadata=[time(j.createdAt||p.createdAt||p.createTime),count>0?count+'张':'',resolution].filter(Boolean).join(' · ');
 return {id,jobId,stage,resumeAction:p.resumeAction||'',title:(p.name||'独立方案')+' · '+(stage==='ELEVATION'?'AI立面':'AI平面'),metadata,
  status,statusText:labels[status]||'待确认',running:active.includes(status),tone:active.includes(status)?'warm':['SUCCEEDED','PARTIALLY_SUCCEEDED'].includes(status)?'green':['FAILED','CANCELLED'].includes(status)?'red':'muted',
  progress:count>0&&Number.isFinite(accepted)?'已生成 '+accepted+'/'+count+' 张':'',count:count>0?count:0,
  action:['SELECT_FLAT','SELECT_ELEVATION'].includes(p.resumeAction)?'选择新方案':active.includes(status)?'查看进度':status==='FAILED'?'查看原因':['SUCCEEDED','PARTIALLY_SUCCEEDED'].includes(status)?'查看结果':'查看详情',
  resultVersionId:p.resultVersionId?String(p.resultVersionId):'',coverAssetId:p.coverAssetId||p.selectedElevationAssetId||p.selectedFlatAssetId||'',coverUrl:'',icon:stage==='ELEVATION'?'/assets/house.png':'/assets/plan-file.png'};
}
function stop(page){page.__recentHidden=true;page.__recentSequence=(page.__recentSequence||0)+1;if(page.__recentTimer)clearTimeout(page.__recentTimer);page.__recentTimer=null;}
function scope(){const p=http.token()&&auth.currentApiPost();return p?JSON.stringify([http.sessionGeneration(),http.currentUserId(),http.tenantId(),p.userId,p.tenantId,p.orgId,p.postId,p.roleCode,p.orgType]):'';}
const unusable=e=>e&&(e.postInvalid||[401,403,404,410].includes(Number(e.code)));
const same=(a,b)=>a.id===b.id&&String(a.coverAssetId)===String(b.coverAssetId)&&a.resultVersionId===b.resultVersionId;
function clear(page){page.setData({recentTasks:[],recentCount:0,recentRunning:0,recentLoading:false,recentError:''});}
function cover(page,row,url){page.setData({recentTasks:page.data.recentTasks.map(t=>same(t,row)?{...t,coverUrl:url}:t)});}
function coverError(page,e){
 if(page.__recentHidden)return;
 if(page.__recentScope!==scope()){clear(page);return;}
 const {id,cover:failed}=e.currentTarget.dataset,row=page.data.recentTasks.find(t=>t.id===String(id)&&t.coverUrl===failed);
 if(row)cover(page,row,'');
}
async function load(page){
 if(page.__recentHidden)return;
 const key=scope();if(page.__recentScope!==key){clear(page);page.__recentScope=key;}if(!key)return;
 if(page.__recentTimer)clearTimeout(page.__recentTimer);
 const sequence=page.__recentSequence=(page.__recentSequence||0)+1,guard=http.requestGuard('design-recent');
 const valid=()=>{if(page.__recentHidden||page.__recentSequence!==sequence)return false;if(scope()!==key){clear(page);return false;}return guard();};
 page.setData({recentLoading:true,recentError:''});
 try{
  const result=await read('/design-projects',{limit:3});if(!valid())return;
  const projects=(result&&result.list||[]).filter(p=>p&&p.projectId&&p.mock!==true&&p.demo!==true).slice(0,3);
  let detailError='';
  const rows=await Promise.all(projects.map(async p=>{
   try{const detail=await read('/design-projects/'+encodeURIComponent(p.projectId));if(!valid())return null;
    const job=detail.jobId?await http.get('/franchise/design/flow-ai-jobs/'+encodeURIComponent(detail.jobId)):{};
    return row({...p,...detail},job);
   }catch(e){
    if(unusable(e))throw e;detailError='任务更新失败，请重试';
    const next=row(p),previous=page.data.recentTasks.find(t=>t.id===next.id),knownCover=['coverAssetId','selectedElevationAssetId','selectedFlatAssetId'].some(k=>k in p);
    return previous&&(!knownCover||String(previous.coverAssetId)===String(next.coverAssetId))&&(!('resultVersionId'in p)||previous.resultVersionId===next.resultVersionId)?{...previous}:next;
   }
  }));
  if(!valid())return;const recentTasks=rows.filter(Boolean);
  // Only the same authorized resource/version may keep its already displayed preview.
  recentTasks.forEach(r=>{const previous=page.data.recentTasks.find(t=>same(t,r));if(r.coverAssetId&&previous)r.coverUrl=previous.coverUrl;});
  page.setData({recentTasks,recentCount:recentTasks.length,recentRunning:recentTasks.filter(r=>r.running).length,recentLoading:false,recentError:detailError});
  recentTasks.filter(r=>r.coverAssetId).forEach(r=>api.assets.downloadUrl(r.coverAssetId).then(async ticket=>{
   if(!valid()||!ticket||!ticket.downloadUrl)return;
   // Pre-read the replacement so signing/downloading never removes a usable cover.
   const image=await new Promise((resolve,reject)=>wx.getImageInfo({src:ticket.downloadUrl,success:resolve,fail:reject}));
   if(valid()&&image.path)cover(page,r,image.path);
  }).catch(e=>{if(valid()&&unusable(e)){if(e.postInvalid||Number(e.code)===401)clear(page);else cover(page,r,'');}}));
 }catch(e){if(valid()){if(unusable(e))clear(page);page.setData({recentLoading:false,recentError:e.message||'最近任务读取失败，请重试'});}}
 finally{if(valid()&&page.data.recentTasks.some(r=>r.running))page.__recentTimer=setTimeout(()=>load(page),15000);}
}
function open(page,e){
 const r=page.data.recentTasks.find(r=>r.id===String(e.currentTarget.dataset.id));if(!r||!http.token())return;
 let url='/design-migration/pages/profile/record?type=projects&id='+encodeURIComponent(r.id);
 if(r.resumeAction==='VIEW_RESULT'&&r.resultVersionId)url='/design-migration/pages/ai-design/result?projectId='+encodeURIComponent(r.id)+'&resultVersionId='+encodeURIComponent(r.resultVersionId);
 else if(r.jobId)url='/design-migration/pages/ai-design/generating?projectId='+encodeURIComponent(r.id)+'&jobId='+encodeURIComponent(r.jobId)+'&stage='+(r.stage==='ELEVATION'?'elevation':'plane')+(r.count?'&count='+r.count:'');
 wx.navigateTo({url,fail:()=>wx.showToast({title:'页面未打开，请重试',icon:'none'})});
}
module.exports={row,load,stop,open,coverError};

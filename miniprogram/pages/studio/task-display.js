const DEMO_IDS=['task-progress','task-complete','task-retry'];
const TEST_PROVIDERS=['fake','mock','demo','fixture'];
function isDemo(task) {
  return !!task&&(task.mock===true||task.demo===true||DEMO_IDS.includes(String(task.id))||TEST_PROVIDERS.includes(String(task.provider||'').toLowerCase()));
}
function stage(status) {
  return {QUEUED:'等待处理',SUBMITTING:'正在提交',RUNNING:'正在处理',ARCHIVING:'正在保存成果',SUCCEEDED:'已完成',FAILED:'处理失败',SUBMISSION_UNCERTAIN:'结果确认中'}[status]||'状态待确认';
}
function icon(kind) {return kind==='ai_video'?'/assets/video-filled.png':kind==='digital'?'/assets/person-filled.png':'/assets/plan-file.png';}
module.exports={isDemo,stage,icon};

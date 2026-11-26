const api = require('./api');
const view = require('./record-view');

function target(project) {
  const id = view.id(project && project.projectId);
  if (!id) throw { msg: '项目编号无效' };
  if (project.resumeAction === 'VIEW_RESULT' && view.id(project.resultVersionId)) {
    return '/design-migration/pages/ai-design/result?projectId=' + id + '&resultVersionId=' + project.resultVersionId;
  }
  if (['POLL_JOB','SELECT_FLAT','SELECT_ELEVATION'].includes(project.resumeAction) && view.id(project.jobId)) {
    const stage = project.stage === 'ELEVATION' ? 'elevation' : 'plane';
    return '/design-migration/pages/ai-design/generating?projectId=' + id + '&jobId=' + project.jobId + '&stage=' + stage;
  }
  return null;
}

function resume(projectId) {
  const http = require('./request');
  const session = http.captureSession();
  return api.getProject(projectId).then(project => {
    if (!http.isSameSession(session)) throw {code: 'SESSION_CHANGED', msg: '登录账号已变化，请重新进入'};
    const url = target(project);
    if (!url) throw {msg: '该方案暂无可查看的任务或结果'};
    wx.navigateTo({url});
  });
}

module.exports = {target, resume};

// 仅识别并封存历史薪酬数据，任务管理不承担薪酬与奖金。
const isPayable = row => !!(row?.payrollId || row?.performanceArchive?.kind === 'performance-task-bonus-v1');
function apply(state, action) {
  if (['payroll-policy', 'payroll-submit', 'performance-bonus-register'].includes(action)) throw Error('薪酬与奖金在线下办理，小程序不提供结算');
  return null;
}
module.exports = { isPayable, apply };

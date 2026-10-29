// 状态标签唯一口径（体检T2：同一业务状态全库一个展示词）。
// 分层约定：项目级工程状态用「在建」，节点级保留「施工中」；
// 委托状态以设计台七态为基准词，销售视角可加角色前缀但核心词一致。
const commission = {
  submit: '待提交设计委托',
  pay: '待支付',            // 设计台「待业务支付」/销售「设计费待一次性支付」核心词统一为待支付展示于此
  payStore: '待业务支付',
  firstPending: '待首款登记',
  designing: '设计中',
  modifying: '修改中',
  receiptWaiting: '待到账确认',
  dispatch: '待下发',
  dispatched: '已下发',
  handover: '待移交',
  receive: '待接收',
};

const engineering = {
  prepare: '待开工',
  building: '在建',        // 项目级
  nodeWorking: '施工中',   // 节点级
  nodeDone: '已完成',      // 节点级
  delivery: '已交付',
  archived: '已归档',      // 归档不再被已交付吞并
};

const receipt = {
  pendingRegister: '待登记',
  pendingCheck: '待核算',
  checked: '已核算',
  filed: '已备案',
  supplement: '需补充',
  returned: '退回补充',
};

const payment = {
  pending: '待办理',
  partial: '部分已付',
  paid: '已付清',
  cancelled: '已取消',
  feePending: '费用待老板审批',
};

const material = {
  plan: '待记录采购金额',
  pay: '待付款',
  partial: '部分已付',
  paid: '已付清',
  cancelled: '已取消',
  arrival: '待到货',
  arrivalPartial: '部分到货',
  arrivalDone: '已验收齐全',
};

module.exports = { commission, engineering, receipt, payment, material };

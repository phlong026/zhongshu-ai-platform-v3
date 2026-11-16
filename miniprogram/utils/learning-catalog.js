// 岗位课程与配套资料共用目录；所有入口和详情均通过同一岗位规则。
const chapters = {
  'ai-video': ['找准乡墅客户的关注点', '提取参考视频文案', '用AI改写获客文案', '把文案生成数字人口播', '添加封面与发布文案', '发布作品与记录线索', '复盘数据与优化内容', '完整实操与学习回顾'],
  sales: ['客户登记与责任归属', '客户跟进与预约到店', '需求沟通与方案参考', '预算与正式报价', '委托与合同办理', '工程移交与持续服务'],
  engineering: ['开工资料与施工图版本核对', '现场条件与班组安排', '现场交底与业主确认', '施工计划与材料进场', '节点验收与整改复验', '竣工交付与资料归档'],
  finance: ['收款登记与到账核对', '应收应付与项目台账', '付款核实与凭据管理', '退款与更正', '月度对账与归档', '设计费及项目费用核收'],
  design: ['设计委托与需求核对', '设计费及资料完整性', '项目分派与成果提交', '版本管理与成果审核', '成果下发与修改反馈', '工程资料移交'],
  management: ['团队分工与成员管理', '月度目标与任务派发', '经营指标与绩效跟进', '费用审批与收支核对', '项目协同与异常跟进', '月度复盘与归档']
};
const cover = '/assets/account-course.jpg';
const courses = [
  { id: 'ai-video', name: 'AI短视频获客实战', category: '业务', image: cover, resourceId: 'script' },
  { id: 'sales', name: '客户跟进与业务办理', category: '业务', image: '/pages/account/assets/course-sales.jpg', resourceId: 'sales-guide' },
  { id: 'engineering', name: '乡墅开工与现场交底', category: '工程', image: '/pages/account/assets/course-engineering.jpg', resourceId: 'brief' },
  { id: 'finance', name: '财务收支与项目对账', category: '财务', image: '/assets/management-receipt.jpg', resourceId: 'finance-guide' },
  { id: 'design', name: '设计委托与成果交付', category: '设计', image: '/assets/floor-plan.png', resourceId: 'requirements' },
  { id: 'management', name: '门店经营与团队管理', category: '管理', image: '/assets/account-chart.jpg', resourceId: 'management-guide' }
].map(course => ({ ...course, lessons: chapters[course.id].length }));
const resources = [
  { id: 'script', name: '短视频选题与文案模板', category: '业务', courseId: 'ai-video' },
  { id: 'sales-guide', name: '客户跟进与移交清单', category: '业务', courseId: 'sales' },
  { id: 'brief', name: '乡墅开工交底清单', category: '工程', courseId: 'engineering' },
  { id: 'finance-guide', name: '收支登记与对账清单', category: '财务', courseId: 'finance' },
  { id: 'requirements', name: '客户设计需求采集表', category: '设计', courseId: 'design' },
  { id: 'management-guide', name: '月度任务与经营复盘清单', category: '管理', courseId: 'management' }
].map(resource => ({ ...resource, type: 'PDF', version: 'V1.0', date: '2026-11-15' }));
const roleCourses = {
  sales: ['ai-video', 'sales'], 'sales-lead': ['ai-video', 'sales'],
  engineer: ['engineering'], 'project-manager': ['engineering'],
  finance: ['finance'], 'finance-lead': ['finance'],
  'hq-design-admin': ['design'], boss: courses.map(course => course.id), owner: courses.map(course => course.id),
  'hq-operations': ['ai-video'], 'hq-operations-lead': ['ai-video']
};
const canStudy = (role, id) => (roleCourses[role] || []).includes(id);
const coursesFor = role => courses.filter(course => canStudy(role, course.id));
const resourcesFor = role => resources.filter(resource => canStudy(role, resource.courseId));
module.exports = { chapters, courses, resources, roleCourses, canStudy, coursesFor, resourcesFor };

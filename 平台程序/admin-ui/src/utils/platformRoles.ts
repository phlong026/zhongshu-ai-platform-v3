const roleLabels: Record<string, string> = {
  boss: '老板',
  owner: '门店负责人',
  sales: '业务员',
  'sales-lead': '业务负责人',
  engineer: '工程人员',
  'project-manager': '工程负责人',
  finance: '财务专员',
  'finance-lead': '财务负责人',
  'hq-admin': '总部管理员',
  'hq-design-admin': '设计行政',
  'hq-designer': '设计师',
  'hq-design-lead': '设计负责人',
  'hq-operations': '运营专员',
  'hq-operations-lead': '运营负责人'
}

export const roleLabel = (roleCode: string) => roleLabels[roleCode] || roleCode

const navigation = require('../../../utils/page-navigation');
// Old business links continue to resolve customer/commission IDs without loading legacy pricing or budget prerequisites.
module.exports = page => {
  const post = page.post();
  if (!post || post.orgType !== 'store' || !['sales', 'sales-lead', 'boss'].includes(post.roleCode)
      || page.historyOnly || !['submit', 'progress'].includes(page.view)) return false;
  const value = page.customerId || page.id;
  if (!/^[1-9]\d{0,18}$/.test(String(value || ''))) return false;
  navigation.open('/pages/external-entrust/index?' + (page.customerId ? 'customerId=' : 'commissionId=') + encodeURIComponent(value), { replace: true });
  return true;
};

function canViewAllStores(post) {
  // Match the server's current-post check; other headquarters roles have no global map scope.
  return !!post && post.orgType === 'headquarters'
    && ['hq-admin', 'finance', 'finance-lead'].includes(post.roleCode);
}

module.exports = { canViewAllStores };

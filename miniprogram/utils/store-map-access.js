function canViewAllStores(post) {
  return !!post && post.orgType === 'headquarters' && Array.isArray(post.permissions)
    && post.permissions.includes('franchise:store-map:read-all');
}

module.exports = { canViewAllStores };

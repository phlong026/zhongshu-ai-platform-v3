function canViewAllStores(post) {
  // The API validates the active post; every headquarters post has read-only map access.
  return !!post && post.orgType === 'headquarters';
}

module.exports = { canViewAllStores };

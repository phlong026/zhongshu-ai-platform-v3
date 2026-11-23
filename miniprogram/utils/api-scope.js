const http = require('../api/http');

// Scope only authenticated, in-memory views; this is not an authorization cache.
module.exports = () => {
  if (!http.token()) return '';
  const p = require('../api/auth').currentApiPost() || {};
  return JSON.stringify([http.sessionGeneration(), http.currentUserId(), http.tenantId(), http.currentPostId(),
    p.userId, p.tenantId, p.orgId, p.postId, p.roleCode, p.orgType, p.teamId, p.lead, p.permissions]);
};

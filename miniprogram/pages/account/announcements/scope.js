const http = require('../../../api/http');
const auth = require('../../../api/auth');
const apiScope = require('../../../utils/api-scope');

// A store's brand can change while the post ID remains the same.
module.exports = () => http.token() ? JSON.stringify([apiScope(), (auth.currentApiPost() || {}).brandId || '']) : '';

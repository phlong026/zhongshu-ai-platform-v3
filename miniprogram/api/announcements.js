const http = require('./http');
const endpoint = '/platform/announcements/app';

module.exports = {
  page: (pageNo, pageSize, keyword) => http.get(endpoint, { pageNo, pageSize, ...(keyword ? { keyword } : {}) }),
  get: id => http.get(endpoint + '/' + encodeURIComponent(id))
};

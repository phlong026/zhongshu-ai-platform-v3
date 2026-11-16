const http = require('./http');
const base = '/training/app';
const part = value => encodeURIComponent(String(value));
module.exports = {
  courses: keyword => http.get(base + '/courses', { keyword: keyword || '' }),
  course: id => http.get(base + '/courses/' + part(id)),
  materials: keyword => http.get(base + '/materials', { keyword: keyword || '' }),
  play: (course, lesson) => http.post(base + '/courses/' + part(course) + '/lessons/' + part(lesson) + '/play', {}),
  beat: (playId, position) => http.post(base + '/progress', { playId, position }),
  read: playId => http.post(base + '/read', { playId, position: 0 }),
  ticket: (course, version, purpose) => http.post(base + '/courses/' + part(course) + '/materials/' + part(version) + '/ticket?purpose=' + part(purpose), {}),
  url: ticket => http.base().replace(/\/$/, '') + ticket.path,
  identity: () => [http.token(), http.tenantId(), http.currentPostId(), http.sessionGeneration()].join('|')
};

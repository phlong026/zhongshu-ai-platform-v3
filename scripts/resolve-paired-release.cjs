const fs = require('node:fs');
const SHA = /^[0-9a-f]{40}$/;

function marker(body) {
  if (body == null) return null;
  if (typeof body !== 'string') throw Error('Pull request body must be text');
  const occurrences = body.match(/Paired-Backend-SHA/gi) || [];
  if (!occurrences.length) return null;
  const lines = body.split(/\r?\n/).filter(line => /Paired-Backend-SHA/i.test(line));
  const match = occurrences.length === 1 && /^[ \t]*Paired-Backend-SHA:[ \t]*([0-9a-f]{40})[ \t]*$/.exec(lines[0]);
  if (!match) throw Error('Use exactly one standalone Paired-Backend-SHA line with a 40-character lowercase commit');
  return match[1];
}
function sha(value, label) {
  if (typeof value !== 'string' || !SHA.test(value)) throw Error(label + ' must be a 40-character lowercase commit');
  return value;
}
function flag(value) {
  if (value === undefined || value === false || value === 'false' || value === '') return false;
  if (value === true || value === 'true') return true;
  throw Error('require_external_handover must be true or false');
}
function selectPair(eventName, event, currentSha) {
  if (!['pull_request', 'push', 'workflow_dispatch'].includes(eventName) || !event || typeof event !== 'object' || Array.isArray(event))
    throw Error('Unsupported paired verification event');
  // Validate metadata before choosing input overrides; invalid or duplicate markers never silently fall back.
  const backendMarker = marker(event.pull_request?.body);
  const inputs = eventName === 'workflow_dispatch' && event.inputs !== undefined ? event.inputs : {};
  if (!inputs || typeof inputs !== 'object' || Array.isArray(inputs)) throw Error('Workflow inputs must be an object');
  if (inputs.frontend_sha !== undefined && inputs.frontend_sha !== '') sha(inputs.frontend_sha, 'Frontend SHA');
  if (inputs.backend_sha !== undefined && inputs.backend_sha !== '' && inputs.backend_sha !== 'backend-main') sha(inputs.backend_sha, 'Backend SHA');
  const frontend = inputs.frontend_sha || (eventName === 'pull_request' ? event.pull_request?.head?.sha : currentSha);
  const backend = inputs.backend_sha || backendMarker || 'backend-main';
  sha(frontend, 'Frontend SHA');
  if (backend !== 'backend-main') sha(backend, 'Backend SHA');
  const requested = flag(inputs.require_external_handover);
  return { frontend_sha: frontend, backend_sha: backend,
    backend_source: inputs.backend_sha ? 'workflow-input' : backendMarker ? 'pr-marker' : 'backend-main',
    require_external_handover: !!backendMarker || SHA.test(backend) || requested };
}
function run(env = process.env) {
  let event;
  try { event = JSON.parse(fs.readFileSync(env.GITHUB_EVENT_PATH, 'utf8')); }
  catch (_) { throw Error('Cannot read a valid GitHub event payload'); }
  const selected = selectPair(env.GITHUB_EVENT_NAME, event, env.GITHUB_SHA);
  if (!env.GITHUB_OUTPUT) throw Error('GitHub output file is unavailable');
  fs.appendFileSync(env.GITHUB_OUTPUT, Object.entries(selected).map(([key, value]) => key + '=' + value + '\n').join(''));
  if (env.GITHUB_STEP_SUMMARY) fs.appendFileSync(env.GITHUB_STEP_SUMMARY,
    '## Selected frontend/backend pair\n\n'
    + '- Frontend: `' + selected.frontend_sha + '`\n'
    + '- Backend: `' + selected.backend_sha + '` (' + selected.backend_source + ')\n'
    + '- External handover: ' + (selected.require_external_handover
      ? 'required; missing capabilities must fail this run.'
      : 'capability check; the executor must report when a legacy pair does not cover this flow.') + '\n');
  return selected;
}
module.exports = { marker, selectPair, run };
if (require.main === module) {
  try { run(); }
  catch (error) { console.error('::error::' + error.message); process.exitCode = 1; }
}

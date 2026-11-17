// Temporary, in-memory input only. A new actor/session invalidates both input and task request keys.
let draft;
function current(scope, generation) {
  if (!draft || draft.scope !== scope || draft.generation !== generation) {
    draft = { scope, generation, link: '', attempt: null };
  }
  return draft;
}
function read(scope, generation) { return current(scope, generation).link; }
function write(scope, generation, value) {
  const text = String(value || '').slice(0, 2000);
  current(scope, generation).link = text;
  return text;
}
function attempt(scope, generation, url, createKey) {
  const value = current(scope, generation);
  if (!value.attempt || value.attempt.url !== url) {
    const key = createKey();
    value.attempt = Object.freeze({ url, parseKey: key + '-parse', asrKey: key + '-asr' });
  }
  return value.attempt;
}
// 仅明确 FAILED 才允许下次点击创建新任务；超时/未知沿用幂等键，避免重复扣费。
function failed(scope, generation, previous, phase, createKey) {
  if (!draft || draft.scope !== scope || draft.generation !== generation || draft.attempt !== previous) return;
  const key = createKey();
  draft.attempt = Object.freeze({ url: previous.url,
    parseKey: phase === 'parse' ? key + '-parse' : previous.parseKey, asrKey: key + '-asr' });
}
module.exports = { read, write, attempt, failed };

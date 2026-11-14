import { readFileSync } from 'node:fs';

const fixed = {
  ZS_AI_CORE_URL: process.env.ZS_AI_CORE_URL,
  ZS_AI_JOURNAL_DIR: process.env.ZS_AI_JOURNAL_DIR
};
if (!fixed.ZS_AI_CORE_URL || !fixed.ZS_AI_JOURNAL_DIR) throw Error('FIXED_RUNTIME_BOUNDARY_REQUIRED');
const seen = new Set();
for (const [index, raw] of readFileSync('/run/secrets/ai-runtime.env', 'utf8').split(/\r?\n/).entries()) {
  const line = raw.trim();
  if (!line || line.startsWith('#')) continue;
  const match = /^([A-Z][A-Z0-9_]*)='([^'\r\n]*)'$/.exec(line);
  if (!match || !match[1].startsWith('ZS_') || seen.has(match[1])) throw Error(`INVALID_RUNTIME_CONFIG_LINE_${index + 1}`);
  if (Object.hasOwn(fixed, match[1])) throw Error('FIXED_RUNTIME_BOUNDARY_OVERRIDE');
  seen.add(match[1]);
  process.env[match[1]] = match[2];
}
Object.assign(process.env, fixed);
await import('/app/src/main.mjs');

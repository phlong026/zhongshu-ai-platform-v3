import { createHash, createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';

let secret = '';
for (const raw of readFileSync('/run/secrets/ai-runtime.env', 'utf8').split(/\r?\n/)) {
  const match = /^ZS_INTERNAL_SECRET='([^'\r\n]+)'$/.exec(raw.trim());
  if (match) secret = match[1];
}
if (secret.length < 32) throw Error('RUNTIME_SMOKE_SECRET');
const path = '/internal-api/design/v1/ai-jobs/auth-smoke';
const body = Buffer.from('{}');
const timestamp = String(Math.floor(Date.now() / 1000));
const bodyHash = createHash('sha256').update(body).digest('hex');
const signature = createHmac('sha256', secret).update(`${timestamp}\nPOST\n${path}\n${bodyHash}`).digest('hex');
const response = await fetch(process.env.ZS_AI_CORE_URL + path, { method: 'POST', body,
  headers: { 'Content-Type': 'application/json', 'X-ZS-Timestamp': timestamp, 'X-ZS-Signature': signature },
  signal: AbortSignal.timeout(5000), redirect: 'error' });
if (response.status !== 204) throw Error(`RUNTIME_TO_PROVIDER_SMOKE_${response.status}`);

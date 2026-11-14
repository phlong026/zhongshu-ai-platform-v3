import { createServer } from 'node:http';
import { config, Core, Provider, Journal, runJob, runTraceReconciler, runCoreEventLoop, sleep, safeFailureCode } from './runtime.mjs';
import { createInternalHandler, createRequestListener } from './internal-http.mjs';

let settings;
try { settings = config(); } catch (error) { console.error(safeFailureCode(error)); process.exit(1); }
if (process.argv.includes('--check')) { console.log('Runtime configuration structure valid; no channel request performed.'); process.exit(0); }
const journal = new Journal(settings.journalPath, { capacityProofSha256: settings.capacityProofSha256 });
try { journal.prepareCapacity(); }
catch (error) { console.error(safeFailureCode(error)); process.exit(1); }
const staleCapacity = journal.capacityReservations();
if (staleCapacity.length) console.error(JSON.stringify({ event: 'runtime_stale_capacity_detected', count: staleCapacity.length }));
const core = new Core(settings), provider = new Provider(settings, journal);
const stop = new AbortController(); let lastPoll = Date.now(), active = false;
// 对外入口：/health 语义不变；补充需求润色经内部签名校验后由本进程调用供应商（密钥不出本进程）
const internal = createInternalHandler({ settings, provider, journal, log: line => console.error(JSON.stringify(line)) });
const server = createServer(createRequestListener({ internal, version: '1.0.0',
  healthy: () => !stop.signal.aborted && journal.capacityHealthy() && (active || Date.now() - lastPoll < 60000) }));
server.listen(8081, '0.0.0.0');
for (const event of ['SIGTERM', 'SIGINT']) process.on(event, () => { stop.abort(); server.close(); });
const traceReconciliation = runTraceReconciler(core, provider, { signal: stop.signal });
const coreEventReconciliation = runCoreEventLoop(core, journal, { signal: stop.signal });
while (!stop.signal.aborted) {
  try {
    if (!journal.capacityHealthy()) {
      console.error('{"event":"runtime_journal_low_space"}');
      await sleep(3000); continue;
    }
    const jobs = await core.post('/internal-api/design/v1/ai-jobs/claims', { workerId: settings.workerId, maxJobs: 1 }, stop.signal);
    if (!Array.isArray(jobs) || jobs.length > 1) throw Error('CLAIM_CONTRACT');
    lastPoll = Date.now();
    for (const job of jobs) {
      active = true;
      try { await runJob(job, core, provider, settings, { signal: stop.signal }); console.log(JSON.stringify({ event: 'job_completed', jobId: job.jobId })); }
      catch { console.error(JSON.stringify({ event: 'job_attempt_incomplete', jobId: /^[0-9]+$/.test(job.jobId) ? job.jobId : 'invalid' })); }
      finally { active = false; }
    }
  } catch { if (!stop.signal.aborted) console.error('{"event":"core_poll_failed"}'); }
  if (!stop.signal.aborted) await sleep(3000);
}
await Promise.all([traceReconciliation, coreEventReconciliation]);

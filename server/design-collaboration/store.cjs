const fs = require('fs');
const path = require('path');

const clone = value => JSON.parse(JSON.stringify(value));
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);

async function atomicWrite(file, state) {
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const temporary = `${file}.${process.pid}.${Date.now()}.tmp`;
  try {
    const handle = await fs.promises.open(temporary, 'wx', 0o600);
    try {
      await handle.writeFile(JSON.stringify(state, null, 2));
      await handle.sync();
    } finally {
      await handle.close();
    }
    await fs.promises.rename(temporary, file);
    const directory = await fs.promises.open(path.dirname(file), 'r');
    try { await directory.sync(); } finally { await directory.close(); }
  } catch (error) {
    await fs.promises.unlink(temporary).catch(() => {});
    throw error;
  }
}

function processAlive(pid) {
  try { process.kill(pid, 0); return true; }
  catch (error) { return error.code === 'EPERM'; }
}

async function acquireDataLock(dataFile) {
  fs.mkdirSync(path.dirname(dataFile), { recursive: true, mode: 0o700 });
  const lockFile = `${dataFile}.lock`;
  const nonce = require('crypto').randomBytes(16).toString('hex');
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      const handle = await fs.promises.open(lockFile, 'wx', 0o600);
      try {
        await handle.writeFile(JSON.stringify({ pid: process.pid, nonce, createdAt: new Date().toISOString() }));
        await handle.sync();
      } finally { await handle.close(); }
      return {
        file: lockFile,
        async release() {
          let record;
          try { record = JSON.parse(await fs.promises.readFile(lockFile, 'utf8')); }
          catch (_) { return; }
          if (record.nonce === nonce && record.pid === process.pid) await fs.promises.unlink(lockFile).catch(() => {});
        }
      };
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
      const stat = await fs.promises.lstat(lockFile).catch(() => null);
      if (!stat?.isFile() || stat.size > 4096) throw Error('数据文件锁无效，请人工核对');
      let record;
      try { record = JSON.parse(await fs.promises.readFile(lockFile, 'utf8')); }
      catch (_) { throw Error('数据文件锁损坏，请人工核对'); }
      if (!Number.isSafeInteger(record.pid) || record.pid <= 0 || !/^[a-f0-9]{32}$/.test(record.nonce || '')) throw Error('数据文件锁内容无效，请人工核对');
      if (processAlive(record.pid)) throw Error(`模拟协作数据已被实例占用（PID ${record.pid}）`);
      await fs.promises.unlink(lockFile);
    }
  }
  throw Error('无法取得模拟协作数据文件锁');
}

function createStore(dataFile, initialState, persistOverride) {
  let current = initialState;
  let queue = Promise.resolve();
  let poisoned = false;
  const write = state => atomicWrite(dataFile, state);
  const persist = state => persistOverride ? persistOverride(state, write) : write(state);

  async function recoverAfterFailure(draft) {
    let disk;
    try { disk = JSON.parse(await fs.promises.readFile(dataFile, 'utf8')); }
    catch (_) { poisoned = true; return { committed: false }; }
    current = disk;
    return { committed: same(disk, draft) };
  }

  async function initialize() {
    if (fs.existsSync(dataFile)) {
      current = JSON.parse(await fs.promises.readFile(dataFile, 'utf8'));
      await fs.promises.chmod(dataFile, 0o600);
    }
    else {
      try { await persist(current); }
      catch (_) {
        const recovered = await recoverAfterFailure(current);
        const error = Error(recovered.committed ? '模拟状态可能已提交，请使用相同幂等键核实' : '模拟状态初始化失败');
        error.status = recovered.committed ? 503 : 500;
        error.code = recovered.committed ? 'commit_unknown' : 'persistence_failed';
        throw error;
      }
    }
  }

  function read() { return clone(current); }

  function transact(change) {
    const operation = queue.then(async () => {
      if (poisoned) {
        const error = Error('模拟状态无法与磁盘对齐，请重启服务核实');
        error.status = 503;
        error.code = 'recovery_required';
        throw error;
      }
      const draft = clone(current);
      const result = await change(draft);
      try { await persist(draft); }
      catch (_) {
        const recovered = await recoverAfterFailure(draft);
        const error = Error(recovered.committed ? '模拟状态可能已提交，请使用相同幂等键核实' : '模拟状态保存失败');
        error.status = recovered.committed ? 503 : 500;
        error.code = recovered.committed ? 'commit_unknown' : 'persistence_failed';
        throw error;
      }
      current = draft;
      return result;
    });
    queue = operation.catch(() => {});
    return operation;
  }

  return { initialize, read, transact, idle: () => queue };
}

module.exports = { createStore, atomicWrite, acquireDataLock };

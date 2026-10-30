// Use the event that opened a task, never the time a workbench was refreshed.
function time(value) {
  if (!value) return 0;
  const normalized = typeof value === 'string' && value.length === 10
    ? value + 'T00:00:00+08:00' : value;
  const parsed = typeof normalized === 'number' ? normalized : Date.parse(normalized);
  return Number.isFinite(parsed) ? parsed : 0;
}

function latest(...values) {
  return values.reduce((best, value) => time(value) > time(best) ? value : best, '');
}

function newest(rows) {
  return rows.slice().sort((a, b) => time(b.triggeredAt) - time(a.triggeredAt));
}

module.exports = { time, latest, newest };

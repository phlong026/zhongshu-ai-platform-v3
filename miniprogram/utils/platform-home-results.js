async function settleHomeSources(promises) {
  const results = await Promise.allSettled(promises);
  const errors = results.filter(result => result.status === 'rejected').map(result => result.reason);
  return {
    values: results.map(result => result.status === 'fulfilled' ? result.value : null),
    fulfilled: results.map(result => result.status === 'fulfilled'),
    errors,
    authError: errors.find(error => error && error.code === 401) || null
  };
}

module.exports = { settleHomeSources };

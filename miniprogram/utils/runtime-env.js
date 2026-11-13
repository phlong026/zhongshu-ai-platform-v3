function storageOverridesAllowed() {
  try {
    const envVersion = wx.getAccountInfoSync().miniProgram.envVersion;
    return envVersion === 'develop' || envVersion === 'trial';
  } catch (e) {
    return false;
  }
}

function configuredValue(defaultValue, storageKey) {
  const stored = storageOverridesAllowed() ? wx.getStorageSync(storageKey) : '';
  return stored || defaultValue || '';
}

module.exports = { storageOverridesAllowed, configuredValue };

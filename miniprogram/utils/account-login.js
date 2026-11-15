const DEFAULT_KEY = 'zs-login-default-account-v1';

function publicAccount(account) {
  if (!account || account.accountId === undefined || !account.loginName) return null;
  return {
    accountId: String(account.accountId),
    loginName: String(account.loginName),
    orgName: String(account.orgName || ''),
    ownerName: String(account.ownerName || '')
  };
}

function defaultAccount() {
  try {
    return publicAccount(wx.getStorageSync(DEFAULT_KEY));
  } catch (e) {
    return null;
  }
}

function saveDefault(account) {
  const safe = publicAccount(account);
  if (safe) wx.setStorageSync(DEFAULT_KEY, safe);
}

function clearDefault() {
  wx.removeStorageSync(DEFAULT_KEY);
}

function validNewPassword(value) {
  const password = String(value || '');
  let bytes = 0;
  for (const character of password) {
    const code = character.codePointAt(0);
    bytes += code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
  }
  return password.length >= 10 && password.length <= 64 && bytes <= 72 && /[A-Za-z]/.test(password) && /\d/.test(password);
}

module.exports = { DEFAULT_KEY, defaultAccount, saveDefault, clearDefault, validNewPassword };

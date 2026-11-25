const DEFAULT_KEY = 'zs-login-default-account-v1';
const CONSENT_KEY = 'zs-login-consent-v1';

function publicAccount(account) {
  if (!account || account.accountId === undefined || !account.loginName) return null;
  return {
    accountId: String(account.accountId),
    loginName: String(account.loginName)
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
  return password.length >= 6 && password.length <= 64 && bytes <= 72;
}

function consent(policy) {
  if (!policy || !policy.agreementVersion || !policy.privacyVersion) return null;
  try {
    const saved = wx.getStorageSync(CONSENT_KEY);
    return saved && saved.accepted === true && saved.agreementVersion === policy.agreementVersion && saved.privacyVersion === policy.privacyVersion
      ? {accepted:true, agreementVersion:policy.agreementVersion, privacyVersion:policy.privacyVersion} : null;
  } catch (e) { return null; }
}
function saveConsent(policy) {
  const value = {accepted:true, agreementVersion:policy.agreementVersion, privacyVersion:policy.privacyVersion};
  try { wx.setStorageSync(CONSENT_KEY, value); } catch (e) { /* 本次明确同意仍有效；下次重新确认。 */ }
  return value;
}
function clearConsent() { try { wx.removeStorageSync(CONSENT_KEY); } catch (e) {} }

module.exports = { CONSENT_KEY, consent, saveConsent, clearConsent, DEFAULT_KEY, defaultAccount, saveDefault, clearDefault, validNewPassword };

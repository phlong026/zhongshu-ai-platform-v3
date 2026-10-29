const Base = require('../../../utils/cross-device-client');

const STORAGE_KEY = require('../../../utils/storage-keys').managementSimulationClient;
const COMMAND_PATTERN = /^\/commands$/;

function createClient(options = {}) {
  return Base.createClient({ ...options, storageKey: options.storageKey || STORAGE_KEY, commandPattern: options.commandPattern || COMMAND_PATTERN });
}

module.exports = { createClient, normalizeBaseUrl: Base.normalizeBaseUrl, STORAGE_KEY, COMMAND_PATTERN };

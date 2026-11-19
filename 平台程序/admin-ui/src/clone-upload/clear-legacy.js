// Remove the previous release's persistent sample store on either admin entry. This must never delay rendering.
try { globalThis.indexedDB?.deleteDatabase('zs-clone-replay') } catch { /* Storage can be disabled. */ }

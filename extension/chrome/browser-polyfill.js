// Chrome and Firefox disagree about how their extension APIs answer.
//
// Chrome's are callback-based (MV3 also returns a promise, but only when no callback is
// given). Firefox's are promise-based and **ignore a callback argument entirely**.
//
// Passing a callback and waiting for it therefore works on Chrome and hangs for ever on
// Firefox — the call succeeds, the reply arrives, and nothing is listening for it. That is
// not a visible error anywhere: the popup simply never updates.
globalThis.browser = globalThis.browser || globalThis.chrome;

/**
 * Calls a browser API that may answer either way, and resolves once.
 *
 * `invoke` is handed a callback and called exactly once. If it also returns a promise —
 * Firefox — that promise wins and the callback is never fired. If it returns nothing —
 * Chrome, given a callback — the callback is what settles this.
 */
globalThis.vgInvoke = function vgInvoke(invoke) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const settle = (value) => {
      if (settled) return;
      settled = true;
      resolve(value);
    };

    let returned;
    try {
      returned = invoke(settle);
    } catch (e) {
      reject(e);
      return;
    }

    if (returned && typeof returned.then === "function") {
      returned.then(settle, reject);
    }
  });
};

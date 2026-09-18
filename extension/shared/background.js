// The only thing that talks to the desktop service.
//
// Content scripts run in the page's world and are the first thing a hostile page reaches;
// they are never given a port to the vault. They ask this worker, and this worker decides.

// Firefox loads browser-polyfill.js through the manifest's background.scripts array.
// Chrome MV3 names a single service worker, so it has to pull it in itself — and a service
// worker is a classic worker, which is what makes importScripts available here and absent
// in Firefox's event page.
if (typeof importScripts === "function") {
  importScripts("browser-polyfill.js");
}

const HOST_NAME = "com.vaultguard.bridge";

const api = globalThis.browser || globalThis.chrome;

/**
 * One request/response over native messaging.
 *
 * sendNativeMessage starts the host, sends, reads one reply and lets it exit. That is the
 * right shape here: requests are rare and user-driven, and a per-message process means no
 * long-lived pipe to leak. The vault stays unlocked in the tray service regardless.
 */
const UNREACHABLE = {
  ok: false,
  serviceUnavailable: true,
  error:
    "Could not reach VaultGuard. Check that `vaultguard --service` is running and " +
    "that the native messaging host is registered.",
};

async function ask(message) {
  try {
    const response = await vgInvoke((done) =>
      api.runtime.sendNativeMessage(HOST_NAME, message, done)
    );

    // Chrome reports a failed host through lastError with an undefined response rather
    // than by rejecting. Firefox rejects, which lands in the catch below.
    if (api.runtime.lastError || !response) return UNREACHABLE;
    return response;
  } catch (e) {
    return { ...UNREACHABLE, detail: String(e && e.message ? e.message : e) };
  }
}

api.runtime.onMessage.addListener((message, sender, sendResponse) => {
  // Requests are only honoured from this extension's own pages and content scripts.
  // sender.id is set by the browser and cannot be forged by a page.
  if (!sender || sender.id !== api.runtime.id) {
    sendResponse({ ok: false, error: "Refused." });
    return false;
  }

  handle(message, sender).then(sendResponse);
  return true; // keep the channel open for the async reply
});

async function handle(message, sender) {
  switch (message && message.type) {
    case "status":
      return ask({ action: "status" });

    case "match":
      // The URL comes from the tab, read here, rather than from the content script. A page
      // cannot claim to be a different site than the one it is on.
      return ask({ action: "match", url: message.url });

    case "fill": {
      const secret = await ask({ action: "secret", id: message.id });
      if (!secret.ok) return secret;

      // The password goes straight into the page and is never stored, cached, or sent back
      // to the popup.
      const [result] = await api.scripting.executeScript({
        target: { tabId: message.tabId },
        func: fillForm,
        args: [secret.username || "", secret.password || ""],
      });
      return { ok: true, filled: result && result.result };
    }

    case "lock":
      return ask({ action: "lock" });

    case "capture": {
      // A login the content script saw being submitted. The URL is the tab's, read here,
      // for the same reason as "match": the page does not get to say where it is. The
      // desktop decides whether anything is worth asking about and asks there.
      const url = sender && sender.tab && sender.tab.url;
      if (!url || !/^https?:/i.test(url)) return { ok: false, error: "No page." };
      return ask({ action: "save", url, username: message.username || "", password: message.password || "" });
    }

    default:
      return { ok: false, error: "Unknown request." };
  }
}

/**
 * Injected into the page to put the values in. Defined here rather than in a content script
 * so it only ever runs when the user has picked an entry.
 */
function fillForm(username, password) {
  const isVisible = (el) =>
    el.offsetParent !== null && !el.disabled && !el.readOnly;

  const passwordField = Array.from(
    document.querySelectorAll('input[type="password"]')
  ).find(isVisible);

  if (!passwordField) return false;

  // The username field is the visible text-ish input closest above the password one, which
  // handles the common layouts without guessing from names in a dozen languages.
  const candidates = Array.from(
    document.querySelectorAll(
      'input[type="text"], input[type="email"], input[type="tel"], input:not([type])'
    )
  ).filter(isVisible);

  const before = candidates.filter(
    (el) =>
      el.compareDocumentPosition(passwordField) &
      Node.DOCUMENT_POSITION_FOLLOWING
  );
  const usernameField = before.length ? before[before.length - 1] : null;

  const set = (el, value) => {
    if (!el || !value) return;
    const setter = Object.getOwnPropertyDescriptor(
      window.HTMLInputElement.prototype,
      "value"
    ).set;
    // Assigning .value directly does not notify React and friends, which then submit an
    // empty form. The native setter plus an input event is what frameworks listen for.
    setter.call(el, value);
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  };

  set(usernameField, username);
  set(passwordField, password);
  passwordField.focus();
  return true;
}

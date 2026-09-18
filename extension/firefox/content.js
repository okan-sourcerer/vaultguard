/*
 * Notices a login being submitted and hands it to the background worker.
 *
 * This is the one thing in the extension that reads what the user typed, so it reads as
 * little as possible: a visible password field with a value, and the visible text-ish
 * field nearest above it, at the moment the form is submitted, Enter is pressed in the
 * password field, or a submit-shaped button in the same form is clicked. Nothing is kept
 * here between events, nothing is sent while the user is still typing, and the page's
 * URL is not read here at all - the background worker takes it from the tab, so a page
 * cannot claim to be a site it is not on.
 *
 * What happens next is the desktop service's decision (SaveDecision in :core): already
 * saved means nothing is shown; a changed password offers an update; the same account
 * saved for another site offers a link; a new one offers to save. The user confirms on
 * the desktop, in a VaultGuard window, never in the page.
 */
(() => {
  const api = typeof browser !== "undefined" ? browser : chrome;

  const isVisible = (el) => el && el.offsetParent !== null && !el.disabled && !el.readOnly;

  // A two-step login shows the account on one page and the password on the next, so at
  // the moment the password is submitted there is no account field to read. The account
  // typed on the previous step is kept in sessionStorage - same tab, same origin, gone
  // when the tab closes - and used when the password page has no field of its own. The
  // phone's autofill merges its contexts the same way (#54).
  const STASH = "vaultguard.account";

  // For reading, a pre-filled read-only or disabled account field is the best source
  // there is - it is the account the password is for. (Filling skips such fields.)
  const isShown = (el) => el && el.offsetParent !== null;

  const looksLikeAccount = (el) =>
    el.type === "email" ||
    /^(username|email)$/i.test(el.autocomplete || "") ||
    /user|email|login|account|mail/i.test([el.name, el.id, el.placeholder, el.getAttribute("aria-label")].join(" "));

  function textCandidates(root, includeReadOnly) {
    return Array.from(
      root.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"], input:not([type])')
    ).filter(includeReadOnly ? isShown : isVisible);
  }

  function usernameFor(passwordField) {
    const scope = passwordField.form || document;
    const before = textCandidates(scope, true).filter(
      (el) => el.compareDocumentPosition(passwordField) & Node.DOCUMENT_POSITION_FOLLOWING
    );
    // A field that says it is the account, else the nearest above within the form. With
    // no form and no such field, do not guess from the whole page.
    const field =
      before.find((el) => looksLikeAccount(el) && el.value.trim()) ||
      (passwordField.form ? before.reverse().find((el) => el.value.trim()) : null);
    if (field) return field.value.trim();
    try {
      return sessionStorage.getItem(STASH) || "";
    } catch (e) {
      return "";
    }
  }

  // The first step of a two-step login: an account field submitted with no password
  // field in sight. Remember the account for the page that follows.
  function stashAccount(root) {
    const candidates = textCandidates(root, false);
    const field = candidates.find(looksLikeAccount) || (candidates.length === 1 ? candidates[0] : null);
    const value = field ? field.value.trim() : "";
    if (!value) return;
    try {
      sessionStorage.setItem(STASH, value);
    } catch (e) {
      // Storage blocked; the password step will just have no account to attach.
    }
  }

  let lastSent = "";

  function capture(passwordField) {
    if (!isVisible(passwordField)) return;
    const password = passwordField.value;
    if (!password) return;
    const username = usernameFor(passwordField);

    // The same pair twice in a row - a retry after a wrong password on the site's side,
    // say - is one capture, not two dialogs.
    const key = username + "\u0000" + password;
    if (key === lastSent) return;
    lastSent = key;

    try {
      api.runtime.sendMessage({ type: "capture", username, password });
    } catch (e) {
      // The extension was reloaded under this page; nothing to do.
    }
  }

  function passwordFieldIn(form) {
    return Array.from(form.querySelectorAll('input[type="password"]')).find(isVisible) || null;
  }

  // A real form submit.
  document.addEventListener(
    "submit",
    (event) => {
      const form = event.target;
      if (!(form instanceof HTMLFormElement)) return;
      const field = passwordFieldIn(form);
      if (field) capture(field);
      else stashAccount(form);
    },
    true
  );

  // Enter in the password field, which many single-page logins handle without a submit.
  document.addEventListener(
    "keydown",
    (event) => {
      if (event.key !== "Enter") return;
      const target = event.target;
      if (target instanceof HTMLInputElement && target.type === "password") capture(target);
    },
    true
  );

  // A click on something submit-shaped near a password field.
  document.addEventListener(
    "click",
    (event) => {
      const target = event.target instanceof Element ? event.target : null;
      const button = target && target.closest('button, input[type="submit"], [role="button"]');
      if (!button) return;
      const form = button.closest("form");
      const field = form ? passwordFieldIn(form) : Array.from(document.querySelectorAll('input[type="password"]')).find(isVisible);
      if (field) capture(field);
      else stashAccount(form || document);
    },
    true
  );

  // Enter in an account field on a page with no password field: the first step.
  document.addEventListener(
    "keydown",
    (event) => {
      if (event.key !== "Enter") return;
      const target = event.target;
      if (!(target instanceof HTMLInputElement) || target.type === "password") return;
      const root = target.form || document;
      if (!passwordFieldIn(root)) stashAccount(root);
    },
    true
  );
})();

const api = globalThis.browser || globalThis.chrome;

const messageEl = document.getElementById("message");
const entriesEl = document.getElementById("entries");
const lockButton = document.getElementById("lock");
const statusEl = document.getElementById("status");
const statusTextEl = document.getElementById("statusText");

const send = (message) =>
  vgInvoke((done) => api.runtime.sendMessage(message, done));

/**
 * The three failure modes need three different actions from the user, so they get three
 * different appearances. Collapsing them into one grey sentence is what made a hung popup
 * indistinguishable from a locked vault.
 */
function setStatus(kind, text, detail) {
  statusEl.className = `status ${kind}`;
  statusTextEl.textContent = text;

  const existing = document.getElementById("detail");
  if (existing) existing.remove();

  if (detail) {
    const p = document.createElement("p");
    p.id = "detail";
    p.textContent = detail;
    statusEl.after(p);
  }
}

function say(text) {
  messageEl.textContent = text;
}

function render(credentials, tabId) {
  entriesEl.replaceChildren();

  for (const credential of credentials) {
    const button = document.createElement("button");

    const name = document.createElement("div");
    name.className = "name";
    // textContent, never innerHTML: these strings come out of the vault and an entry named
    // after a script tag should be an odd name, not an execution.
    name.textContent = credential.name || "(unnamed)";

    const username = document.createElement("div");
    username.className = "username";
    username.textContent = credential.username || "no username";

    button.append(name, username);
    button.addEventListener("click", async () => {
      say("Filling...");
      const result = await send({ type: "fill", id: credential.id, tabId });
      if (!result.ok) {
        say(result.error || "Could not fill.");
      } else if (!result.filled) {
        say("No password field found on this page.");
      } else {
        window.close();
      }
    });

    const item = document.createElement("li");
    item.append(button);
    entriesEl.append(item);
  }
}

async function main() {
  const [tab] = await api.tabs.query({ active: true, currentWindow: true });

  const status = await send({ type: "status" });

  if (!status.ok) {
    if (status.serviceUnavailable) {
      setStatus(
        "error",
        "Service not reachable",
        "Start VaultGuard with `vaultguard --service`. If it is already running, the " +
          "native messaging host may not be registered."
      );
    } else {
      setStatus("error", "VaultGuard refused", status.error || "");
    }
    return;
  }

  if (status.locked) {
    // The host answered, so the bridge is fine and only the vault is shut. Worth
    // distinguishing: the fix is a click on the tray, not a reinstall.
    setStatus("locked", "Vault locked", "Unlock it from the VaultGuard tray icon, then reopen this.");
    return;
  }

  setStatus("ok", "Connected");

  lockButton.hidden = false;
  lockButton.addEventListener("click", async () => {
    await send({ type: "lock" });
    window.close();
  });

  if (!tab || !tab.url) {
    say("No page to match against.");
    return;
  }

  const matches = await send({ type: "match", url: tab.url });
  if (!matches.ok) {
    say(matches.error || "Could not match this page.");
    return;
  }

  if (!matches.credentials.length) {
    setStatus("ok", `Connected - nothing saved for ${matches.host}`);
    return;
  }

  setStatus(
    "ok",
    `Connected - ${matches.credentials.length} for ${matches.host}`
  );
  render(matches.credentials, tab.id);
}

main().catch((e) => setStatus("error", "Something went wrong", String(e)));

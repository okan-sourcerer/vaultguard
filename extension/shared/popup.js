const api = globalThis.browser || globalThis.chrome;

const messageEl = document.getElementById("message");
const entriesEl = document.getElementById("entries");
const lockButton = document.getElementById("lock");

const send = (message) =>
  vgInvoke((done) => api.runtime.sendMessage(message, done));

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
    say(status.error || "VaultGuard is unavailable.");
    return;
  }

  if (status.locked) {
    say("The vault is locked. Unlock it from the VaultGuard tray icon, then reopen this.");
    return;
  }

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
    say(`Nothing saved for ${matches.host}.`);
    return;
  }

  say("");
  render(matches.credentials, tab.id);
}

main();

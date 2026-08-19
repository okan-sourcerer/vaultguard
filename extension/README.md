# VaultGuard browser extension

Fills passwords from the vault held open by the desktop service. It never holds a key,
never decrypts anything, and never stores a credential.

## How the pieces fit

```
extension (Chrome MV3 / Firefox)
    │  runtime.sendNativeMessage — the browser decides which extension may talk to the host
    ▼
vaultguard --native-host          a relay; holds nothing, spawned per connection
    │  loopback socket + token from ~/.vaultguard/bridge.json
    ▼
vaultguard --service              the tray process that holds the unlocked vault
```

The middle hop exists because a browser starts a **new** host process per connection. A
fresh process has no unlocked vault and could only ask for the master password again, every
time — which is what the tray service exists to avoid.

## Two rules the code follows

**Matching happens in Kotlin, not JavaScript.** `CredentialMatcher` in `:core` decides which
credentials may be offered — the same code the Android autofill service uses. Findings #10
and #11 were both a second, looser copy of that decision; a third written in JS, guarding a
browser, would be the worst place yet for a lookalike-domain hole.

**Matching never returns a password.** `match` answers with names and usernames. The
password comes from a separate `secret` call naming one id, made only once the user has
picked an entry. A page that somehow reached the bridge would learn that a credential
exists, not what it is.

The URL matched against is read from the tab by the background worker, never accepted from
the content script — a page cannot claim to be a site it is not on.

## Installing

1. Build and start the service:

   ```
   gradlew :desktop:installDist
   desktop\build\install\vaultguard\bin\vaultguard --service
   ```

2. Load the extension unpacked.

   - **Chrome** — `chrome://extensions`, enable Developer mode, *Load unpacked*,
     choose `extension/chrome`. Copy the extension ID it shows.
   - **Firefox** — `about:debugging#/runtime/this-firefox`, *Load Temporary Add-on*,
     choose `extension/firefox/manifest.json`.

3. Register the native messaging host.

   ```
   vaultguard --install-bridge                       # Firefox only
   vaultguard --install-bridge <chrome-extension-id> # also Chrome
   ```

   **Firefox needs no id.** It is matched by the add-on id in its own manifest
   (`vaultguard@vaultguard.local`). The UUID `about:debugging` shows is Firefox's internal
   per-install identifier for `moz-extension://` origins, and is not what goes here.
   The argument is the **Chrome** extension id from `chrome://extensions`.

   On Windows this prints a `reg add` command per browser. Run them yourself — they change
   your browser configuration, so they are not run for you.

4. Start the service and unlock it:

   ```
   vaultguard --service
   ```

   The extension talks to `--service`, not to `--cloud`. `--cloud` is the interactive CLI
   and exits when you leave it; the bridge only exists while the tray service runs.

5. Click the extension on a login page.

## Data collection declaration

Firefox 140 onwards requires every new AMO submission to say what it collects, including
saying that it collects nothing. The Firefox manifest declares:

```json
"data_collection_permissions": { "required": ["none"] }
```

That is accurate rather than convenient. The extension reads the active tab's URL to match
against, receives a name and username list, and pushes one password into one form — all of
it between the browser and a process on the same machine. Nothing is transmitted anywhere,
so there is no category to declare. `"none"` is a terminal value and cannot be combined
with one.

The declaration pushes `strict_min_version` to 140.0, since older Firefox does not
understand the key.

## Making it stick in Firefox

Release Firefox refuses to permanently install an unsigned add-on, and **ignores**
`xpinstall.signatures.required` — that preference is only honoured by Developer Edition,
Nightly and ESR. So there are two routes:

- **Developer Edition, Nightly or ESR** — set `xpinstall.signatures.required` to `false` in
  `about:config` and install the zip permanently. Quickest, but a second Firefox.
- **Sign it, unlisted, through addons.mozilla.org** — free, stays private, and the result
  installs on ordinary Firefox. Submit the zip as an *unlisted* add-on and download the
  signed `.xpi`.

Either way, build the zip with:

```
gradlew packageFirefoxExtension
```

It lands in `build/extension/vaultguard-firefox.zip`.

**Chrome does not have this problem.** An unpacked extension stays loaded across restarts
as long as Developer mode is on, so it is the quicker path for checking that the whole chain
works.

## Editing

`extension/shared` is the source. The two browser directories hold a manifest plus copies.
After changing anything shared:

```
gradlew syncExtension
```

## Known rough edges

- The `.bat` shim the browser executes on Windows is the least certain part: whether cmd
  passes native messaging's binary framing through cleanly is a platform question, not an
  API one, and this project has been caught by that category before. If a browser reports
  the host as unresponsive, look there first.
- **A temporary add-on in Firefox is discarded on restart.** Nothing is wrong when it
  disappears; that is what "temporary" means. See *Making it stick in Firefox* below.
- Registering the native messaging host does **not** need a browser restart. The manifest is
  read when the host is launched, not at startup.
- Filling picks the first visible password field and the visible text field above it. That
  covers ordinary login forms and will not cover multi-step or shadow-DOM ones.
- There is no save-on-submit yet: new credentials are added from the CLI, the tray, or the
  phone.

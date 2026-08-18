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

3. Register the native messaging host, passing the Chrome ID from step 2:

   ```
   vaultguard --install-bridge <chrome-extension-id>
   ```

   On Windows this prints two `reg add` commands. Run them yourself — they change your
   browser configuration, so they are not run for you.

4. Unlock from the tray icon, then click the extension on a login page.

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
- Firefox's temporary add-on is discarded when the browser restarts. Signing it, or using
  Developer Edition with `xpinstall.signatures.required=false`, makes it persist.
- Filling picks the first visible password field and the visible text field above it. That
  covers ordinary login forms and will not cover multi-step or shadow-DOM ones.
- There is no save-on-submit yet: new credentials are added from the CLI, the tray, or the
  phone.

# Usability pass — device walkthrough

The script for chunk 15. Run it on the phone holding the live vault, with the app in front
of you rather than the code.

Its purpose is not to confirm the tests pass — they already do. It is to catch the two
things tests cannot see: **platform behaviour**, where Android decides the outcome and the
source gives no hint (three real bugs so far arrived this way), and **friction**, where the
app is correct and still annoying to use.

Record what you find as you go. Anything that is wrong becomes a numbered finding in
[FINDINGS.md](FINDINGS.md) starting at #57; anything that is merely irritating goes in the
Friction log at the end of this file, which is where the next round of work comes from.

---

## Before you start

1. **Settings → Export Vault.** Choose a backup password you will remember. Copy the file
   off the device.
2. **Record the entry count** shown in Vault Stats: ______. Every section below assumes it
   has not changed unless the step says it should.
3. **Install the release build**, not debug:

```bash
./gradlew :app:assembleRelease
```

   Section A depends on code R8 has inlined, so a debug pass proves nothing about it. The
   release APK is debug-signed and upgrades in place — it keeps the vault.

4. **Never uninstall to fix a failed install.** The Keystore key goes with it and the vault
   becomes unrecoverable. Check the signing certificate instead.

Steps marked **[RISK]** can change or destroy vault data. Do not start one until step 1 is
done and you have the file somewhere other than the phone.

---

## Section A — Chunk 14, the unverified code

This is the whole reason the pass is worth doing now. Every item here was written against
documentation and compiles, but the outcome is decided by another process or by a platform
promise. Nothing in this section should be believed until it is seen.

### A1. Inline suggestions appear (#50)

**Do:** open any app with a login form for a site you have saved. Tap the username field
with the vault **unlocked**.

**Expect:** a VaultGuard chip in the keyboard's suggestion strip, above the keys — with a
lock icon, the site name, and the username beneath it. Tapping it fills the form.

**Suspect if:** nothing appears in the strip but tapping the field's drop-down still offers
the credential. That means the Slice is being built and rejected — the R8 question. Note
which keyboard you are using; Gboard is the reference.

### A2. Inline suggestions when locked

**Do:** lock the vault (Settings → Lock Vault Now), then tap a username field.

**Expect:** a chip reading **Unlock VaultGuard**. Tapping it opens the unlock screen; after
the master password, the matching credentials come back **in the strip**, not only in the
drop-down menu.

**Suspect if:** the results return to the drop-down after unlocking. The specs are
forwarded to the auth activity through the intent, and that is the part most likely to be
dropped.

### A3. Two-step login, the whole point (#54)

**Do:** sign in somewhere the username and password are on **separate screens** — Google,
Microsoft, and most banks work this way. Type the username, continue, type the password,
submit.

**Expect:** the save prompt appears **after the password screen**, with the **username
already filled in**.

**Suspect if:** the username box is empty — the merge is not seeing the earlier screen. Or
if a save prompt appears after the *username* screen, before any password was typed —
`FLAG_DELAY_SAVE` is not being honoured.

**Note:** press **Back** to dismiss the prompt without saving. **Skip** is different: it
records a dismissal for that site, which is its own test in B3.

### A4. Two-step login for a credential you already have (#55)

**Do:** the same flow, for an account already in the vault.

**Expect:** **no save prompt at all.** Silence is the correct result.

**Suspect if:** you are asked to save, and accepting would leave a second entry.

### A5. A password-only screen (#55)

**Do:** find a re-authentication prompt — "confirm your password" — for an account in the
vault. Some banking apps ask on launch.

**Expect:** no save prompt. The account is already held.

### A6. The settings gear (#53)

**Do:** Android Settings → Passwords & accounts → Autofill service → the **gear** beside
VaultGuard.

**Expect:** VaultGuard opens **on its own Settings screen**. If the vault is locked you
unlock first, then land on Settings. Back from there goes to the vault list, not out of the
app.

**Suspect if:** you land on the vault list, or the app flashes and closes.

### A7. Cancellation (#51)

**Do:** tap a username field and immediately — before any suggestion appears — dismiss the
keyboard or switch apps. Repeat a few times quickly.

**Expect:** nothing. No stale suggestion appearing over the next screen, no flicker, no
crash.

**Suspect if:** a suggestion for the previous screen's field appears on the new screen.

---

## Section B — Autofill, the rest

Regression cover for chunk 8a, all of it previously verified.

**B1. In a browser (#48).** Log in to a site in Chrome. Suggestions offered, fill works,
and the entry that saves is scoped to *that site*, not to Chrome.

**B2. A new credential saves (#47).** Sign up for something, or change a password
somewhere. The save prompt appears and the entry lands in the vault.

**B3. Skip is scoped (#49).** On site A, tap **Skip** on a save prompt. Then log in to
site B. B must still offer to save. Then Settings → Dismissed save prompts → reset, and
site A offers again.

**B4. Matching is not loose (#11, #13).** Confirm a credential saved for one site is not
offered on an unrelated one with a similar name.

**B5. The toggle honours off (#39).** Settings → turn autofill off. Confirm no suggestions
appear anywhere. Turn it back on.

---

## Section C — Unlock, lock, and the session

**C1. Master password.** Correct password unlocks. Wrong password says so plainly.

**C2. Lockout escalates and survives (#12).** Enter a wrong password five or six times.
The delay should grow past 32 seconds. Force-stop the app and reopen: **the lockout must
still be in force.** You will have to wait it out — do this when you are not in a hurry.

**C3. Biometric (#6, #8, #32).** Unlock with the fingerprint. Then cancel a fingerprint
prompt and confirm the failure is *stated*, not silent.

**C4. Auto-lock persists (#25).** Set the timeout to 1 minute. Force-stop the app and
reopen Settings: the setting must still read 1 minute. Then background the app, wait,
return — it should be locked.

**C5. Screenshots blocked (#13).** Try to screenshot the vault list, the detail screen, and
the autofill unlock prompt. All three should refuse.

**C6. Process death.** Open the vault, then force-stop and reopen. You should get the
unlock screen, never a stale vault list.

---

## Section D — Credentials

**D1. Add** a credential by hand. **Edit** it. **Delete** it. Count returns to your
recorded number.

**D2. Generator hands back (#33).** Add/Edit → generate a password → "Use this password".
It should land in the field you came from, not be lost.

**D3. Copy and auto-clear (#36).** Copy a password from the detail screen. Switch to
another app and wait 30 seconds, then check the clipboard — it should be empty. Then copy
again and paste **immediately**; that must still work.

**D4. Password age (#29).** Edit a credential changing only the *notes*. The age must not
reset. Change the password: it should.

**D5. Search and categories.** Search finds by site and by username. Category chips filter
as expected.

**D6. Breach check (#14).** Run it on a credential. Read the wording closely: it must not
claim to know more than it does.

---

## Section E — Settings and stats

**E1. Vault Stats (#26, #27, #30).** Weak count, duplicate count, and the older-than-90-days
count. Cross-check one by hand — a credential you know is weak should be counted, and two
entries sharing a password should count as one duplicate pair, not two.

**E2. Change master password [RISK] (#5).** Only with the backup from step 1 in hand.
Change it, then: unlock with the new password, confirm **every** entry still opens, and
re-enrol biometrics — they are deliberately disabled by the change, and the UI should say
so.

**E3. Sync copy is truthful (#15).** With sync off, the screen must say the vault is local.
Turn sync on, sync, and confirm the state text matches reality.

**E4. Delete cloud copy [RISK to remote only].** "Turn off and delete cloud copy". The
remote is disposable by design; confirm the local vault is untouched afterwards.

---

## Section F — Backup

**F1. Export.** Already done in step 1. Confirm the file is a plausible size, and that a
wrong backup password on import is rejected with a clear message rather than an empty
import.

**F2. Import [RISK].** Merge mode is additive — importing your own backup **will**
duplicate every entry. Only do this if you are willing to clean up afterwards, or have a
spare device. If you skip it, say so; an untested restore path is worth knowing about.

---

## Section G — The friction sweep

No pass or fail here. Use the app for ten minutes as you normally would and answer:

- How many taps to copy a password for a site you use daily? Is any of them avoidable?
- Does anything make you wait without telling you it is working?
- Does any error message leave you unsure what to *do* next?
- Does auto-lock fire while you are still using the thing you unlocked for?
- Is anything easy to tap by accident that you would regret?
- After autofill saves an entry, is the name it chose the one you would have chosen?
- Is there anywhere the app says something you know to be untrue?

---

## Not testable here

- **The recovery screen (#1, #2).** Reaching it means an unopenable `vault.db`, and
  deliberately corrupting the live one is not worth it. Needs a spare install with a throwaway
  vault.
- **Setup, and the no-recovery warning.** First-run only.
- **Joining an account that already holds a vault (#4).** Needs a second device.

---

## Friction log

| # | Where | What is annoying | Worth fixing? |
| --- | --- | --- | --- |
| | | | |

## Defects found

Add to [FINDINGS.md](FINDINGS.md) as #57 onward, with the section reference from this file
so the reproduction is recorded alongside.

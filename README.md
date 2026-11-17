# Nuxeo Labs WebDAV Direct Edit

Adds an **Open in Microsoft Office** button to the Nuxeo Web UI. One click opens the current document
in Word, Excel or PowerPoint on the user's desktop. When the user saves from the Office application,
the changes go straight back to Nuxeo and update the document.

The document is **locked in Nuxeo as soon as the user clicks**, and unlocked when the file is closed in
Office, the way Nuxeo Drive Direct Edit works. See [Locking](#locking).

Works on both macOS and Windows, which support WebDAV natively.

Web UI is optional: the endpoint and the Automation operations work the same behind a custom UI, see
[Without Web UI (custom UI)](#without-web-ui-custom-ui).

> [!Important]
> For Windows User, read the [Platform Notes](#platform-notes)

> [!IMPORTANT]
> This is a **Nuxeo Presales Sandbox plugin**, not included as part of the Nuxeo Platform and, so,
> it is **not supported**.
> If you need the feature, **we strongly recommend a Nuxeo Partner forks the code and takes ownership**
> (tuning, maintenance, support)

## How it works

The plugin provides its **own WebDAV endpoint**, at `/nuxeo/site/officeedit`. It does not use, and does
not require, the `nuxeo-webdav` addon.

That endpoint is deliberately **not** a general purpose WebDAV server. It implements only the part of
RFC 4918 that Microsoft Office exercises, over a URL space that is two levels deep:

```
/nuxeo/site/officeedit/<sessionToken>/                  a collection holding exactly one file
/nuxeo/site/officeedit/<sessionToken>/<fileName>        the document's main file
```

The first segment is an opaque **edit session token**, minted when the user clicks the button. It
resolves server-side to exactly one document and one user — so it addresses the document *and*
authenticates the request. Addressing this way, rather than by repository path, is what makes the
feature work for *any* document, and what the rest of the design follows from.

The edit cycle itself is driven entirely by Office, except for the lock taken on click:

```
Web UI  --  WebDavDirectEdit.GetUrl  -->  Nuxeo                -->  Nuxeo lock taken
                                                                                       |
Web UI  --  ms-word:ofe|u|https://host/nuxeo/site/officeedit/<token>/Report.docx  -->  Word
                                                                                       |
Word  --  OPTIONS, PROPFIND, LOCK, GET  -->  /nuxeo/site/officeedit/...                |
                                                                                       |
user edits, then saves                                                                 |
                                                                                       |
Word  --  PUT  -->  /nuxeo/site/officeedit/...                     -->  Nuxeo document updated
                                                                                       |
Word  --  UNLOCK  -->  /nuxeo/site/officeedit/...                   -->  Nuxeo lock released
```

### One file, and nothing else

The endpoint enforces a single rule: **only the canonical file name maps to the Nuxeo document. Every
other name in the collection is scratch.**

Scratch files live in a transient store and expire on their own. That is how the temporary files Office
creates while it saves — `~$Report.docx`, `A1B2C3D4.tmp` — are handled without ever becoming Nuxeo
documents.

Everything else follows from that rule:

- the endpoint can never create, rename, trash or delete a document;
- `DELETE` on the document's own file is refused with `403`;
- `MKCOL` is refused with `405`;
- there is nothing to browse, and nothing is ever enumerated.

## Requirements

| | |
|---|---|
| Nuxeo | LTS 2025, 2025.24 or later |
| Addons | None. `nuxeo-web-ui` for the button |
| Client | Microsoft Word, Excel or PowerPoint **desktop** application, licensed for editing, on Windows or macOS. See [Client requirements](#client-requirements) |
| Transport | **HTTPS strongly recommended**, see Platform notes below |

## Installation

```bash
nuxeoctl mp-install nuxeo-labs-webdav-directedit-package-<version>.zip
```

## Client requirements

Nothing has to be installed on the user's computer besides Microsoft Office: no Nuxeo Drive, no agent,
no browser extension, no Java, no mapped drive, and no administrator rights. A browser launches Office
from the button; a custom UI can launch it by other means, see
[Without Web UI (custom UI)](#without-web-ui-custom-ui).

The computer must still meet the following conditions.

1. **A licensed Office desktop application that can edit.**
   - Word, Excel or PowerPoint desktop: Microsoft 365 Apps, or a perpetual Office 2016 or later. Older
     versions are untested.
   - Office must be activated. An unlicensed Office runs in *reduced functionality mode*, which opens
     documents read-only.
   - Some Microsoft 365 plans, such as Business Basic, E1 or F3, include Office for the web and mobile
     only, with no desktop application. They cannot be used.
2. **Windows or macOS.** Linux and ChromeOS have no Office desktop application. Office for the web, iOS
   and Android are untested and not expected to work.
3. **Direct network access to Nuxeo.** Office connects to the address built from
   `nuxeo.labs.webdavDirectEdit.baseUrl`, else `nuxeo.url`, not to the UI the user clicked in. DNS, VPN
   and firewalls must allow it.
   - Office uses the operating system's proxy settings. A proxy configured only in a browser, such as
     Firefox, does not apply.
   - A corporate proxy must let the WebDAV methods through (`PROPFIND`, `LOCK`, `UNLOCK`, …), see
     [Deployment](#deployment).
4. **An HTTPS certificate the operating system trusts.** Office checks it against the Windows certificate
   store or the macOS Keychain.
   - Accepting a certificate warning in the browser does nothing for Office.
   - Firefox has its own certificate store, so a server that works in Firefox may still fail in Office.
   - A self-signed certificate, common on demonstration servers, must be added to the system store.
   - Behind a TLS-inspecting proxy, the corporate root certificate must be in the system store. It
     usually is on managed computers.
5. **A browser allowed to launch Office.** By default the browser asks for confirmation (*Open Microsoft
   Word?*). A browser policy can block external protocols altogether or, on the contrary, skip the prompt
   for the Nuxeo host (`AutoLaunchProtocolsFromOrigins` in Chrome and Edge).

### On Windows

- **Put the Nuxeo host in the *Trusted sites* or *Local intranet* zone.** This removes both the launch
  prompt and Protected View, see
  [The fix: put the Nuxeo host in a trusted zone](#the-fix-put-the-nuxeo-host-in-a-trusted-zone).
- **Macros.** Since 2022, Office blocks VBA macros in files that come from the Internet zone. A
  macro-enabled document (`.docm`, `.xlsm`, `.pptm`) served by a host in that zone is expected to open
  with its macros blocked, and the zone change above to lift that. Not verified.
- **Plain HTTP** works only while the address authenticates on its own. Once Office prompts for
  credentials, Windows refuses to send them over HTTP, see
  [Basic credentials over plain HTTP](#basic-credentials-over-plain-http). Use HTTPS in production;
  plain HTTP is fine for a demonstration, see
  [Demonstration machine over HTTP](#demonstration-machine-over-http).
- **The WebClient service is not involved** when the button is used: Office talks to the server with its
  own HTTP client. Opening the address with *File › Open* or from a mapped drive does go through
  WebClient, which by default also refuses to download files larger than about 50 MB
  (`FileSizeLimitInBytes`).

## When the button appears

The button is shown only when all of the following hold:

- the document has a file in `file:content`;
- that file's mime type **or** its file name extension matches a Microsoft Office format;
- the user has the `Write` permission;
- the document is not a proxy, a version, or trashed;
- the document is not locked by another user. The lock owner still sees the button.

Clicking then runs a server-side check that is authoritative, and reports a clear message when the
document cannot be opened.

## Configuration

All properties can be set in `nuxeo.conf`. Those prefixed with `org.nuxeo.web.ui.` are read by both the
browser and the server, so the two can never disagree about which files are Office documents.

| Property | Default | Purpose |
|---|---|---|
| `org.nuxeo.web.ui.webdavDirectEdit.enabled` | `true` | Turns the whole feature off |
| `org.nuxeo.web.ui.webdavDirectEdit.mainBlobXPath` | `file:content` | The blob the endpoint exposes |
| `org.nuxeo.web.ui.webdavDirectEdit.word.extensions` | `doc,docx,docm,dot,dotx,dotm,rtf` | |
| `org.nuxeo.web.ui.webdavDirectEdit.excel.extensions` | `xls,xlsx,xlsm,xlsb,xlt,xltx,xltm,csv` | |
| `org.nuxeo.web.ui.webdavDirectEdit.powerpoint.extensions` | `ppt,pptx,pptm,pps,ppsx,ppsm,pot,potx,potm` | |
| `org.nuxeo.web.ui.webdavDirectEdit.<app>.mimeTypes` | see `webdav-directedit-properties.xml` | |
| `org.nuxeo.web.ui.webdavDirectEdit.showCredentialsHelp` | `true` | Shows the sign-in help section in the dialog |
| `org.nuxeo.web.ui.webdavDirectEdit.warnOnInsecureUrl` | `true` | Warns when the address is not HTTPS |
| `nuxeo.labs.webdavDirectEdit.baseUrl` | *empty* | Forces the base URL of the WebDAV addresses. Server-side only |
| `nuxeo.labs.webdavDirectEdit.tokenAuth.enabled` | `true` | Accepts a Nuxeo token as the WebDAV password. Server-side only |
| `nuxeo.labs.webdavDirectEdit.capabilityUrl.enabled` | `true` | The URL authenticates the request, so Office never prompts. Server-side only |
| `nuxeo.labs.webdavDirectEdit.capabilityUrl.ttlMinutes` | `480` | How long the URL keeps **authenticating**, in idle minutes. Server-side only |
| `nuxeo.labs.webdavDirectEdit.session.ttlMinutes` | `43200` | **Absolute** lifetime of an address, from issue. Never refreshed. Server-side only |
| `nuxeo.labs.webdavDirectEdit.maxUploadSizeMB` | `512` | Largest accepted `PUT`, hence the largest editable document. Server-side only |
| `nuxeo.labs.webdavDirectEdit.lockOnOpen.enabled` | `true` | Locks the document on click and releases it when Office closes the file, see [Locking](#locking). Server-side only |
| `nuxeo.labs.webdavDirectEdit.lockOnOpen.openTimeoutMinutes` | `5` | How long the lock taken on click waits for Office to lock the file or save it. Server-side only |
| `nuxeo.labs.webdavDirectEdit.lockOnOpen.inactivityTimeoutMinutes` | `65` | How long a document open in Office stays locked with no request from Office. Never less than `61`. Server-side only |

`nuxeo.labs.webdavDirectEdit.baseUrl` is the one to set when Nuxeo is reached over plain HTTP but a
HTTPS entry point exists, since Office on Windows needs HTTPS. When it is empty, `nuxeo.url` is used,
unless it still points at localhost, in which case the address the browser is using is taken instead.

## Locking

With `nuxeo.labs.webdavDirectEdit.lockOnOpen.enabled=true`, the default, the document is locked from
the click until the file is closed in Office. This is what Nuxeo Drive Direct Edit does. The WOPI
integration for Office Online also holds a lock while the file is open, but takes it when Office Online
asks for it rather than on click.

| Event | What happens to the Nuxeo lock |
|---|---|
| The user clicks the button | **Locked** for that user, before Office even starts. The page shows the lock straight away |
| Office locks the file | Kept. From now on, the lock lives as long as Office keeps talking to the server |
| Office saves | Kept |
| The file is reopened from Office's recent files, with no click | **Locked** by Office's own `LOCK`, then handled the same way: released when the file is closed, or after the inactivity timeout |
| The user closes the file in Office, which sends `UNLOCK` | **Released** at once |
| Office never locks nor saves the file: launch prompt cancelled, Office not installed, file opened read-only | Released after `openTimeoutMinutes`, 5 by default |
| Office goes away without unlocking: crash, network loss | Released after about `inactivityTimeoutMinutes` with no request from Office, 65 by default |

The two timeouts are enforced by a scheduled job that runs **every minute**, so a release can come up
to one minute later. Each release writes one `INFO` line, see [Reading the log](#reading-the-log):

```
INFO  [OfficeEditLockTracker] Office edit: released the lock on document 1a2b3c4d-… held by jdoe, which Office never opened
```

**A lock the user set by hand is never released by the plugin.** If the user locked the document with
the Web UI Lock button *before* clicking *Open in Microsoft Office*, the document opens normally, and
stays locked after Office closes it: the lock is theirs. The same holds for a lock set by hand after
the plugin's own lock was removed. To tell the two apart, the plugin records every lock it takes, and
releases a lock only if it is still that very lock, same owner and same creation time.

**A document locked by somebody else** is not offered: the button is hidden. A custom UI calling
`WebDavDirectEdit.GetUrl` anyway gets `available: false`, `reason: "locked"` and the owner in
`lockOwner`.

The records live in the `officeEditLock` key/value store, next to the edit sessions. On a cluster the
key/value store must be shared, which it is with the standard SQL and MongoDB templates. Each record is
claimed atomically before its lock is released, so the job may run on every node.

With `lockOnOpen.enabled=false`, clicking does not lock: Office's own `LOCK` and `UNLOCK` drive the
lock, as before this feature, and a lock left behind by a crashed Office stays until someone removes it.
Two things do not depend on the switch:

- a document locked by another user is still refused: the button is hidden, and `GetUrl` answers
  `reason: "locked"`;
- locks the plugin recorded before the switch was turned off are still released when their deadline
  passes.

## Authentication, and single sign-on

**Microsoft Office cannot do SSO.** It drives WebDAV with its own HTTP stack: no browser, no way to
follow an identity provider redirect, and support for only the HTTP authentication schemes — Basic and
Digest. This is a Microsoft constraint and it applies to every WebDAV integration, not just this one.

The plugin deals with it in two layers.

### 1. The edit session token in the URL (default, no prompt)

The address the button builds carries an edit session token, and that token authenticates the request.
Office is never asked for credentials, so an SSO-only deployment works with no user interaction at all.

The token is put in the **path**, not in the query string, because Office builds the parent collection,
the temporary file names and the `Destination` header by manipulating the path. A query string would be
dropped on the second step of a save.

It is a deliberately narrow credential:

- it grants access to **one document**, for the user it was issued to, and nothing else;
- it is meaningless anywhere but `/nuxeo/site/officeedit`;
- it stops authenticating after `capabilityUrl.ttlMinutes` of inactivity, 8 hours by default.

**Two lifetimes, and the difference matters.** An address goes on *designating* its document
(`session.ttlMinutes`, 30 days) long after it has stopped *authenticating* (`capabilityUrl.ttlMinutes`,
8 hours of inactivity). So an address reopened from Office's recent files the next day asks for
credentials and then opens the document. Were there a single lifetime, entering a correct password
would still yield a 404 and the Basic fallback below would be useless.

The 30 days are an **absolute ceiling measured from the moment the address was issued**, deliberately
not extended by use. An issued address is a bearer credential that nothing else can revoke, so it must
expire on its own even if it is polled continuously.

> **What you are trading.** The URL is a bearer credential. Anyone holding it can read and write *that
> one document*, as that user, until its authentication window lapses. It is visible in Office's
> recent-files list, and wherever URLs are logged upstream — typically a reverse proxy; a stock Nuxeo
> writes no access log. There is **no way to revoke an individual address** short of waiting for it to
> expire, which is why the absolute ceiling exists. Set
> `nuxeo.labs.webdavDirectEdit.capabilityUrl.enabled=false` to turn the mechanism off entirely and
> require a credentials prompt instead.

The same approach is what the platform's own WOPI module uses for Office Online, for the same reason.

### 2. The credentials prompt (fallback)

If the session has expired, or capability URLs are disabled, Office falls back to a normal Basic
challenge. The server answers `401` with `WWW-Authenticate` rather than redirecting to the login page —
the scoped authentication chain removes the OIDC and SAML plugins entirely for these URLs, so Office can
never be sent to an identity provider it cannot use.

At the prompt the user can enter:

- **their Nuxeo authentication token**, shown in the dialog's *Trouble signing in?* section. This is what
  makes the fallback usable under SSO, where the user has no password. Token lookup is confined to this
  endpoint, and a secret is only ever looked up as a token if it has the shape of one, so a real password
  is never handed to the token service.
- **their Nuxeo password**, for deployments with local accounts.

> **Security note.** The token in the *Trouble signing in?* section is a **full-account credential**:
> Nuxeo records the `rw` permission on a token but never enforces it as a scope. It is shown only when the
> user explicitly asks for it, it is cleared from the page when the dialog closes, and it can be revoked
> from the same dialog. The edit session token in the URL is strictly narrower and is the normal path.

### 3. Kerberos or NTLM at the reverse proxy

In an Active Directory environment, a reverse proxy configured for Negotiate authentication lets Windows
sign Office in transparently. This is a deployment-level setup, outside the scope of this plugin.

## Platform notes

### Windows

Two things bite on Windows, and it is worth separating what the plugin controls from what it does not.

#### Basic credentials over plain HTTP

The `WebClient` service refuses to **send** Basic credentials over an unencrypted connection by
default:

```
HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters\BasicAuthLevel
    0 = Basic authentication disabled
    1 = Basic authentication enabled for SSL shares only   (default)
    2 = Basic authentication enabled for SSL and non-SSL shares
```

**Use HTTPS.** Changing the registry on every workstation is not a reasonable alternative. Note that
while the address still authenticates on its own, no credentials are sent at all and this does not
apply — it only bites once Office has to prompt. The dialog warns when the address is not HTTPS.

#### The two security prompts, and their single cause

Users on Windows see two warnings that do not appear on macOS. **Neither can be suppressed from the
server; no HTTP header influences either.** Both come from the same place: the Nuxeo host sits in the
**Internet zone**.

**1. When clicking the button**, Windows asks for confirmation before launching Word:

> Some files contain viruses that can be harmful to your computer… Do you want to open this file?
> Program: Microsoft Word

This is the shell warning for launching an external application from a browser. It is governed by the
zone setting *Launching applications and unsafe files*, which defaults to **Prompt** in the Internet
zone and to **Enable** in Trusted sites.

**2. Once Word opens**, a banner appears:

> PROTECTED VIEW — Be careful, files from the Internet can contain viruses…

Microsoft's own documentation gives this exact wording and its cause: *the file is being opened from
the Internet*.

#### The fix: put the Nuxeo host in a trusted zone

One change addresses both. On a managed estate, use the Group Policy **Site to Zone Assignment List**
to map the Nuxeo host to *Trusted sites* (zone 2) or *Local intranet* (zone 1).

To verify or set it on a single machine, through the interface rather than the registry:

- *Internet Options › Security › Trusted sites › Sites* — add `https://your-nuxeo-host`;
- the launch prompt is *Internet Options › Security › Trusted sites › Custom level › Launching
  applications and unsafe files*;
- Protected View can also be turned off independently, per application, in *Word › File › Options ›
  Trust Center › Trust Center Settings › Protected View*, by clearing **Enable Protected View for
  files originating from the Internet**. Changing the zone is preferable: it is narrower, and it fixes
  the launch prompt too.

#### What Protected View costs you, until it is turned off

Word locks the document, reads it, then **releases the lock** and waits for the user to click *Enable
Editing*. Between 10 and 45 seconds elapsed in the sessions measured — human reaction time.

**During that pause the document is unlocked in Nuxeo**, so another user can take it. Nothing in the
plugin can prevent this: it is the client that releases the lock.

**This includes the lock taken on click.** Office's `UNLOCK` is the only sign the plugin gets that a
file was closed, and Word sends the very same `UNLOCK` when it enters Protected View. The plugin cannot
tell the two apart, so it releases the lock at once, as it does when the file is really closed; Word
locks the document again when the user clicks *Enable Editing*. Waiting a little before releasing would
bridge the gap, at the cost of keeping every closed document locked for that long; it was deliberately
not done. On Windows, the sequence is therefore:

| Step | Nuxeo lock |
|---|---|
| Click on the button | locked |
| Word locks the file, reads it, opens it in Protected View, then sends `UNLOCK` | **unlocked** |
| The user clicks *Enable Editing*, and Word sends `LOCK` | locked again |
| The user closes the file | unlocked |

Putting the Nuxeo host in a trusted zone, see above, removes Protected View, hence the gap.

Office normally remembers a document after the first *Enable Editing* and stops prompting for it — the
*Trusted Documents* mechanism, which is keyed on the address. **A new address is issued on every click
of the button**, so that memory is currently defeated. See the note on the recent-files list under
Limitations.

### macOS

macOS supports WebDAV natively and Office for Mac registers the `ms-word:`, `ms-excel:` and
`ms-powerpoint:` URL schemes. HTTPS is recommended here too.

### URL length

The Office URI scheme caps the address at **256 characters, and at 216 for Excel**. The address this
plugin builds is `<base URL>/site/officeedit/<36-character token>/<file name>`, which leaves ample room
in practice, but a very long host name combined with a very long file name could reach the limit. The
session token replaces the document id rather than being added to it, so carrying the credential in the
URL costs nothing against this budget.

## Demonstrations and local testing

Both authentication paths are supported, and which one you get depends on whether the address is still
within its authentication window. On a demonstration machine the thing to avoid is being interrupted by
a credentials prompt you cannot answer.

### What works where

The only awkward case comes from Windows, not from this plugin. The `WebClient` service refuses to
**send** Basic credentials over an unencrypted connection when `BasicAuthLevel` is 1, its default.

| | HTTP | HTTPS |
|---|---|---|
| **macOS** — address still authenticates, no prompt | works | works |
| **macOS** — prompt, password or token | works | works |
| **Windows** — address still authenticates, no prompt | works | works |
| **Windows** — prompt, password or token | **blocked** by `BasicAuthLevel` | works |

Since no credentials are sent while the address authenticates, plain HTTP is fine on Windows — right up
until the window lapses. Hence the longer window below.

### Demonstration machine over HTTP

Paste into `nuxeo.conf`:

```properties
# --- Nuxeo Labs WebDAV Direct Edit: demo machine over plain HTTP ---
# Authentication window raised to a week, so no prompt interrupts a demo
nuxeo.labs.webdavDirectEdit.capabilityUrl.ttlMinutes=10080
# The HTTPS warning is expected locally
org.nuxeo.web.ui.webdavDirectEdit.warnOnInsecureUrl=false
```

### Exercising the Basic path on purpose

```properties
# The URL only designates the document: Office asks for credentials
nuxeo.labs.webdavDirectEdit.capabilityUrl.enabled=false
```

Sign in with a Nuxeo user name and either their password, or the token from the dialog's
*Trouble signing in?* section. On Windows this requires HTTPS, or `BasicAuthLevel` set to 2 in
`HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters`.

Note that an address is personal: opening one issued to another user, while signed in as yourself,
returns `403` rather than the document.

### Reading the log

The plugin writes one `INFO` line per save:

```
INFO  [OfficeEditServlet] Office edit: updated main blob of document 1a2b3c4d-… (Report.docx, 24576 bytes)
```

and one per lock released because Office never opened the file or went silent, see [Locking](#locking).
A lock released by Office's own `UNLOCK` gets no line of its own: at `DEBUG`, it shows only as the
`UNLOCK` request line.

**It is not visible with the default logging configuration.** Nuxeo's stock `log4j2.xml` logs at `WARN`
by default, and the plugin's logger, `nuxeo.labs.webdav.directedit`, has no entry of its own. To see
it, add one line inside `<Loggers>` in `$NUXEO_HOME/lib/log4j2.xml`; it covers every class in the
plugin, and nothing else:

```xml
<Logger name="nuxeo.labs.webdav.directedit" level="info" />
```

The file is declared with `monitorInterval="30"`, so the change is picked up within 30 seconds, without
a restart. Where `log4j2.xml` comes from a configuration template, as in the Docker image, make the
change in that template: a regenerated configuration would otherwise overwrite it.

For anything finer, use `level="debug"` instead. You then get one line per HTTP request, with the
method and the path, plus a line per authentication. Session tokens are masked everywhere, so a DEBUG
log is safe to paste into a ticket.

Two things are worth watching:

- `WARN  Unsupported WebDAV method ... on ...` — a verb this endpoint does not implement, which is the
  most likely cause if Office behaves oddly;
- in a reverse proxy access log, any request Office makes **outside** `/nuxeo/site/officeedit/`. The
  servlet only answers under that path.

Note that a stock Nuxeo enables no Tomcat access log, so the address, and therefore the session token,
does not reach any Nuxeo log file. It will appear wherever URLs are logged upstream — typically a
reverse proxy.

## Validation status

Honest reporting of what has actually been exercised against real Office clients, as opposed to the
automated test suite. Word was measured on 22 September 2026, through the `ms-word:` protocol handler —
that is, by clicking the button. Windows was measured over plain HTTP on a local server, macOS over
**HTTPS on a hosted instance**. Excel and PowerPoint have their own section below.

The two Word clients do not behave identically, so they are reported in separate columns.

| | macOS, Word | Windows, Word |
|---|---|---|
| Open, edit, save, close | works | works |
| Credentials prompt | **none** | **none** |
| How it saves | direct `PUT` | direct `PUT` |
| Temporary files created | none | none |
| `PROPPATCH` sent | no | no |
| Requests to open | 8 | 11, in two phases |
| `LOCK` per session | 1 | 3 |
| `GET` per session | 1 | 2 |
| Protected View pause | no | **yes**, 10 to 45 s |
| Transport | HTTPS, hosted | plain HTTP, local |

Verbs actually seen, on both: `OPTIONS`, `HEAD`, `GET`, `LOCK`, `UNLOCK`, `PROPFIND`, `PUT`.
Never seen, on either: `PROPPATCH`, `MOVE`, `COPY`, `DELETE`, `MKCOL`.

Measured once, on macOS:

- a second user holding write permission **cannot modify** the document while it is open;
- **one version per editing session**, not per save;
- a file name containing spaces round-trips correctly;
- **reopening from Word's recent-files list works**: the address survives closing Word, and the
  document is locked again correctly. This is what the split between the two lifetimes is for;
- the upload cap, the scratch permission check, the absolute session ceiling and the base URL taken
  from the request — the whole security hardening — ran against a real client without regression.

### Excel and PowerPoint

On 16 November 2026, a Word (`.docx`), an Excel (`.xlsx`) and a PowerPoint (`.pptx`) document were each
opened with the button, edited and saved back to Nuxeo successfully. That run did not trace the
requests, so how Excel and PowerPoint save, with a direct `PUT` or with the transacted save described
below, is not known yet.

### Locking

On 17 November 2026, the lock lifecycle described in [Locking](#locking) was checked against a real
Office client, through the button:

- the click locks the document, and the page shows the lock at once;
- closing the file in Office unlocks it;
- cancelling the launch prompt leaves the document locked, then releases it 5 to 6 minutes later;
- a lock set by hand before opening the file is still there after Office closes it;
- another user does not see the button while the document is locked.

### Not yet verified

- **the lock taken on click through Windows Protected View**: the sequence in
  [What Protected View costs you](#what-protected-view-costs-you-until-it-is-turned-off) follows from
  the code and from the trace of 22 September, but was not observed with locking on click;
- **whether Office refreshes its WebDAV lock** while a document stays open. If it does not, and sends no
  other request either, a document left open and idle for more than `inactivityTimeoutMinutes` is
  unlocked in Nuxeo while still open in Office; saving still works, as long as nobody else locked it
  meanwhile. With the plugin's logger at `DEBUG`, a refresh shows as `Office edit: lock refresh on ...`;
- **opening the address with File then Open**, or from a mapped drive, rather than with the button.
  On Windows that route goes through the WebClient service, which is likely to produce a different
  request sequence — see below. The dialog advertises this route, so it matters;
- the request sequence Excel and PowerPoint send, and the 216-character URI limit specific to Excel,
  with a long file name;
- older Office versions;
- the credentials-prompt fallback against a real Office client, once the authentication window has
  lapsed — covered by automated tests only;
- whether reusing one address per document would let Office stop showing Protected View, through its
  Trusted Documents mechanism;
- a large document.

### About the transacted save

Word is documented, and widely reported, to save by writing a temporary file, renaming the original
aside, renaming the temporary into place and deleting the leftover. The plugin implements that
sequence, and the automated test suite exercises it.

**Neither Word client measured here does it.** Both send a plain `PUT`. That does not make the code
dead: the protocol handler is only one way in, and the routes listed above as unverified are precisely
the ones most likely to use it. **Unobserved is not unreachable.**

Rather than keep guessing, the plugin reports it. The first time any client takes that path, on any
deployment, one line appears in the log, provided the plugin's logger is at `INFO`, see
[Reading the log](#reading-the-log):

```
INFO  Office edit: a client used the transacted save path (MOVE on Report.docx). ...
```

If you ever see it, the sequence is in use somewhere, and the answer is settled.

## Limitations

- **Main file only.** The endpoint reads and writes only the blob returned by the document's
  `BlobHolder`, that is `file:content` for File, Picture, Video and Audio. Attachments stored in
  `files:files` are not exposed, and the plugin refuses them rather than let Office edit one file and
  overwrite another. This is why the button is a document action and not a blob action.
- **Maximum document size.** A single `PUT` is capped by `maxUploadSizeMB`, 512 MB by default, which is
  therefore also the largest document editable through this endpoint.
- **An issued address cannot be revoked individually.** It stops authenticating after its idle window
  and dies at the absolute ceiling, but there is no "revoke this address" action.
- **A new address is issued on every click of the button**, even for the same document and the same
  user. Two consequences. Office's recent-files list accumulates one entry per click, of which only
  the most recent will still work once the others expire — reopening from that list does work, it was
  measured, but older entries eventually go stale. And on Windows it defeats Office's *Trusted
  Documents* memory, which is keyed on the address, so the Protected View banner returns on every
  open instead of only the first. Reusing a single address per document and user would address both;
  it is not implemented yet.
- **On Windows the document is briefly unlocked** while Protected View waits for *Enable Editing*, the
  lock taken on click included, see
  [What Protected View costs you](#what-protected-view-costs-you-until-it-is-turned-off).
- **Full download and full upload.** WebDAV has no partial write: every save uploads the entire file.
  This is a property of the protocol, not of this plugin. The server side streams in both directions
  and supports HTTP `Range`, so nothing is ever held in memory.
- **One version per editing session.** Nuxeo's versioning policy creates a version on the first save
  after the document is opened, not on every save, so frequent saving does not pile up versions.
- **Stale locks.** If Office crashes, the lock the plugin took is released after
  `inactivityTimeoutMinutes`, 65 by default, see [Locking](#locking). Until then, or for good when
  `lockOnOpen.enabled` is `false`, it can be cleared with the standard Lock toggle button in the Web UI.
- **Closing a document locked by hand does not unlock it.** That is on purpose, see
  [Locking](#locking), but it differs from Nuxeo Drive and from the WOPI integration, which both release
  a lock their user had set by hand.
- **Clicking locks.** Clicking the button and then not opening the file, or cancelling the launch
  prompt, keeps the document locked for `openTimeoutMinutes`, 5 by default.
- **Single repository.** Documents are resolved in the default repository.
- **Addresses expire.** `session.ttlMinutes` after they were issued, 30 days by default, an address kept
  in Office's recent files stops working entirely and the user clicks the button again. This ceiling is
  absolute and is deliberately not extended by use. Between the authentication window and that limit,
  the address still works but asks for credentials.
- **Do not install the `nuxeo-webdav` addon alongside this plugin.** It contributes an authentication
  chain selected on the `User-Agent` header with no URL pattern at all, matching exactly the user agents
  Word and Excel send. It can take precedence over this plugin's own chain and break its authentication.
  The plugin logs a warning at startup when it detects the addon.

## Automation operations

| Operation | Input | Output | Purpose |
|---|---|---|---|
| `WebDavDirectEdit.GetUrl` | Document | JSON Blob | `{available, app, fileName, davPath, davUrl, protocolUrl, secure, reason, lockOwner}`. Also locks the document unless `lockOnOpen.enabled` is `false`, see [Locking](#locking) |
| `WebDavDirectEdit.GetCredentials` | void | JSON Blob | `{username, token, enabled}` |
| `WebDavDirectEdit.RevokeCredentials` | void | void | Revokes the caller's own token |

When `available` is `false`, `reason` is one of `disabled`, `noBlob`, `notMainBlob`, `notOfficeFile`,
`notEditable`, `noWritePermission`, `locked`. With `locked`, `lockOwner` is the user holding the lock;
it is `null` otherwise.

## Without Web UI (custom UI)

Web UI is optional. The WebDAV endpoint, its authentication chain and the Automation operations are
server-side and work the same behind any UI. The Web UI button is only a client of
`WebDavDirectEdit.GetUrl`, and a custom UI does the same in three steps.

**1. Decide whether to show the action.** Mirror the rules of
[When the button appears](#when-the-button-appears): an Office file by mime type or extension, per the
`org.nuxeo.web.ui.webdavDirectEdit.<app>.*` properties; the `Write` permission; not a proxy, a version
or trashed; not locked by another user (`lockOwner`, with the `fetch-document: lock` header). No
endpoint exposes those lists, so copy them from the configuration. The server re-checks everything
anyway.

**2. On click, and only on click, ask for the address**, authenticated as the end user:

```bash
curl -X POST "https://nuxeo.example.com/nuxeo/api/v1/automation/WebDavDirectEdit.GetUrl" \
  -u "$NUXEO_USER:$NUXEO_PASSWORD" \
  -H "Content-Type: application/json" \
  -d '{"input": "doc:<document-uid>", "params": {}}'
```

```json
{"available": true, "app": "word", "fileName": "Report.docx",
 "davPath": "<token>/Report.docx",
 "davUrl": "https://nuxeo.example.com/nuxeo/site/officeedit/<token>/Report.docx",
 "protocolUrl": "ms-word:ofe|u|https://nuxeo.example.com/nuxeo/site/officeedit/<token>/Report.docx",
 "secure": true, "reason": null, "lockOwner": null}
```

When `available` is `false`, show a message based on `reason`, see [Automation operations](#automation-operations).

Every successful call issues a new address, valid for up to 30 days, that cannot be revoked. Unless
`lockOnOpen.enabled` is `false`, it also **locks the document** for the caller until Office closes the
file, or for `openTimeoutMinutes` if Office never locks nor saves it, see [Locking](#locking). Do not
call it to find out whether a document *can* be opened. Treat `davUrl` and `protocolUrl` as
credentials: never log them, store them or send them to analytics. Refresh the document afterwards if
the UI shows its lock state.

**3. Launch `protocolUrl`.** In a browser, click a detached anchor (`a.href = protocolUrl; a.click()`)
rather than assigning `window.location`, which raises a navigation error when Office is not installed.
Also render it as a real link the user can click as a fallback. A desktop application hands the URI to
the operating system.

**Testing from a terminal.** To check the server side before any UI exists, open `protocolUrl` from a
shell. Quote it: an unquoted `|` is a pipe, so the shell runs `ms-word:ofe` on its own and then fails
with `u: command not found`, and Office never gets the address.

```bash
# macOS
open 'ms-word:ofe|u|https://nuxeo.example.com/nuxeo/site/officeedit/<token>/Report.docx'
```

```bat
:: Windows, cmd.exe (the empty "" is the window title start expects)
start "" "ms-word:ofe|u|https://nuxeo.example.com/nuxeo/site/officeedit/<token>/Report.docx"
```

```powershell
# Windows, PowerShell
Start-Process 'ms-word:ofe|u|https://nuxeo.example.com/nuxeo/site/officeedit/<token>/Report.docx'
```

The command line ends up in the shell history with the address in it: test with a throwaway document
or user rather than a real account.

### Authentication

There are two separate legs:

- **Custom UI to the Nuxeo REST API** (the `GetUrl` call): whatever the UI already uses, such as an
  OAuth2/OIDC bearer token, a Nuxeo token, Basic or a session cookie. It must carry the **end user's**
  identity: the edit session is issued to the caller, so a backend calling Nuxeo with a service account
  would make Office edit as that account.
- **Office to `/nuxeo/site/officeedit`**: nothing to do. The token in the address authenticates Office,
  then the Basic fallback applies, as described in
  [Authentication, and single sign-on](#authentication-and-single-sign-on). On SSO-only deployments,
  expose `WebDavDirectEdit.GetCredentials` and `WebDavDirectEdit.RevokeCredentials` the way the Web UI
  dialog does, so users have something to type at the prompt.

### Deployment

- Office talks to Nuxeo **directly**, not through the custom UI. The address is built from
  `nuxeo.labs.webdavDirectEdit.baseUrl`, else `nuxeo.url`, so that URL must be reachable from the
  users' desktops, over HTTPS.
- Any gateway, reverse proxy or WAF in front of Nuxeo must pass `/nuxeo/site/officeedit/*` through
  unchanged and without adding its own authentication, including the WebDAV methods (`OPTIONS`,
  `PROPFIND`, `PROPPATCH`, `LOCK`, `UNLOCK`, `PUT`, `MOVE`, `COPY`, `DELETE`, `MKCOL`) and headers
  (`Depth`, `Destination`, `Overwrite`, `If`, `Lock-Token`, `Timeout`, `Authorization`).
- A custom UI served from another origin needs a Nuxeo `corsConfig` contribution for `/nuxeo/api/.*`.
  The WebDAV endpoint needs none: Office is not a browser.

## Build


### Local Build
After cloning the repo:

```bash
cd /path/to/nuxeo-labs-webdav-directedit
mvn clean install
```

The marketplace package is produced at
`nuxeo-labs-webdav-directedit-package/target/nuxeo-labs-webdav-directedit-package-<version>.zip`.

### Nuxeo marketplace

The package is available in Nuxeo Marketplace. So, for example, you can just add `nuxeo-labs-webdav-directedit` to your Sutdio dependencies, or in the `NUXEO_PACKAGES` variable of a Docker deployment.

## Support

These features are not part of the Nuxeo Production platform. They are not supported, and are provided
without warranty of any kind. Use them at your own risk.

## License

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)

## About Nuxeo

[Nuxeo](https://www.hyland.com/en/products/nuxeo-platform), developed by [Hyland](https://www.hyland.com),
is a Content Services platform. It provides an easy-to-use, highly customizable and extensible
architecture for building content-centric applications.

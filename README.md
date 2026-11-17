# Nuxeo Labs WebDAV Direct Edit

Adds an *Open in Microsoft Office* button to the Nuxeo Web UI. One click opens the current document
in Word, Excel or PowerPoint on the user's desktop. When the user saves from Office, the changes go
straight back to Nuxeo and update the document.

> [!IMPORTANT]
> This is a **Nuxeo Presales Sandbox plugin**: it is not part of the Nuxeo Platform and it is **not
> supported**. If you need the feature, **we strongly recommend that a Nuxeo Partner forks the code
> and takes ownership** (tuning, maintenance, support).

## Highlights

- **Nothing to install on the user's computer** besides Microsoft Office: no Nuxeo Drive, no agent, no
  browser extension, no administrator rights. Works on Windows and macOS.
- **Works with single sign-on.** The address given to Office carries its own time-limited credential,
  valid for one document only, so Office does not ask the user to sign in.
- **The document is locked while it is open**: locked on click, unlocked when the file is closed in
  Office, as with Nuxeo Drive Direct Edit.
- **Any document**, wherever it is in the repository, as long as its main file is an Office file.
- **Web UI is optional**: a custom UI can use the same endpoint and Automation operations.
- **Self-contained**: the plugin has its own WebDAV endpoint and does not need the `nuxeo-webdav` addon.

Developers and administrators will find every detail in [TECHNICAL.md](TECHNICAL.md).

## Requirements

| | |
|---|---|
| Nuxeo | LTS 2025, 2025.24 or later |
| Addons | None. `nuxeo-web-ui` for the button |
| Client | Microsoft Word, Excel or PowerPoint **desktop** application, licensed for editing, on Windows or macOS |
| Transport | **HTTPS strongly recommended**, see the [Production checklist](#production-checklist) |

The complete list of client-side conditions (network, certificates, browser policies) is in
[Client requirements](TECHNICAL.md#client-requirements).

## Installation

The package is available on the Nuxeo Marketplace: add `nuxeo-labs-webdav-directedit` to the
dependencies of your Studio project, or to the `NUXEO_PACKAGES` variable of a Docker deployment.

From a local [build](#build):

```bash
nuxeoctl mp-install nuxeo-labs-webdav-directedit-package-<version>.zip
```

> [!WARNING]
> Do not install the `nuxeo-webdav` addon on the same server. It can take over the requests sent by
> Office and break this plugin's authentication. The plugin logs a warning at startup when it detects
> it.

## Production checklist

Office connects to Nuxeo **directly**, with its own network stack, not through the browser. Before
opening the feature to users:

1. **Serve Nuxeo over HTTPS, with a certificate the operating system trusts.** Office checks it against
   the Windows certificate store or the macOS Keychain: accepting a warning in the browser does not help.
   See [Client requirements](TECHNICAL.md#client-requirements).
2. **Make the Nuxeo address reachable from the users' desktops.** Office connects to
   `nuxeo.labs.webdavDirectEdit.baseUrl`, else `nuxeo.url`, through the operating system's proxy
   settings. See [How the base URL is chosen](TECHNICAL.md#how-the-base-url-is-chosen).
3. **Let WebDAV through.** Any reverse proxy, gateway or WAF must pass `/nuxeo/site/officeedit/*`
   unchanged, with all WebDAV methods and headers, and without adding its own authentication. See
   [Deployment](TECHNICAL.md#deployment).
4. **On Windows, put the Nuxeo host in the *Trusted sites* or *Local intranet* zone**, typically by
   Group Policy. This removes the launch prompt and Protected View. See
   [The fix: put the Nuxeo host in a trusted zone](TECHNICAL.md#the-fix-put-the-nuxeo-host-in-a-trusted-zone).

## Trying it on a demonstration machine

Plain HTTP is fine for a demonstration. Paste into `nuxeo.conf`:

```properties
# --- Nuxeo Labs WebDAV Direct Edit: demo machine over plain HTTP ---
# Authentication window raised to a week, so no prompt interrupts a demo
nuxeo.labs.webdavDirectEdit.capabilityUrl.ttlMinutes=10080
# The HTTPS warning is expected locally
org.nuxeo.web.ui.webdavDirectEdit.warnOnInsecureUrl=false
```

Why this works, and what to expect on Windows: see
[Demonstrations and local testing](TECHNICAL.md#demonstrations-and-local-testing).

## What the user sees

### When the button appears

*Open in Microsoft Office* is shown in the document actions only when all of the following hold:

- the document has a file in `file:content`;
- that file's mime type **or** its file name extension matches a Microsoft Office format;
- the user has the `Write` permission;
- the document is not a proxy, a version, or trashed;
- the document is not locked by another user. The lock owner still sees the button.

Clicking then runs a server-side check that is authoritative, and reports a clear message when the
document cannot be opened.

### Opening, editing, saving

1. The user clicks the button. The document is **locked** in Nuxeo for them, and a dialog opens.
2. The browser asks for confirmation (*Open Microsoft Word?*), then Office opens the document. If
   nothing happens, the dialog has a link to open it manually.
3. The user edits and saves as usual. Each save **updates the Nuxeo document**. Nuxeo creates one
   version per editing session, not one per save.
4. The user closes the file in Office: the document is **unlocked**.

If Office never opens the file (prompt cancelled, Office not installed), the lock is released after
5 minutes. If Office crashes, after about 65 minutes. A lock the user had set by hand before clicking
is never released by the plugin. See [Locking](TECHNICAL.md#locking).

### Signing in

Normally, never: the address carries its own credential. It stops authenticating after 8 hours of
inactivity, for example when the file is reopened from Office's recent files the next day. Office
then asks for credentials, and the user enters their Nuxeo user name and, as the password:

- their Nuxeo password, or
- under SSO, the token shown in the dialog's *Trouble signing in?* section. That token gives access to
  the whole Nuxeo account: it is shown only on request, and can be revoked from the same dialog.

Clicking the button again also works, since it issues a new address. An address stops working
altogether 30 days after it was issued. See
[Authentication and single sign-on](TECHNICAL.md#authentication-and-single-sign-on).

### On Windows

Unless the Nuxeo host is in a trusted zone ([checklist](#production-checklist), item 4), Windows users
see two warnings that macOS users do not: a confirmation before Word launches, and the *Protected View*
banner once it opens. While Protected View waits for *Enable Editing*, the document is briefly unlocked
in Nuxeo. See [Windows](TECHNICAL.md#windows).

## Main settings

Set in `nuxeo.conf`. The full list is in [Configuration](TECHNICAL.md#configuration).

| Property | Default | Purpose |
|---|---|---|
| `org.nuxeo.web.ui.webdavDirectEdit.enabled` | `true` | Turns the whole feature off |
| `nuxeo.labs.webdavDirectEdit.baseUrl` | *empty* | Forces the base URL Office connects to, typically an HTTPS entry point |
| `nuxeo.labs.webdavDirectEdit.capabilityUrl.ttlMinutes` | `480` | How long an address keeps signing Office in, in idle minutes |
| `nuxeo.labs.webdavDirectEdit.capabilityUrl.enabled` | `true` | Set to `false` to always require a credentials prompt |
| `nuxeo.labs.webdavDirectEdit.lockOnOpen.enabled` | `true` | Locks the document on click and releases it when Office closes the file |
| `nuxeo.labs.webdavDirectEdit.maxUploadSizeMB` | `512` | Largest accepted save, hence the largest editable document |
| `org.nuxeo.web.ui.webdavDirectEdit.warnOnInsecureUrl` | `true` | Warns in the dialog when the address is not HTTPS |

## Limitations

- **Main file only.** Attachments (`files:files`) cannot be opened this way.
- **512 MB per document** by default (`maxUploadSizeMB`). Every save uploads the whole file.
- **Single repository.** Documents are resolved in the default repository.
- **Addresses cannot be revoked one by one.** An address is a credential for one document: it stops
  authenticating after 8 hours of inactivity and stops working 30 days after it was issued.
- **A new address on every click.** Office's recent-files list grows by one entry per click, and on
  Windows Protected View comes back on every open.
- **On Windows, the document is briefly unlocked** while Protected View waits for *Enable Editing*.
- **Clicking locks**, even if the file is then not opened: for 5 minutes by default.
- **A lock set by hand is never released by the plugin**, on purpose. Nuxeo Drive and WOPI do release it.
- **Not compatible with the `nuxeo-webdav` addon** on the same server.

Details: [Limitations](TECHNICAL.md#limitations).

## Troubleshooting

To see what the plugin does, turn its log on first, see [Reading the log](TECHNICAL.md#reading-the-log).

| Symptom | Likely cause | What to do |
|---|---|---|
| The button does not appear | One of the [conditions](#when-the-button-appears) is not met, or the feature is disabled (`org.nuxeo.web.ui.webdavDirectEdit.enabled`) | Check the conditions. For a file type that is not detected, add its extension or mime type, see [Configuration](TECHNICAL.md#configuration) |
| Nothing happens after the click | The browser blocked the launch, or Office is not installed | Use the *Nothing happened? Open in …* link in the dialog. Check browser policies, see [Client requirements](TECHNICAL.md#client-requirements) |
| Office cannot reach the server, or reports a certificate error | Office uses the operating system's network settings and certificate store, not the browser's | Check DNS, VPN, firewall, the system proxy and the system certificate store, see [Client requirements](TECHNICAL.md#client-requirements) and [How the base URL is chosen](TECHNICAL.md#how-the-base-url-is-chosen) |
| Word opens the document read-only | On Windows, Protected View. Otherwise, a proxy or WAF blocking WebDAV methods such as `LOCK` | Click *Enable Editing*, or put the host in a [trusted zone](TECHNICAL.md#the-fix-put-the-nuxeo-host-in-a-trusted-zone). Check the proxy, see [Deployment](TECHNICAL.md#deployment) |
| On Windows, a virus warning before Word launches, and Protected View on every open | The Nuxeo host is in the Internet zone, and each click issues a new address | Put the host in a [trusted zone](TECHNICAL.md#the-fix-put-the-nuxeo-host-in-a-trusted-zone) |
| Office asks for a user name and password | The address was not used for 8 hours (reopened from recent files), or `capabilityUrl.enabled` is `false` | Enter the user name and the password, or the token from *Trouble signing in?*. Or click the button again |
| On Windows, the credentials prompt keeps failing | Nuxeo is served over plain HTTP: Windows does not send credentials over HTTP | Use HTTPS, see [Basic credentials over plain HTTP](TECHNICAL.md#basic-credentials-over-plain-http) |
| `403` when opening an address | The address was issued to another user | Click the button yourself: addresses are personal |
| An address from Office's recent files no longer works | It was issued more than 30 days ago | Click the button again |
| The document stays locked, but Office never opened it | The click locked it | It is released after 5 minutes, or unlock it with the Web UI Lock button |
| The document stays locked after closing Office | It was locked by hand before the click (kept on purpose), Office crashed (released after about 65 minutes), or `lockOnOpen.enabled` is `false` | Unlock it with the Web UI Lock button, see [Locking](TECHNICAL.md#locking) |
| The document is unlocked while still open in Office | On Windows, Protected View before *Enable Editing* | Put the host in a [trusted zone](TECHNICAL.md#the-fix-put-the-nuxeo-host-in-a-trusted-zone) |
| A large file cannot be saved | It exceeds `maxUploadSizeMB`, 512 MB by default | Raise `nuxeo.labs.webdavDirectEdit.maxUploadSizeMB` |
| Office behaves oddly | A WebDAV method not supported, or altered by a proxy | Set the log to `debug`, look for `Unsupported WebDAV method`, see [Reading the log](TECHNICAL.md#reading-the-log) |
| Authentication fails, with a startup warning about `nuxeo-webdav` | The `nuxeo-webdav` addon is installed | Uninstall it |

## Going further

[TECHNICAL.md](TECHNICAL.md) covers:

- [How it works](TECHNICAL.md#how-it-works): the WebDAV endpoint, its URL layout and the request flow;
- [Configuration](TECHNICAL.md#configuration): every property;
- [Locking](TECHNICAL.md#locking): the full lock lifecycle, locks set by hand, clusters;
- [Authentication and single sign-on](TECHNICAL.md#authentication-and-single-sign-on): the credential in
  the address, its two lifetimes, the credentials prompt;
- [Client requirements](TECHNICAL.md#client-requirements), [Windows](TECHNICAL.md#windows),
  [macOS](TECHNICAL.md#macos) and [Deployment](TECHNICAL.md#deployment);
- [Demonstrations and local testing](TECHNICAL.md#demonstrations-and-local-testing) and
  [Reading the log](TECHNICAL.md#reading-the-log);
- [Automation operations](TECHNICAL.md#automation-operations) and
  [Without Web UI (custom UI)](TECHNICAL.md#without-web-ui-custom-ui): integrating with a custom UI;
- [Validation status](TECHNICAL.md#validation-status): what was tested with real Office clients, and
  what was not.

## Build

```bash
cd /path/to/nuxeo-labs-webdav-directedit
mvn clean install
```

The marketplace package is produced at
`nuxeo-labs-webdav-directedit-package/target/nuxeo-labs-webdav-directedit-package-<version>.zip`.

## Support

These features are not part of the Nuxeo Production platform. They are not supported, and are provided
without warranty of any kind. Use them at your own risk.

## License

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)

## About Nuxeo

[Nuxeo](https://www.hyland.com/en/products/nuxeo-platform), developed by [Hyland](https://www.hyland.com),
is a Content Services platform. It provides an easy-to-use, highly customizable and extensible
architecture for building content-centric applications.

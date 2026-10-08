# Data Saver

**See which apps spend your mobile data in the background — and stop them.**

An Android data manager that measures per-app usage and lets you restrict apps in the same
place. No ads. No accounts. No servers.

> **This app has no `INTERNET` permission.** Check
> [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml) — it isn't declared,
> so the app is incapable of sending anything anywhere. Everything it shows comes from
> counters Android already keeps on your device.

---

## Install

| | |
|---|---|
| **Google Play** | [play.google.com/store/apps/details?id=com.anubisproductions.datagate](https://play.google.com/store/apps/details?id=com.anubisproductions.datagate) |
| **Direct APK** | [Releases](https://github.com/anubisproductions/data-saver-usage-blocker/releases) — signed, with a SHA-256 checksum and the signing fingerprint in each release's notes |
| **Obtainium** | Add `https://github.com/anubisproductions/data-saver-usage-blocker` as a source; it tracks the releases above |
| **F-Droid** | Requested, not yet accepted — [fdroid/rfp#4520](https://gitlab.com/fdroid/rfp/-/issues/4520) |

The Play build and the APKs here are the same source at the same tag, but **not the same
signature**: Google re-signs what it distributes, so a device will install one or the other,
not both. Pick a channel and stay on it, or uninstall before switching — which loses your
rules and baselines.

Verifying a direct download, before you trust it:

```bash
sha256sum -c SHA256SUMS.txt
aapt dump permissions DataSaver-v0.8.apk     # six lines, and no INTERNET
```

---

## Why it exists

Every tool in this space does one half of the job:

| | Shows you the numbers | Can act on them |
|---|---|---|
| Data monitors (GlassWire, My Data Manager) | yes | **no** |
| Firewalls (NetGuard, and the many clones) | **no** | yes |
| Data Saver | yes | yes |

A firewall will happily let you block an app. It will never tell you that a tile-matching
puzzle game is the single largest data consumer on your phone. On the device this was built
on, one was — **2.7 GB in 30 days, ahead of Facebook and YouTube**. That is the gap.

## Features

- **Per-app usage** for the current billing cycle, mobile and Wi-Fi separately
- **Foreground vs background split** — background bytes are what an app spent without being
  asked, and the only figure you can really act on
- **Restrict any app** from the same row as its number
- **Per-network control** — block an app on mobile but leave it working on Wi-Fi, which is the usual answer on a metered bundle
- **Estimated savings** measured against the app's own rate before you restricted it
- **Bundle gauge** — set your monthly allowance, see what's left
- **Bulk restrict** the worst background offenders in one action
- **Protected apps** — Google Play Services and your dialer/SMS app cannot be restricted by
  accident, because doing so silently kills every notification on the device
- Survives reboot
- No ads, no analytics, no crash reporting, no account

## How the restriction works

Android's `VpnService` in **package-list mode**: the tunnel's allowed-application set is the
*restricted* set, so only restricted apps enter it and everything else bypasses it entirely at
full speed. Packets from restricted apps are answered locally — nothing is forwarded, no
socket is ever opened, and no traffic leaves the device through this app.

A useful side effect: because the apps being *reported on* are exactly the apps *not* in the
tunnel, per-app accounting stays accurate while the app runs. A full-capture firewall cannot
do this — with every UID inside the tunnel, Android attributes their traffic to a VPN network
identity that `NetworkStatsManager.querySummary` cannot read back. Measured: with the engine
off, reported bytes were 108.4% of what was actually transferred; with it on, 108.1%. The
overhead is TCP/IP headers and ACKs.

## Permissions, and why each is needed

| Permission | Why |
|---|---|
| `PACKAGE_USAGE_STATS` | The only Android API exposing per-app data figures. Granted by you in Settings → Special access → Usage access |
| `QUERY_ALL_PACKAGES` | To list installed apps and attribute usage to them — the app's core function |
| `BIND_VPN_SERVICE` | The local interface used to restrict apps. No remote server is involved |
| `ACCESS_NETWORK_STATE` | To tell whether Wi-Fi or mobile is currently carrying traffic, so per-network rules apply to the right one. Reports connectivity state only — it grants no network access |
| `FOREGROUND_SERVICE` | Keeps the interface alive while restrictions are active |
| `RECEIVE_BOOT_COMPLETED` | Restores your restrictions after a restart |
| `POST_NOTIFICATIONS` | The ongoing notification Android requires for a foreground service |
| ~~`INTERNET`~~ | **Deliberately absent.** See above |

## Build

```
./gradlew assembleDebug
```

Requires JDK 17+, Android SDK 36, Gradle 9.6. AGP 9 has Kotlin support built in — do not add
the `org.jetbrains.kotlin.android` plugin, it is rejected.

## Translations

Contributions welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Strings live in
`app/src/main/res/values/strings.xml`; a new language is a `values-<code>/strings.xml`
alongside it. The layout already uses start/end padding rather than left/right, so RTL
languages work without further changes.

## Licence

[GPL-3.0](LICENSE). If you distribute a modified version, you must publish your source too.

## Credits

An [Anubis Productions](https://github.com/) app.

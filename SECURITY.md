# Security Policy

## Reporting a vulnerability

Please report security issues privately, not as a public issue.

- Preferred: [open a private security advisory](https://github.com/anubisproductions/data-saver-usage-blocker/security/advisories/new)
- Or email: anubisproductions.studio@gmail.com

Please include the app version (shown in Settings), your Android version, and enough
detail to reproduce. A proof of concept helps but is not required to report something.

I am one person, not a company. You should get an acknowledgement within 72 hours and a
first assessment within a week. If a fix is needed I will aim to ship it in the next
release and credit you in the changelog unless you would rather not be named.

## Supported versions

Only the latest release published on Google Play is supported. There is no long-term
support branch.

| Version | Supported |
| --- | --- |
| Latest Play release | Yes |
| Anything older | No - please update first |

## Scope

In scope:

- Anything that lets another app or a remote party read the usage figures this app
  collects, or influence which apps it blocks
- Any way the VpnService interface could be used to carry, capture or divert traffic.
  It is configured in package-list mode with no packet forwarding; if that is not what
  the shipped build actually does, that is a serious bug and I want to know
- Any network access by the app. The release manifest contains no `INTERNET`
  permission, so the app should be incapable of opening a socket. Evidence to the
  contrary is the single highest-severity report this project can receive
- Local privilege or data exposure through exported components, the save file, or
  backup

Out of scope:

- Android's own quick-settings wording ("This device is connected to the Internet
  through Data Saver") and the status-bar key icon. Both are shown for every app using
  `VpnService` and cannot be changed by the app
- Needing Usage access permission to read per-app figures. That is the only API Android
  provides for this, and it is granted by hand
- Reports produced only by an automated scanner, with no demonstrated impact

## Verifying the no-network claim yourself

You do not need the source or my word for it:

```
aapt2 dump permissions app-release.apk
```

If `android.permission.INTERNET` is not listed, the operating system will not permit
the process to open a socket. The permission list shown on the Play listing is
extracted from the uploaded artifact in the same way.

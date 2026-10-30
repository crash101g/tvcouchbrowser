# TVCouchBrowser

A web browser for Android TV that blocks ads, works with a TV remote, and lets you type from your phone.
Built and tested on a TCL 40Q3K (Google TV 14, 32-bit ARM, 1 GB RAM).

- **Ad blocking** with EasyList, EasyPrivacy and the uBlock Origin filter lists, refreshed every 4 days.
- **Two ways to use the remote**, switched by holding OK: a pointer (cursor mode) or jumping between
  links (link mode).
- **Your phone as a keyboard and remote**: scan a QR code, nothing to install.
- **Send to TV** from the iPhone share sheet: see [docs/send-to-tv-shortcut.md](docs/send-to-tv-shortcut.md).
- Small: a ~145 KB APK, because it uses the Android System WebView already on the TV.

## Why WebView and not Firefox's engine

The plan was GeckoView with real uBlock Origin. On the 40Q3K it filled RAM and swap, and the TV's
low-memory killer kept killing its page processes, so pages went blank. The measurements are in
[docs/engine-spike.md](docs/engine-spike.md). So the blocker is built in instead.

**What that means:** this is not uBlock Origin. The blocker understands the common filter-list rules
(blocking by domain and by path, first/third party, exceptions, and element hiding with standard CSS).
It skips regex rules, `$domain=` rules, redirects and uBO's scriptlets. On YouTube, a small script
removes the ad entries from the player data, which is what uBO's scriptlets do; YouTube may change or
detect this.

## Using it with the remote

| Button | What it does |
|---|---|
| D-pad | Cursor mode: moves the pointer (slow at first, faster while held); at the screen edge the page scrolls. Link mode: jumps to the nearest link, button or field in that direction, or scrolls 40% of a screen when there is none. |
| OK | Clicks what is under the pointer, or the link with the ring around it |
| Hold OK | Switches between cursor mode and link mode |
| Back | Leaves full-screen video, then goes back a page, then shows the start screen |
| Menu, or hold Back | Opens the menu: Back, Forward, Reload, Address, Tabs, Bookmarks, Add bookmark, Zoom −/+, Desktop site, Ad blocking, Pair phone, Settings |
| Play/Pause, Fast-forward, Rewind | Act on the page's video (FF/Rewind: 10 s) |
| Next, Previous | Jump 60 s forward or back |
| Channel up/down | Page up/down |

On the start screen, hold OK on a bookmark tile to move or remove it. Up to three tabs; when the TV runs
low on memory, background tabs are unloaded and reload when you return to them.

## Typing from your phone

Menu → **Pair phone** shows a QR code. Scan it with the iPhone camera and a page served by the TV opens:
open a website, type into the field that is selected on the TV, or use the D-pad remote.

### Security

- The phone page and its server run **only while the browser is on screen**.
- Every request needs a 128-bit random token, checked in constant time. The token is stored encrypted
  with a key held in the Android Keystore.
- Only devices on the TV's own home network can connect: private (RFC 1918 / ULA) or link-local
  addresses on the TV's subnet. Apps on the TV itself cannot.
- 5 wrong tokens from one address block it for 10 minutes.
- The endpoints take only a URL (`http`/`https` only), text, or a key name; never JavaScript.
- **Settings → Forget paired phones** makes a new token, so old QR codes and Shortcuts stop working.
- **It is plain HTTP on your home network.** Someone who can watch your Wi-Fi traffic could read the
  token and what you type. Don't send passwords through the phone page on a network you don't trust.

## Install

From a GitHub release, with `install-tv-apps.sh`, or by hand:

    adb install -r tvcouchbrowser-<version>-universal.apk

There is one APK for every TV (`universal`): the app has no native code, so it does not need one build
per CPU type.

## Build

JDK 21 and the Android SDK with platform `android-37.2` and build-tools 37.0.0.

    ./gradlew assembleDebug                  # app/build/outputs/apk/debug/app-debug.apk
    tools/release-local.sh                   # signed release + .sha256 in dist/

### Release signing

The release key lives **outside the repo** in `~/.tvcouchbrowser/` (`release.jks` and
`keystore.properties` with its password). **Back up that folder.** If it is lost, a new key can't update
the installed app: everyone would have to uninstall it first and lose their bookmarks.

For GitHub Actions, add these repository secrets, then push a tag such as `v0.1.0`:

| Secret | Value |
|---|---|
| `TVCB_KEYSTORE_BASE64` | `base64 -w0 ~/.tvcouchbrowser/release.jks` |
| `TVCB_KEYSTORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `TVCB_KEY_ALIAS` | `tvcouchbrowser` |
| `TVCB_KEY_PASSWORD` | `keyPassword` from `keystore.properties` |

The workflow publishes a full release (not a pre-release) with `tvcouchbrowser-<version>-universal.apk`
and its `.sha256` (`<hex>  <filename>`).

## Tested on the TCL 40Q3K

Working: start screen, menu, both remote modes, holding OK and Back, bookmarks with thumbnails and
reordering, tabs and unloading under memory pressure, desktop/mobile per site, ad blocking (24–38
requests blocked on the Daily Mail front page), YouTube playback (hardware AV1), full-screen video
and Back out of it, video keys, the screen staying on during playback, QR pairing, typing and Enter from
the phone, token, LAN and rate-limit checks.

Limits found:
- **DRM is Widevine L3 only** in WebView on this TV (`"tee_os":"none"`). Protected test streams play, but
  services that require L1 will refuse or limit quality.
- Some H.264 files (the test-videos.co.uk Big Buck Bunny encodes) fail in the TV's MediaTek decoder
  (`PIPELINE_ERROR_DECODE`); other H.264 and YouTube play fine.
- Filter lists take ~15 s to parse the first time and after each update; after that they load from a cache
  (network blocking ready ~0.7 s after start).

## Licenses

TVCouchBrowser is MIT licensed: see [LICENSE](LICENSE). Third-party parts are listed in [NOTICE](NOTICE). Filter lists are downloaded at run time from their publishers and are not bundled.

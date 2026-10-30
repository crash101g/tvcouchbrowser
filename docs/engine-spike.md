# Engine spike: GeckoView + uBlock Origin vs. system WebView on a TCL 40Q3K

Measured 2026-10-30 with a throwaway script and two throwaway apps, deleted after the decision.

## The TV

| | |
|---|---|
| Model | TCL 40Q3K (`G13_2K_US`), Google TV 14 |
| `ro.build.version.sdk` | 34 |
| `ro.product.cpu.abi` | `armeabi-v7a` (abilist `armeabi-v7a,armeabi`: 32-bit userspace) |
| RAM | `MemTotal` 960,764 kB; idle `MemAvailable` ~350,000 kB (37%) |
| Swap (zram) | 716,796 kB; ~356,000 kB free at idle |
| WebView | `com.google.android.webview` 156.0.8078.47 |
| Low-memory killer | MediaTek `mtklmkd`, which kills when swap is full |
| Screen | 1280x720 |

GeckoView 159.0.20261022150525 (`geckoview-armeabi-v7a`) needs SDK 26 or later, so this TV is supported.
uBlock Origin 1.75.0 installed from addons.mozilla.org through `WebExtensionController` on first run.

## Method

The spike apps are full-screen views that take the URL from an intent extra, using a desktop user agent.
Each engine starts cold, then loads five pages in turn. 20 s after each load it takes a screenshot,
does 10 swipes, and records PSS and swap PSS summed by PID over every process of the app
(Gecko: main, tab, gpu, utility, media...; WebView: app plus Chromium's sandboxed renderer),
`MemAvailable`, `SwapFree`, and kills. Kills count `am_kill` plus `[mtklmkd] start to kill`;
"own procs killed" counts `Process <spike pkg>... has died`.
Pages: Daily Mail home (ad-heavy news), YouTube desktop, Wikipedia "Television", an Allrecipes
recipe, and a page with a muted autoplaying 1080p H.264 video.

## Results

| Page | Gecko PSS | Gecko swap PSS | Gecko MemAvail | Gecko SwapFree | Gecko own procs killed | WebView PSS | WebView swap PSS | WebView MemAvail | WebView SwapFree | WebView own procs killed |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| News | 402 MB | 220 MB | 101 MB (10%) | 3 MB | 1 | 241 MB | 1 MB | 230 MB (23%) | 350 MB | 0 |
| YouTube | **439 MB** | 294 MB | **55 MB (5%)** | **2 MB** | 2 | 255 MB | 26 MB | 228 MB (23%) | 317 MB | 0 |
| Wikipedia | 374 MB | 197 MB | 156 MB (16%) | 84 MB | 3 | 271 MB | 31 MB | 206 MB (21%) | 313 MB | 0 |
| Recipe | 296 MB | 130 MB | 195 MB (20%) | 183 MB | 3 (page blank) | **314 MB** | 74 MB | 200 MB (20%) | 231 MB | 0 |
| 1080p video | 264 MB | 111 MB | 164 MB (17%) | 220 MB | 0 | 276 MB | 74 MB | 267 MB (27%) | 253 MB | 0 |

| | GeckoView + uBO | WebView |
|---|---|---|
| Cold start (`am start -W` TotalTime) | 866-1118 ms | 1456-1757 ms |
| Peak PSS | 439 MB | 314 MB |
| Peak PSS + swap PSS | 733 MB | 388 MB |
| Lowest `MemAvailable` | 55 MB (5% of RAM) | 200 MB (20%) |
| Its own processes killed by the system | 9 tab processes + 1 media process | none |
| Other apps killed meanwhile | Dolby audio service (x2), rkpdapp | Play Store, Google services (cached, restart on demand) |
| Pages left blank by a killed tab | News (first run), Recipe | none |
| Scrolling | Gecko draws through its own compositor, so `gfxinfo` can't measure it (3 frames reported). By eye: moves in jumps while a tab process restarts | 994 frames, 9.8% janky, p90 34 ms |
| Ads | uBO blocked the Daily Mail ad slots | ads shown (no blocker in the spike) |

Load times (`onPageStop`) were similar: news 21 s vs 26 s, YouTube 12.6 s vs 9.8 s, recipe 4.4 s vs 4.4 s.

## Against the decision rule

| Trigger (any one means fall back) | Result |
|---|---|
| Gecko peak PSS > 1.5x WebView peak PSS | 439 / 314 = **1.40x**: not triggered. Counting swap, 733 / 388 = 1.89x |
| Gecko pushes `MemAvailable` below 15% of RAM (144 MB) | **Triggered**: 55 MB (5%) on YouTube, 101 MB (10%) on news |
| Gecko causes low-memory kills that WebView doesn't | **Triggered**: mtklmkd killed Gecko's own tab processes 9 times, leaving pages blank, and killed the Dolby audio service; WebView lost no process of its own |

**Recommendation: fall back to WebView with a built-in blocklist** (EasyList + uBO filter lists applied
in `shouldInterceptRequest`, with simple cosmetic hiding). On this 1 GB TV, GeckoView fills swap on
ordinary pages and the TV's killer then removes Gecko's own content processes.

## Decision

2026-10-30: **WebView with a built-in blocklist**, chosen by the owner after these results. The spike
apps were removed from the TV and from the repo.

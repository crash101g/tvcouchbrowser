# "Send to TV": an iPhone Shortcut

With this Shortcut, any link in Safari, the YouTube app or anywhere else with a share sheet reaches
the TV in two taps: **Share → Send to TV**. TVCouchBrowser must be open on the TV, and the phone must be
on the same Wi-Fi.

## What you need from the TV

On the TV, open the browser menu (Menu button, or hold Back) and choose **Pair phone**. Under the QR code
is the address for the Shortcut, for example:

    http://192.168.1.174:8765/open?t=9e37b0e99db081b891eb10ef2bf3c645

The part after `t=` is your pairing token. Treat it like a password.

## Build the Shortcut (iOS 17 or later)

1. Open the **Shortcuts** app and tap **+** to make a new Shortcut. Name it **Send to TV**.
2. Tap the **ⓘ** (details) button at the bottom and switch on **Show in Share Sheet**. Tap **Done**.
3. At the top, the first block now reads *Receive **Any** input from **Share Sheet***. Tap **Any** and
   leave only **URLs**, **Safari web pages** and **Text** selected. Set *If there's no input* to
   **Ask For → Text**, so you can also run it on its own and type a website or a search.
4. Add the action **Get Contents of URL**:
   - **URL**: paste the address from the TV.
   - Tap **Show More**. **Method**: `POST`.
   - **Request Body**: `JSON`. Add a field: key `url`, type **Text**, value: tap and choose the
     **Shortcut Input** variable.
5. Optional: add **Show Notification** with the text `Sent to TV`.
6. Tap **Done**.

To use it: in Safari or the YouTube app, tap **Share**, then **Send to TV**. The first time, iOS asks
whether the Shortcut may connect to the TV's address: choose **Always Allow**.

## When it stops working

- **"Could not connect"**: the browser isn't open on the TV (its server only runs while it is on
  screen), or the phone isn't on the home Wi-Fi.
- **The TV's address changed.** The router gave the TV a new IP address. Give the TV a fixed address
  (a "DHCP reservation") in your router's settings, then update the URL in the Shortcut once.
  A `.local` name would avoid this, but on Android 14 apps cannot choose the TV's network name:
  Android announces the server under a random name like `Android_5940f092….local`.
- **Error 403**: the token changed, because **Settings → Forget paired phones** was used. Copy the new
  address from **Pair phone** into the Shortcut.
- **Error 429**: too many wrong tokens from this phone. Wait 10 minutes.

## Why there is no ready-made `.shortcut` file

iOS only imports Shortcut files that were signed on a Mac or iPhone, and each one would also need your
own TV address and token baked in. Building it by hand as above takes about two minutes.

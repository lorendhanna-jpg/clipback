# ClipBack — Google Play listing (ready to paste)

## App name (30 chars max)
ClipBack: Screen Instant Replay

## Short description (80 chars max)
Save the last 10 seconds of your screen — after they happen. Nothing uploads.

## Full description
Something funny, weird, or important just happened on your phone — and it's gone.

ClipBack quietly keeps the last 10 seconds of your screen ready at all times. When the moment happens, tap once and it's a video in your gallery. Like instant replay, for everything on your phone.

HOW IT WORKS
• Start ClipBack once and put your phone away
• The last 10 seconds of your screen are always ready in memory
• Something happens? Swipe down and tap "Save last 10 seconds" — from any app
• The clip lands in your gallery (Movies/ClipBack) as a normal video: watch, trim, share

BUILT TO BE TRUSTED
• No internet permission — ClipBack is physically unable to upload anything, ever
• Memory only — nothing touches storage until YOU tap Save
• Always visible — a recording indicator and notification show whenever it's watching
• No accounts, no ads, no analytics

CLIPBACK PRO
The 10-second window is free forever. ClipBack Pro reaches back further — 30 seconds, 1 minute, or 2 minutes. Cancel anytime.

Great for gamers clipping a play, catching a glitch to show support, saving a video-call moment, or keeping receipts of something that just crossed your screen.

## Category
Video Players & Editors  (alt: Tools)

## Tags
screen recorder, instant replay, clip, screen capture

## Contact details
- Email: lorendhanna@gmail.com
- Website: https://lorendhanna-jpg.github.io/clipback/
- Privacy policy URL (required): https://lorendhanna-jpg.github.io/clipback/privacy.html

## Data safety form (all questions)
- Does your app collect or share any of the required user data types? **No**
  (No data collected, no data shared. App has no INTERNET permission.)

## Content rating questionnaire
- Category: Utility/Tools
- Violence/sex/drugs/gambling/hate: No to all
- User-generated content shared with others: No (clips stay on-device)
- Expected rating: Everyone

## Ads
Contains ads: **No**

## In-app purchases
Yes — subscription. Create in Play Console → Monetize → Subscriptions:
- Product ID: `clipback_pro`  (MUST match exactly — the app looks up this id)
- Name: ClipBack Pro
- Benefit: replay windows of 30s / 1m / 2m
- Suggested base plans: monthly $1.99, yearly $14.99 (final prices are Lorend's call, set here)

## Assets in this folder
- icon-512.png — Play Store icon (512×512)
- feature-graphic-1024x500.png — feature graphic
- screenshot-1-home.png / screenshot-2-notification.png / screenshot-3-gallery.png (1080×1920)
  (Marketing-style renders; can swap for raw phone screenshots any time.)

## Release file
Play needs a signed release AAB (not the debug APK). Once the Play listing exists,
we add a signing key + `gradle bundleRelease` step to CI — ask Claude to "set up the
release signing" when you're ready to upload.

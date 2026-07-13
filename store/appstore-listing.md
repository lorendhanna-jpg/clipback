# ClipBack — Apple App Store listing (ready to paste into App Store Connect)

## App name (30 chars max)
ClipBack: Screen Replay

## Subtitle (30 chars max)
Save the last 10 seconds

## Promotional text (170 chars, editable anytime)
Something amazing just happened on your screen — and it's gone? Not anymore. ClipBack keeps the last 10 seconds ready. One tap, it's a video in Photos.

## Description
Something funny, weird, or important just happened on your phone — and it's gone.

ClipBack keeps the last 10 seconds of your screen ready at all times. When the moment happens, save it with one tap — like instant replay, for everything on your phone.

HOW IT WORKS
• Flip on ClipBack's screen broadcast — a red indicator shows while it's watching
• The last 10 seconds of your screen are always ready in memory
• Save mid-action from the ClipBack app, or simply stop the broadcast — stopping auto-saves the last 10 seconds
• Clips land in your Photos library as normal videos: watch, trim, share

BUILT TO BE TRUSTED
• Nothing is uploaded, ever — there are no servers, no accounts, no analytics
• Memory only — nothing is written until YOU save
• Always visible — iOS shows the recording indicator whenever ClipBack is active

CLIPBACK PRO
The 10-second window is free forever. ClipBack Pro reaches back further — 30 seconds, 1 minute, or 2 minutes. Cancel anytime.

Great for gamers clipping a play, catching a glitch to show support, saving a video-call moment, or keeping receipts of something that just crossed your screen.

## Keywords (100 chars max, comma-separated)
instant replay,screen recorder,clip,record,last 30 seconds,capture,rewind,gameplay,save,replay

## URLs
- Support URL: https://lorendhanna-jpg.github.io/clipback/
- Marketing URL: https://lorendhanna-jpg.github.io/clipback/
- Privacy Policy URL: https://lorendhanna-jpg.github.io/clipback/privacy.html

## App Privacy (nutrition label)
- Data collection: **Data Not Collected** (select "No" to every category — the app has no
  network layer, no third-party SDKs, no analytics)

## Age rating questionnaire
- All content categories: None → expected rating 4+

## Category
- Primary: Photo & Video
- Secondary: Utilities

## Pricing
- App: Free
- In-App Purchase (create in App Store Connect → Subscriptions):
  - Reference name: ClipBack Pro
  - Product ID: `clipback_pro` (keep identical to Android for sanity)
  - Group: ClipBack Pro
  - Suggested: monthly $1.99, yearly $14.99 (Lorend sets final prices)
  - NOTE: the iOS StoreKit paywall isn't wired in the app yet — ask Claude to
    "add the iOS paywall" once the Apple Developer account exists.

## Screenshots (in this folder)
- ios-screenshot-1-home.png / ios-screenshot-2-broadcast.png / ios-screenshot-3-photos.png
  (1290×2796 — fits the required 6.7"/6.9" iPhone display slot)

## Review notes (paste into App Review notes field)
ClipBack uses ReplayKit's Broadcast Upload Extension. To test: open the app, tap the
record button and Start Broadcast, use any app for ~15 seconds, then either return to
ClipBack and tap "Save last 10 seconds" or stop the broadcast from the status bar pill —
a 10-second clip appears in Photos. The rolling buffer lives in extension memory only;
the app has no network access.

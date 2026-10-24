# Architecture

How VibraText is put together. Read this before changing anything in `core/`. For the user-facing description see the
[README](../README.md); for how to send a change see [CONTRIBUTING.md](../CONTRIBUTING.md).

## Project layout

```
VibraText/
  core/   Pure Kotlin/JVM library: no Android classes, fully unit-tested on a laptop
    encoding/      EncodingTable, CharacterEncoder, CharacterDecoder, MessageFramer, VibSymbol
    playback/      TimingSettings, VibrationPatternGenerator, PlaybackController, PlaybackQueue
    notification/  NotificationParser, NotificationFilter (+ duplicate filter), MessageComposer, TemplateRenderer
    rules/         NotificationRuleEngine, rule JSON, rule editing helpers
    data/          EncodingProfile, ProfileBook, ProfileJson, AppSettings, History
    pipeline/      NotificationProcessor (filter → parse → rules → compose → encode → script), ManualPlayback
    json/          MiniJson (strict, size/depth limited parser used for storage and import)
  app/    Android application
    notification/  NotificationMonitorService, NotificationExtractor, DeviceState, Diagnostics
    vibration/     AndroidVibrationSink (VibrationEffect waveforms), PlaybackWakeLock
    data/          LocalStore (Jetpack DataStore, JSON documents)
    util/          InstalledAppsRepository, NotificationAccessHelper (settings intents, rebind)
    ui/            Compose screens + view models (home, apps, profiles, rules, history, settings,
                   testing, decoder, help), navigation, theme, shared components
  docs/ENCODING_TABLE.md   Generated from the code; a test fails if it ever drifts
```

Concerns are kept apart: parsing, encoding, pattern generation, playback control and UI do not know about each
other's internals. The UI uses MVVM (`ViewModel` + `StateFlow`); storage is DataStore with JSON documents whose
codecs live in `:core` and are unit-tested, tolerate damaged data and never throw into the service.

## How it works

```
NotificationListenerService ──► (is the app selected? is monitoring on?)  – content is only read after this check
        │
        ▼
NotificationExtractor     Android Notification → RawNotification (plain data)
        │
        ▼                                       ┌── in :core, all unit-tested ──────────────────────────────────┐
NotificationProcessor:  filter → parse → rules → duplicate check → compose fields → frame + encode → script
        │                                       └────────────────────────────────────────────────────────────────┘
        ▼
PlaybackController  (one active job; queue policy; cancellation)  ──►  AndroidVibrationSink (VibrationEffect)
```

The service handles notifications one at a time on a background thread, in arrival order. The playback controller
is shared with the Test screen, so a test and a real notification can never vibrate at the same time.

## The encoding protocol

The complete generated table is in [`ENCODING_TABLE.md`](ENCODING_TABLE.md). Summary:

* **Four symbols**: SHORT (S), LONG (L), DOUBLE (D), TRIPLE (T). Defaults: SHORT = one 150 ms pulse, LONG = one
  500 ms pulse, DOUBLE = two 150 ms pulses 120 ms apart, TRIPLE = three 150 ms pulses 120 ms apart.
* **Fixed-length codes**: every character is a code 0–63 written as **3 base-4 digits** (for example `S S S`, `S T D`, `T L L`); each digit is one symbol
  (default mapping 0 = S, 1 = L, 2 = D, 3 = T). Because all codes have the same length, there are no ambiguities and
  the code is prefix-free by construction.
* **Gaps** (each longer than the previous): between pulses of a DOUBLE/TRIPLE 120 ms → between symbols 250 ms →
  between characters 450 ms → after a space 800 ms. (Profiles must keep this order, and the editor enforces it so a
  DOUBLE can never feel like two SHORTs.)
* **Reserved control codes** that no character can ever use (a custom table that tries is rejected):
  START `T T T`, SEPARATOR `T T D`, END `T T L`, CASE_SHIFT `T T S`, TRUNCATED `T D T` (default mapping; they are
  the codes 63, 62, 61, 60 and 59). After START, SEPARATOR and END there is an 800 ms pause. Three symbols give only
  64 codes, so a table can hold at most 59 characters; the default table uses 58 of them, leaving one spare code.
* **Message frame**: `START field1 [TRUNCATED] SEPARATOR field2 [TRUNCATED] … END`, then, after the last repetition,
  one **50 ms completion tick**. For a notification the fields are normally *sender* and *message*; a field can be
  empty. Field boundaries are therefore explicit codes, never guessed from timing alone.
* **Default character set**: `a–z`, `0–9`, space and `. , ? ! : ; ' " - _ ( ) / @ # & + = % * $` (`< > [ ]` are not in the default table because there is no room; they are sent as the replacement character unless you swap them in).
* **Normalisation** (deterministic): accents removed (é → e), typographic quotes/dashes → ASCII, whitespace collapsed,
  invisible characters dropped, then lower-casing. In *preserve case* mode a capital is sent as CASE_SHIFT + letter.
  Characters not in the table (emoji, other scripts) are replaced by a configurable character (default `?`), or the
  notification is skipped, as the profile chooses. Normalisation is lossy by design; the decoder shows exactly what was
  sent.
* **Speed** multiplies all durations (Slow 1.5×, Normal 1×, Fast 0.75×, custom 0.25–4×) and re-establishes the gap order
  afterwards.
* **Decoder notation**: letters `S L D T`, optional `|` between characters, whitespace ignored, exactly three symbols per
  character, e.g. `L S S | S S S …`. Problems (wrong symbol, wrong group length, unknown code, missing END, …)
  are reported, never guessed.

Truncation is never silent: a message cut to the maximum length gets a TRUNCATED marker (optional), is flagged in the
history, and the Settings screen offers "play full", "first N characters", "sender only" or "use the summary text".

## Playback, cancellation and queueing

`PlaybackController` is where most of the care went:

* Exactly **one active playback** at any moment, identified by a *generation number*. Every call to the vibrator is made
  under a lock **and only if the caller's generation is still current**.
* **Stop** (button or interruption) revokes the generation, calls `Vibrator.cancel()`, cancels the coroutine and its
  pending delays, clears the queue and resets the state, all inside the same lock. A cancelled job that wakes up later
  sees a stale generation and does nothing, so it can never resume, **and a pending completion tick can never be
  played after cancellation**.
* Long messages are cut into batches (only at gaps between characters), each handed to the vibrator as one
  `VibrationEffect` waveform, so a very long pattern does not depend on one huge array.
* **Queue policies** when a notification arrives during playback: *Play all* (bounded queue, oldest dropped when full),
  *Newest only*, or *Combine per app* ("3 new notifications from Signal"). Settings also offer *interrupt the current
  one*, a start delay and a minimum interval between notifications.
* A partial **wake lock** (with a 10-minute safety limit) is held only while something is playing, so a long pattern is
  not stalled when the screen is off.
* Playback runs on a background dispatcher; the UI observes a `StateFlow` (current character, symbol, part of the
  message, progress, repetition, queue size, last error). A vibrator failure restores a safe idle state.

## Rule priority

When several rules match, **the most specific wins, per setting**:

1. **Contact** rule (sender name as exposed in the notification, case-insensitive)
2. **App** rule
3. **Type-of-notification** rule (calls, high priority, unknown sender, no message text, messages)
4. **Global** settings / default profile

A rule only sets what you fill in (profile, what to play, mute / always allow, important); everything else falls through
to the next level. A "contact" rule never reads your address book: Android does not expose that through notifications,
so contacts are matched by the name the notification shows.

## Senders, messages and what Android exposes

VibraText can only use what an app puts into its notification:

* Messaging apps that use `MessagingStyle` (WhatsApp, Signal, Telegram, Google Messages and most others do) expose the
  sender and text of the latest messages. In a 1:1 chat the title is used as the sender; in a group chat the per-message
  sender is used, and **never** the group name.
* **If message previews are hidden** (an app setting, or Android's "hide sensitive content on the lock screen"), the
  notification may only say "New message". VibraText does not pretend otherwise: it plays what is exposed (for example
  the app name) and the history shows what was played.
* Generic notifications (not messages) have no sender; their title is treated as part of the message.
* When the sender is unknown you choose between playing the app name in its place or leaving the sender field empty.
* Which notification the *app* posts (for example one summary notification for many messages) is the app's decision;
  group summaries are skipped by default because each message already plays on its own.

This was tested with notification payloads **built in unit tests** that mimic representative messaging notifications. It
was **not** tested against real WhatsApp or Signal installations.

## Reliability and battery settings

`NotificationListenerService` is the supported way to receive notifications, and Android keeps it bound while access is
granted. Some phone makers kill background apps anyway. If notifications are missed:

* Exempt VibraText from battery optimisation (*Help → Battery settings*). The app deliberately does not hold the
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission; you choose in the system list.
* On Xiaomi, Huawei/Honor, Oppo/Realme/OnePlus, Vivo, Samsung and others: set the app's battery usage to *Unrestricted /
  No restrictions* and allow auto-start where the phone has such a switch.
* If the dashboard shows *waiting for Android to connect*, use **Reconnect**. The app also asks Android to rebind the
  listener on start and whenever it is disconnected.
* Check the counters in *Help* to see whether notifications arrive at all, and why they were skipped.

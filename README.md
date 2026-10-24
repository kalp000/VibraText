# VibraText

**Feel who is messaging you, without looking at your phone.**

##PREVIEW

https://github.com/user-attachments/assets/8d209d6a-bf0f-4611-af41-f5e4aeed7b74

VibraText is a native Android app (Kotlin, Jetpack Compose) that turns notifications from the apps *you choose*
(WhatsApp, Signal, Telegram, …) into **vibration patterns that spell out the sender and the message**. You learn
the code once, then read notifications by touch with the phone in your pocket.

Everything happens on your phone. There is no server, no account, and the app has **no internet permission**.

> ### Project status: please read
>
> This project is at an early stage, and the README will not pretend otherwise.
>
> | Part | Status |
> |---|---|
> | **`:core`** (encoding, decoding, timing, playback control, notification parsing, rules, profiles) | Compiled and covered by **174 passing unit tests**. |
> | **`:app`** (screens, notification service, storage, vibrator) | Written in full, but **not yet compiled against the real Android SDK and never run**. A first build may need small compile fixes. |
> | **Real phones** | **Not tested** with WhatsApp, Signal, Telegram or any real vibration motor. |
> | **Prebuilt APK** | None. You build it yourself (see [Install](#install)). |
>
> The most useful thing you can do right now is build it, try it on a phone and
> [tell us what happens](CONTRIBUTING.md#testing-on-a-real-phone).

## Contents

- [What it sounds like (feels like)](#what-it-feels-like)
- [Features](#features)
- [Install](#install)
- [First five minutes](#first-five-minutes)
- [Using the app](#using-the-app)
- [Reading the vibrations](#reading-the-vibrations)
- [Customising](#customising)
- [Privacy](#privacy)
- [Limitations](#limitations)
- [Troubleshooting](#troubleshooting)
- [For developers](#for-developers)
- [Contributing](#contributing)
- [License](#license)

## What it feels like

Every character is a fixed **3-symbol code**. There are four symbols you can tell apart by touch:

| Symbol | Letter | Feel (defaults) |
|---|---|---|
| Short | `S` | one short buzz (150 ms) |
| Long | `L` | one long buzz (500 ms) |
| Double | `D` | two quick buzzes |
| Triple | `T` | three quick buzzes |

A notification is sent as `START`, the sender, a `SEPARATOR`, the message, `END`, then a tiny tick that means
"finished". For example, a message `hi` from `al` is:

```
T T T | S S S | S D T | T T D | S L T | S D S | T T L
START    a       l     SEPARATOR   h       i      END
```

At normal speed that takes about 17 seconds. Speed, gaps, repeat count and strength are all adjustable, and the full
table of characters is in [`docs/ENCODING_TABLE.md`](docs/ENCODING_TABLE.md).

## Features

- **Choose your apps.** Pick any installed apps to monitor. Nothing else is ever read.
- **Sender and message.** Understands chat notifications (including group chats) and ordinary notifications.
- **Reversible code.** The built-in **Decoder** turns symbols back into text, so you can practise and check.
- **Everything is configurable.** Symbol meanings, character table, timing, speed, repeat count and strength,
  saved as **profiles** you can duplicate, share as JSON and assign per app.
- **Rules.** Different behaviour per contact, per app or per kind of notification; mute some, always allow others,
  mark some as important.
- **Play modes.** Sender + message, sender only, message only, app + sender + message, or your own template such as
  `{sender}: {message}`.
- **Reliable stopping.** A Stop button that really stops: nothing resumes and no completion tick plays afterwards.
- **Queue control.** Play all, newest only, or combine ("3 new from Signal") when notifications arrive together.
- **Test screen.** Feel every symbol, marker, single character or whole message, with live progress.
- **Optional history**, local only, which you can switch off or keep without message text.

## Install

There is no prebuilt APK yet, so you build the app from source. You need **JDK 17** and either
**Android Studio** (Ladybug or newer) or the Android command-line SDK with **Platform 35**. The app runs on
**Android 8.0 (API 26) or newer**, and you need a phone with a vibration motor for real use.

1. Get the code: clone the repository, or download the ZIP from GitHub (green **Code** button).
2. **Android Studio:** *File → Open…*, choose the project folder, wait for Gradle sync, pick your phone, press *Run*.
3. **Command line:**
   ```bash
   echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # once; Android Studio does this for you
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

To enable USB installs, turn on *Developer options* and *USB debugging* on the phone. Release builds and signing are
described in [CONTRIBUTING.md](CONTRIBUTING.md#building-a-release).

## First five minutes

Nothing vibrates by itself after installing: monitoring is **off** and **no app is selected** until you choose.

1. **Home → Enable Notification Access.** Android opens the right settings page. Switch **VibraText** on and
   confirm Android's warning. (Android never shows a pop-up for this.)
   - On Android 13+ with an APK installed outside an app store, the switch may be greyed out. Open
     *Settings → Apps → VibraText*, tap **⋮** (top right) → **Allow restricted settings**, then try again.
2. **Apps.** Tick WhatsApp, Signal or whichever apps you want. Messaging apps are listed first.
3. **Home → Test Vibration.** Feel each symbol, the markers and a short word such as "hello". If two symbols feel
   alike on your phone, lengthen the long pulse or the gaps in your profile (*Profiles → edit → Timing*).
4. **Home → switch *Notification monitoring* on.** Read the privacy note. The status must say **Active**.
5. Lock the phone and send yourself a message from a selected app. You should feel `START`, the sender, `SEPARATOR`,
   the message, `END`, then a short tick. Press **Stop Playback** on the Home screen to cut a long message short.

## Using the app

| Screen | What it is for |
|---|---|
| **Home** | Status (*Active* only if access is granted, Android has connected the listener **and** monitoring is on), last notification, Stop / Replay / Test, shortcuts to Rules, Decoder and Help. |
| **Apps** | Choose monitored apps (searchable) and optionally give each one its own profile. |
| **Profiles** | Create from a template, rename, duplicate, delete, set the default, import or export JSON. The editor changes symbol mapping, timing, speed, repeat, strength and the character table. |
| **History** | What was played and when. Optional, local, deletable. |
| **Settings** | Play mode, message length limits, queue policy, vibration type, screen-locked behaviour, Do Not Disturb, calls, history and privacy switches, *Delete all data*. |
| **Rules** (from Home) | Per-contact, per-app and per-type rules. |
| **Test** (from Home) | Calibration and practice. |
| **Decoder** (from Home) | Type symbols such as `S L T | S D S` and read the text. |
| **Help & reliability** (from Home) | Status checks, battery guidance, counters showing why notifications were skipped (counts only, never content). |

## Reading the vibrations

- Each character is **3 symbols**, with a longer pause between characters (450 ms) than between symbols (250 ms),
  and an even longer pause after a space (800 ms).
- `START`, `SEPARATOR` and `END` are special codes made only of triples, such as `T T T` for START, so they feel
  different from letters. After each of them there is an 800 ms pause.
- Capitals are sent as lower case unless your profile preserves case.
- Letters, digits, space and `. , ? ! : ; ' " - _ ( ) / @ # & + = % * $` are in the default table. Anything else (emoji,
  other scripts) is replaced by `?`, or the notification is skipped, as you choose.
- Accents are removed (é becomes e).
- A sender or message that is too long is shortened, and (optionally) a truncation marker is played so it is never
  silent.

Practise with **Test**, and use **Decoder** to check your reading. The exact rules are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#the-encoding-protocol).

## Customising

- **Profiles**: start from *Default English*, *Quiet Notifications*, *Fast Messages*, *Important Contacts*,
  *Emergency Alerts* (repeats three times with markers) or *Case-sensitive*. Share a profile by exporting its JSON.
- **Symbol mapping**: choose which feel stands for which digit. The mapping must stay one-to-one.
- **Character table**: reassign any character's 3-symbol code, for example `S S S` or `S T D`. A table holds at most
  **59 characters**, since 3 symbols give 64 codes and 5 are reserved for the markers. A profile must keep `a–z`,
  `0–9` and space, so 22 codes are left for punctuation and anything extra; the default table uses 21 of them.
- **Rules**: *contact* beats *app*, which beats *type of notification*, which beats your global settings. A rule only
  sets what you fill in. Contacts are matched by the name shown in the notification; the app never reads your address
  book.
- **Defaults worth knowing**: messages are cut to the first 60 characters and senders to 24, notifications at least 2
  seconds apart, up to 5 queued, duplicates within 30 seconds ignored, and playback pauses during phone calls.

## Privacy

- The app has **no `INTERNET` permission**, so it cannot send anything over the network.
- A notification's content is read only **after** checking that its app is selected and monitoring is on.
- Notification content is never written to Logcat or diagnostics. Diagnostics keep only counts and reason codes.
- **History is on by default and keeps message previews.** In *Settings → History*, turn off *Keep a history*, or
  turn off *Store sender and message preview*. *Delete all data* erases everything.
- Backup and device transfer of the app's data are disabled.
- Permissions: `VIBRATE`, and `WAKE_LOCK` (only while playing). Notification access is yours to grant or revoke in
  Android settings.

## Limitations

- **Only what the notification exposes.** If an app hides message previews (its own setting, or Android's "hide
  sensitive content on the lock screen"), the notification may say only "New message", and that is all that can play.
- **Long messages are long.** A 100-character message takes on the order of a minute at normal speed. Use the length,
  speed and repeat settings.
- **Phone motors differ.** Cheap or slow motors may blur short pulses. Calibrate with the Test screen.
- **Strength** only works on vibrators with amplitude control.
- **Silent mode and Do Not Disturb** can suppress vibration. *Settings → vibration type* offers *Notification*,
  *Accessibility* and *Alarm*; which one works depends on the phone.
- **Aggressive battery managers** (Xiaomi, Huawei, Oppo, Vivo, some Samsung and others) can stop background apps. See
  [Troubleshooting](#troubleshooting).
- **English by default.** With 3-symbol codes there are only 59 usable codes, and `a–z`, `0–9` and space are required,
  so there is room for just 22 extra characters. Supporting a full non-Latin alphabet would need a change to the
  validation rules (see [CONTRIBUTING.md](CONTRIBUTING.md)).
- The app drives the phone's own vibrator, not a wearable.

## Troubleshooting

| Problem | What to try |
|---|---|
| Notification-access switch is greyed out | *Settings → Apps → VibraText → ⋮ → Allow restricted settings*, then grant access again. |
| Home says "waiting for Android to connect" | Press **Reconnect**, or switch notification access off and on once. |
| Nothing vibrates | *Help* shows why notifications were skipped (not selected, duplicate, Do Not Disturb, no readable content, …). Try the *Accessibility* vibration type and check that the phone's own vibration is on. |
| Symbols feel the same | Lengthen the long pulse and the gaps (*Profile → Timing*), or pick *Slow*. |
| Vibration stops part way, or misses notifications | Set VibraText's battery usage to *Unrestricted* (*Help → Battery settings*) and allow auto-start if your phone has that switch. |

## For developers

The project has two Gradle modules:

- **`core/`** is pure Kotlin/JVM with no Android classes: encoding, decoding, timing, playback control, parsing,
  rules, profiles and storage formats. It is fully unit-tested on a laptop.
- **`app/`** is the Android application: Compose UI (MVVM), the notification listener, DataStore storage and the
  vibrator.

```bash
./gradlew :core:test                      # 174 unit tests, no phone needed
./gradlew :app:assembleDebug              # debug APK
./gradlew :app:connectedDebugAndroidTest  # instrumented tests (phone or emulator attached)
```

More detail: [Architecture](docs/ARCHITECTURE.md) · [Encoding table](docs/ENCODING_TABLE.md) ·
[Testing and the phone checklist](docs/TESTING.md).

## Contributing

Contributions are very welcome, whether that is a bug report, a note from testing on your phone, a fix or a feature.
[**CONTRIBUTING.md**](CONTRIBUTING.md) explains how to set up, where code belongs, the rules the project keeps (above
all: privacy) and how to open a pull request.

Things that would help most right now:

1. **Build the app** and fix any compile errors the first real build reveals.
2. **Test on real phones** with real WhatsApp, Signal and Telegram, and report which phones and Android versions work.
3. **Run the instrumented tests**, which have never been run.
4. **Ideas and designs for other languages**, given the 59-code limit.

## License

No license file has been added to this repository yet. Until the maintainer adds one, the code is under default
copyright (all rights reserved), so please ask before reusing it. Contributors should expect their contributions to be
released under whatever license the maintainer chooses.

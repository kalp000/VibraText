# Contributing to VibraText

Thank you for helping. VibraText is a small project with a clear purpose and one rule above all others: **it must
never compromise the privacy of the notifications it reads.** This guide explains how to help and what a good
contribution looks like.

## Ways to help

You do not have to write code.

- **Test on a real phone** (the most valuable thing right now, see below).
- **Report a bug** or a phone that behaves badly (cheap motors, aggressive battery managers, odd notification apps).
- **Build the app and fix compile errors.** The `:app` module has never been compiled against the real Android SDK,
  so a first build may show small errors.
- **Improve the docs**, wording in the app, or the troubleshooting section.
- **Add a profile template**, or propose how other languages could be supported (see "Languages" below).
- **Fix a bug or build a feature.** Please open an issue first for anything bigger than a small fix, so we can agree
  on the approach before you spend time on it.

## Ground rules

1. **Privacy is non-negotiable.**
   - No `INTERNET` permission, no analytics, no crash-reporting SDKs, no network calls of any kind.
   - Read a notification's content **only after** checking that its app is selected and monitoring is on.
   - Never write notification content (sender, text, titles) to Logcat, diagnostics, exceptions or test output.
     Counts and reason codes are fine.
   - Do not add permissions without a very good reason, and say why in the pull request.
2. **Be honest in the docs.** Do not describe something as tested, working or supported unless it was. If you could
   not test something, say so.
3. **No placeholder code.** No `TODO` stubs standing in for real behaviour, and no features that silently do nothing.
4. **Be kind.** Assume good faith, give specific and respectful feedback, and keep discussion about the work.

## Setting up

You need **JDK 17**, and **Android Studio** (Ladybug or newer) or the Android command-line SDK with **Platform 35**.

```bash
git clone <your fork>
cd VibraText
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # Android Studio does this for you
./gradlew :core:test                                      # logic tests, no phone needed
./gradlew :app:assembleDebug                              # debug APK
```

The Gradle wrapper is pinned to Gradle 8.9. The versions in use (Android Gradle Plugin 8.7.3, Kotlin 2.0.21,
Compose BOM 2024.10.01, minSdk 26, compile/target SDK 35) are set in `build.gradle.kts` and `app/build.gradle.kts`.
You can work on `core/` without the Android SDK at all.

## Where code belongs

Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) first. In short:

| Change | Goes in |
|---|---|
| Encoding, timing, playback control, parsing, filters, rules, profile or settings formats | `core/` (pure Kotlin/JVM) |
| Reading Android notifications, vibrator, storage, system settings intents | `app/` (`notification/`, `vibration/`, `data/`, `util/`) |
| Screens, navigation, theme | `app/` (`ui/`) |

Rules for **`core/`**:

- **No Android classes**, so every behaviour can be unit-tested on a laptop. If you need something from Android, have
  the `app` layer extract plain data and pass it in (see `RawNotification`).
- Keep the public coroutines API compatible with **kotlinx-coroutines 1.9.0** (what the app uses) and avoid APIs that
  need a newer version.
- Behaviour must be **deterministic**: the same input and settings always produce the same vibration script.
- Storage codecs must **tolerate damaged data** and never throw into the notification service.
- The playback controller's cancellation guarantees (nothing resumes after Stop, no completion tick after a cancel)
  are tested. Do not weaken them.

Rules for **`app/`**:

- MVVM: screens are Compose, state is a `StateFlow` from a `ViewModel`. Keep logic out of composables; put anything
  testable in `core/`.
- The notification service must stay cheap and must never crash. Check the selected-app filter before reading content.
- Use `AppGraph`'s single shared `PlaybackController`, so a test vibration and a real notification can never overlap.

## Changing the encoding

The character table, symbol length and reserved framing codes are a **protocol**: saved and exported profiles depend
on them. If you change them:

- Update the tests and regenerate the table doc: `UPDATE_DOCS=1 ./gradlew :core:test` rewrites
  `docs/ENCODING_TABLE.md`. Without that variable a test fails when the file drifts from the code.
- Explain in the pull request what happens to existing saved profiles and exports (for example, rejected or
  migrated). Never silently reinterpret old data.
- Keep the guarantees: fixed-length codes, a bijective symbol mapping, reserved codes that no character can use,
  and `decode(encode(x)) == normalize(x)`.

## Tests

Every change to `core/` needs a test. Every bug fix needs a test that fails without the fix.

```bash
./gradlew :core:test                      # must pass before you open a pull request
./gradlew :app:connectedDebugAndroidTest  # instrumented tests; needs a phone or emulator
```

- Unit tests live in `core/src/test/kotlin`, mirroring the package of the code they test. They use JUnit 4.
- Timing and cancellation tests use the virtual clock and fake vibrator in `playback/TestSupport.kt`, so they are
  deterministic and fast. Do not add `Thread.sleep` or real delays.
- Tests must not contain real people's names, messages or numbers. Use made-up data.
- `docs/TESTING.md` maps the required scenarios to test names; update it when you add or rename tests.

A vibration test on a device can show that the pattern plays, but only a human can judge whether it is *distinct to
touch*. If you can, describe how it felt.

## Testing on a real phone

Real-device results are the most valuable contribution. Run the checklist in
[`docs/TESTING.md`](docs/TESTING.md#checklist-to-run-on-a-phone) and open an issue with:

- phone model, Android version and the app the notifications came from (for example WhatsApp or Signal)
- which steps passed and which failed, and what you felt or saw
- the numbers from *Help & reliability* if notifications were missed (they are counts only)
- **never** paste the content of real notifications, names or messages

## Reporting a bug

Open an issue and include:

1. What you did, what you expected, and what happened.
2. Phone model and Android version, and how the app was installed.
3. Whether notification access is granted and the dashboard says *Active*.
4. The *Help & reliability* counters, if the problem is a missed notification.
5. For a compile error, the full Gradle output.

Please leave out personal notification content.

### Security and privacy problems

If you find a way for VibraText to leak notification content, read apps you did not select, or otherwise break the
privacy rules above, please **do not post details in a public issue**. Contact the maintainer privately first, using
the contact details on their GitHub profile or the repository's security tab if it is enabled, so it can be fixed
before it is described publicly.

## Sending a change

1. **Fork** the repository and create a branch from `main` with a short descriptive name, such as
   `fix-queue-overflow` or `add-greek-table`.
2. Keep the change **focused**: one logical change per pull request. Unrelated clean-ups go in their own.
3. Follow the surrounding style. This project uses the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
   with 4-space indentation, and no formatter is enforced. User-visible text in plain, short language.
4. Add or update tests and docs, and run `./gradlew :core:test`.
5. Write clear commit messages: a short summary in the imperative ("Fix queue overflow when the vibrator fails"),
   then a paragraph about *why* if it is not obvious.
6. Open the pull request and fill in:
   - what changed and why
   - how you tested it (unit tests only, or on which phone and Android version)
   - anything that could affect privacy, permissions or saved data
7. Respond to review comments. A maintainer will merge when it is ready.

### Pull request checklist

- [ ] `./gradlew :core:test` passes
- [ ] New or changed behaviour has tests
- [ ] No new permissions, network access or logging of notification content
- [ ] `docs/ENCODING_TABLE.md` regenerated if the encoding changed
- [ ] README or docs updated if behaviour, settings or screens changed
- [ ] The pull request says honestly what was and was not tested

## Common changes, step by step

**Add a setting.** Add the field with a safe default to `AppSettings`, validate or clamp it in `validate`/`sanitized`,
read and write it in `SettingsJson`, add a UI control in `SettingsScreen`, use it where it matters, and add tests for the
default, the clamping and the behaviour. Old stored data without the field must still load.

**Add a profile template.** Add a `Template` to `ProfileTemplates` in `core/.../data/EncodingProfile.kt`. It must pass
`EncodingProfile.validate()`; add a test that it does.

**Add a rule kind or notification type.** Extend `NotificationClass`/`RuleScope` and `NotificationRuleEngine.classify` in
`core/.../rules/NotificationRules.kt`, keep the documented priority (contact, then app, then type, then global),
and cover it in `NotificationProcessorTest` (which exercises the rule engine), or in a new rules test.

**Languages.** A table holds at most 59 characters (64 codes minus 5 reserved), and validation requires `a–z`, `0–9`
and space, which leaves 22 codes for everything else. That is enough for accents-stripped Latin text with some
punctuation, but not for another alphabet. Supporting one means a deliberate change to the validation (and probably
a per-profile list of required characters), with tests that it round-trips and a note in the pull request about saved
profiles. Please open an issue to discuss the design first.

**Add a screen.** Create the composable and its `ViewModel` under `app/.../ui/<feature>/`, register the route in
`AppNavigation.kt`, and put any logic worth testing in `core/`.

## Releases

Release builds are minified with R8 and signed from a git-ignored `keystore.properties`.

### Building a release

1. Create a keystore:
   `keytool -genkeypair -v -keystore vibratext.jks -alias vibratext -keyalg RSA -keysize 2048 -validity 10000`
2. Create `keystore.properties` in the project root:
   ```
   storeFile=vibratext.jks
   storePassword=…
   keyAlias=vibratext
   keyPassword=…
   ```
3. Run `./gradlew :app:assembleRelease` (or `:app:bundleRelease` for an AAB).

Without `keystore.properties` the release build is produced unsigned. **Never commit the keystore or
`keystore.properties`** (both are in `.gitignore`).

## License of contributions

The repository does not have a license file yet. By contributing you agree that the maintainer may release your
contribution under the license they choose for the project. If that matters to you, ask in an issue before you start.

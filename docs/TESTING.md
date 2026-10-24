# Testing

## Unit tests (`:core`, 174 tests)

Run with `./gradlew :core:test`. The required scenarios map to tests like this:

| # | Required test | Test(s) |
|---|---|---|
| 1 | Every supported character has a valid encoding | `EncodingTableTest.everySupportedCharacterHasAValidEncoding` |
| 2 | Every character code is unique | `EncodingTableTest.everyCharacterCodeIsUniqueAndNeverReserved` |
| 3 | Encoding is deterministic | `EncoderDecoderTest.encodingIsDeterministic`, `PatternGeneratorTest.generationIsDeterministicAndDurationIsTheSumOfSegments` |
| 4 | Decoding reverses encoding | `EncoderDecoderTest.decodingReversesEncodingForSimpleText`, `roundTripHoldsForRandomTextInBothCaseModes` |
| 5–7 | Spaces, punctuation, digits preserved | `EncoderDecoderTest.spacesPunctuationAndDigitsArePreserved` |
| 8 | Unsupported characters: error or configured replacement | `EncoderDecoderTest.unsupportedCharactersAreRejectedWhenPolicyIsError`, `unsupportedCharactersUseTheConfiguredReplacement` |
| 9 | Custom profiles reject duplicate codes | `EncodingTableTest.customTableRejectsDuplicateCodes`, `DataLayerTest.profileValidationCoversNamesAndTables` |
| 10 | Invalid timing values rejected | `PatternGeneratorTest.invalidTimingValuesAreRejectedWithExplanations` |
| 11 | Message framing is unambiguous | `MessageFramerTest.*` (incl. `noCharacterCodeCanBeConfusedWithAFramingCode`, `framingRoundTripsRandomFieldLists`) |
| 12 | Sender and message fields decode correctly | `MessageFramerTest.senderAndMessageUseExplicitStartSeparatorAndEndCodes`, `ManualPlaybackTest.theSymbolTextOfAPlanDecodesBackToTheNormalizedMessage` |
| 13 | Cancellation prevents pending playback events | `PlaybackControllerTest.stopCancelsImmediatelyAndNothingResumesNotEvenTheFinalTick`, `stopWhileWaitingForTheMinimumIntervalNeverVibrates` |
| 14 | Completion tick not played after cancellation | `PlaybackControllerTest.cancellingBeforeTheLastBatchMeansTheCompletionTickIsNeverSent` |
| 15 | Completion tick after successful completion | `PlaybackControllerTest.theCompletionTickIsTheLastPulseAfterSuccessfulCompletion` |
| 16 | Repeat counts respected | `PatternGeneratorTest.repeatCountsAreRespectedAndTickOnlyFollowsTheFinalRepetition`, `PlaybackControllerTest.repeatCountIsReflectedInStateAndOutput` |
| 17 | Queue policies | `PlaybackControllerTest.playAllPlaysQueuedRequestsInOrderOneAtATime`, `replaceWithNewestKeepsOnlyTheLatestWaitingRequest`, `combinePerAppMergesWaitingRequestsOfTheSameApp`, `queueIsBoundedAndDropsTheOldestWaitingRequest` |
| 18 | Duplicate events filtered | `TemplateComposerFilterTest.duplicateEventsAreFilteredWithinTheWindowOnly`, `NotificationProcessorTest.duplicateEventsAreFilteredButNewMessagesInTheSameConversationAreNot` |
| 19 | Unselected apps ignored | `NotificationProcessorTest.notificationsFromUnselectedAppsNeverPlay` |
| 20 | VibraText's own notifications ignored | `NotificationProcessorTest.notificationsFromVibraTextItselfNeverPlay` |
| 21 | Missing sender handled | `NotificationProcessorTest.missingSenderPlaysTheAppNameFirstThenTheMessage`, `NotificationParserTest.groupChatWithoutExplicitSenderDoesNotInventOne` |
| 22 | No readable text handled | `NotificationProcessorTest.notificationsWithNoReadableTextAreIgnoredOrPlayedAsAppNameByTheUsersChoice`, `hiddenPreviewPlaysOnlyWhatTheNotificationExposes` |
| 23 | Mapping change affects encoding **and** decoding | `EncoderDecoderTest.changingSymbolMappingChangesEncodingAndDecodingConsistently`, `ManualPlaybackTest.changingTheMappingChangesThePlanConsistentlyAndStillDecodes` |

Integration-style tests with representative notification payloads: `NotificationParserTest`, `NotificationProcessorTest`
(WhatsApp-style group and 1:1 messages, a Signal-style notification with its own profile via rules, hidden previews,
emoji and accents). `PlaybackControllerTest` uses a virtual clock and a fake vibrator, so timing and cancellation are
deterministic.

## Instrumented tests (`app/src/androidTest`, **written but never run**)

`NotificationExtractorTest` (real `Notification` objects with `MessagingStyle`), `LocalStoreTest` (DataStore
persistence, clamping, concurrent writers), `PlaybackEngineTest` (real vibrator path smoke test; skipped when the device
has no vibrator). They cannot feel the motor: whether the pattern is *distinct to a human* must be judged by you.

## Checklist to run on a phone

This is the manual check to run on a real phone. **It has not been performed yet**; if you run it, please report the result (see [CONTRIBUTING.md](../CONTRIBUTING.md)).

1. Install the APK and open the app. Confirm nothing vibrates by itself.
2. *Home → Enable Notification Access*; grant it (restricted-settings steps above if needed).
3. *Apps*: select WhatsApp and Signal (they appear first when installed).
4. *Test*: play each symbol, the markers, the tick; play "hello"; adjust timing in a profile until the symbols are distinct.
5. *Home*: switch monitoring on (read the privacy note). The status must say **Active**.
6. Lock the phone. Send yourself a WhatsApp message. Expect: START, the sender's name, SEPARATOR, the message, END, then
   a short tick.
7. Send a Signal message; if you assigned it another profile (*Apps → Profile for this app*), feel that profile.
8. Press **Stop Playback** during a long message: the vibration must stop at once and not resume (no tick).
9. *Profiles → edit → Character table*: change a character's code; use *Feel*; open *Decoder* and confirm the decoder
   accepts the new code for that character and the old code is no longer accepted.
10. Close and reopen the app (or restart the phone): settings, selected apps, profiles and rules must still be there.
11. Send a message from an app you did *not* select: nothing may vibrate (the *Help* counters show nothing for it).

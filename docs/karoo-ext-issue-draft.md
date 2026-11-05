# FIT: native session fields 192/193 written as field 255, and `DeveloperField.nativeFieldNum` is not written

## Summary

Two related problems prevent an extension from adding perceived exertion (RPE) and Feel to a ride's FIT file in a way that analysis platforms recognise:

1. **Native fields unknown to Karoo's FIT profile are written as invalid field number 255.** `WriteToSessionMesg(FieldValue(193, 70.0))` (`workout_rpe`) and `FieldValue(192, 75.0)` (`workout_feel`) reach Karoo, but the session message in the saved file contains them as `unknown_255` (enum, 1 byte) with a wrong value. The FIT profile bundled with the ride software appears to predate these fields.
2. **`DeveloperField.nativeFieldNum` is accepted but not written.** Writing the same data as developer fields with `nativeFieldNum = 193 / 192` produces correct developer fields, but the `field_description` messages have no `native_field_num` (field 15), so decoders cannot map them to the native fields.

## Why it matters

Garmin head units write `workout_rpe` / `workout_feel` to the session message after the post-ride "How did it feel?" prompt. intervals.icu, TrainingPeaks and other platforms import them automatically. Karoo has no equivalent prompt, and because of the two issues above an extension cannot provide one. The rating either becomes an invalid field or lands as an unrecognised developer field.

## Reproduction

Extension with `fitFile="true"`, emitting from `startFit` during a ride (Recording / Paused):

**Case 1: native fields**
```kotlin
emitter.onNext(WriteToSessionMesg(listOf(
    FieldValue(193, 90.0),   // workout_rpe = RPE 9 × 10
    FieldValue(192, 50.0),   // workout_feel
)))
```
Logcat confirms Karoo receives them:
```
HHApp: Extensions: Adding FieldValue(fieldNum=193, value=90.0, developerField=null) to SessionMesg
HHApp: Extensions: Adding FieldValue(fieldNum=192, value=50.0, developerField=null) to SessionMesg
```
Decoded session definition of the resulting file (fitdecode):
```
field nums: [2, 5, 7, 8, 9, 14, 22, 23, 26, 28, 45, 49, 50, 57, 58, 71, 253, 255, 255, 255, 255]
unknown_255 = 90 (enum, size 1)   ×4
```
Fields 192/193 are missing; four field-255 entries appear instead.

**Case 2: developer fields with native hint**
```kotlin
val rpe = DeveloperField(
    fieldDefinitionNumber = 0, fitBaseTypeId = 2 /* uint8 */,
    fieldName = "workout_rpe", units = "", nativeFieldNum = 193,
)
emitter.onNext(WriteToSessionMesg(FieldValue(rpe, 60.0)))
```
Logcat shows the hint arriving:
```
HHApp: Extensions: Adding FieldValue(fieldNum=0, value=60.0, developerField=DeveloperField(fieldDefinitionNumber=0, fitBaseTypeId=2, fieldName=workout_rpe, units=, nativeFieldNum=193, developerDataIndex=0)) to SessionMesg
```
The written `field_description` contains only fields 0, 1, 2, 3 and 8 (`developer_data_index`, `field_definition_number`, `fit_base_type_id`, `field_name`, `units`). `native_field_num` (15) is absent. The session value itself (`workout_rpe = 60`) is written correctly.

## Expected

1. Native session fields `workout_feel` (192) and `workout_rpe` (193) are written with their profile-defined number and type. More generally, either update the bundled FIT profile or write a native `FieldValue` with an unknown field number using its given number and a base type, instead of field 255.
2. When `DeveloperField.nativeFieldNum` is set, write it as `native_field_num` in the `field_description` message, as the [FIT developer data cookbook](https://developer.garmin.com/fit/cookbook/developer-data/) describes.

Either fix would let extensions provide a Garmin-compatible post-ride RPE / Feel prompt. A built-in post-ride RPE prompt would also solve the underlying use case.

## Environment

- Karoo 3 (k24), ROM 1.665.2548.20 (4.217.1)
- karoo-ext 1.1.7
- Decoded with fitdecode (Python)

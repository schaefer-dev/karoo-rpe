# Ride Rating (RPE) for Karoo

A Hammerhead Karoo 2/3 extension that records how hard a ride felt: **RPE (1–10)** and
**Feel (Weak … Strong)**, and gets it to intervals.icu.

- After the ride is uploaded, the extension sets RPE and Feel on the matching intervals.icu
  activity through the intervals.icu API (debug builds with an API key, see below).
- It also writes both into the FIT file as developer fields `workout_rpe` / `workout_feel`.

Garmin writes these as *native* FIT session fields, which every platform imports. Current Karoo
firmware can't write those (it turns them into invalid field 255, and drops the
`native_field_num` hint), reported as
[karoo-ext#94](https://github.com/hammerheadnav/karoo-ext/issues/94). Until that's fixed, only
intervals.icu gets the rating, via the API.

## intervals.icu API key
Put your key (intervals.icu → Settings → Developer Settings) in a git-ignored `.env` at the repo
root:
```
INTERVALS_ICU_API_KEY=...
```
It is compiled into **debug builds only**; release builds never contain it and have sync off.
Don't share a debug APK built with your key.

## How to use
1. Install the APK and add the **Ride Rating** field to a ride page.
2. At the end of the ride, **pause**. A Control Center notification ("How hard was this ride?")
   appears; tap it, or tap the Ride Rating field, to open the picker.
3. Pick RPE and Feel, tap **Done**, then end the ride.

> **Rate before tapping "End Ride".** Karoo closes the FIT file when the ride ends, so a
> rating made afterwards can't be saved. You can change it as often as you like while the
> ride is open; the last value wins.

The notification only appears on a manual pause, and only while RPE is still unset.
Auto-pauses never trigger it.

When the ride ends, the rating is queued and the Karoo checks intervals.icu (Wi-Fi or phone
connection) with backoff until the uploaded activity appears, matched by device "Karoo" and a
start time within 5 minutes. It then sets `icu_rpe` and `feel` (1 = Strong … 5 = Weak) and shows
a notification. It gives up 12 hours after the ride.

## Build
SDK credentials go in `~/.gradle/gradle.properties` (`gpr.user` / `gpr.key`, a PAT with
`read:packages`), the same as for the other extensions.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/karoo_rpe-<ver>-debug.apk
```

## Verifying the FIT output
Pull a recorded ride and check the session message:
```sh
adb pull <path-to-ride>.fit ride.fit
pip install fitdecode
python -c "import fitdecode,sys
for f in fitdecode.FitReader('ride.fit'):
    if isinstance(f, fitdecode.FitDataMessage) and f.name=='session':
        print({k: f.get_value(k) for k in ('workout_rpe','workout_feel') if f.has_field(k)})"
```
For RPE 7 / Good, expect developer fields `workout_rpe=70` and `workout_feel=75`.

License: MIT

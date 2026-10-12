# Project guidance

- This is the independent acoustics project. Work only in this repository; the previous graduation project is a source reference.
- Open/build the Gradle project in `android-sdk-v5-as/`. The Android application module is `:sample`.
- New landing logic belongs in `dji.sampleV5.aircraft.acoustics`. Keep the controller independent of Android/DJI so `scripts/test_acoustics.sh` remains runnable with a JDK.
- Direction samples are target-to-aircraft bearings, geographic north 0°, clockwise. Array bearings with another origin require an explicit estimator/conversion.
- Queue completion, angle zero and missing measurements must never count as arrival. Preserve explicit position/operator arrival criteria and fail-stop behavior.
- Default operation is local simulation. Do not persist or automatically enable the live-flight switch. Do not issue real flight commands from development tools.
- The first-stage field setup has no GPS: the operator flies above the target first. Default to OPERATOR arrival and a WAIT queue; never infer arrival from simulated bearings. Position/GPS mode is an optional separate demonstration.
- API keys and signing credentials belong only in ignored `android-sdk-v5-as/secrets.properties`. Do not copy secrets from the old project or commit APKs, recordings, logs or local SDK paths.
- Validate controller changes with `./scripts/test_acoustics.sh`; validate Android changes with `./gradlew :sample:assembleDebug` in the Gradle directory. Report separately what is compiled, simulated and verified on an actual aircraft.

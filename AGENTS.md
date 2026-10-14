# Project guidance

- This is the independent acoustics project. Work only in this repository; the previous graduation project is a source reference.
- Open/build the Gradle project in `android-sdk-v5-as/`. The Android application module is `:sample`.
- New landing logic belongs in `dji.sampleV5.aircraft.acoustics`. Keep the controller independent of Android/DJI so `scripts/test_acoustics.sh` remains runnable with a JDK.
- Direction samples are target-to-aircraft bearings, geographic north 0°, clockwise. Array bearings with another origin require an explicit estimator/conversion.
- UI direction choices describe intended movement and must explicitly convert to the opposite deviation bearing. Preserve arbitrary imported bearings and the finite duration/path limits.
- Keep SDK registration, product/FC connection, per-field telemetry, read-only hardware responses and flight readiness distinct. Never refresh telemetry timestamps from synchronous cache reads or accept late pre-disconnect/pre-refresh responses as fresh data.
- Queue completion enters persistent zero-velocity HOVERING. Angle zero and WAIT never count as arrival. Landing requires a separate explicit operator action after the queue ends and stable hover is observed; no automatic arrival/landing mode.
- Default operation is local simulation. Do not persist or automatically enable the live-flight switch. Do not issue real flight commands from development tools.
- The first-stage field setup has no GPS: the operator takes off and flies above the target first. Default to WAIT*20 at 0.1 m/s. Require usable GPS or onboard visual positioning; reject ATTI/unknown modes. No automatic takeoff.
- Keep the emergency brake permanently accessible, including confirmation dialogs. Invalidate the session immediately, cancel this app's landing request, command zero and relinquish authority. No queue resume after a stop; do not send motor-stop commands.
- Run actual flight control on its dedicated worker, with a latched UI heartbeat watchdog. Late callbacks or heartbeats must never restart a stopped session. Keep uncertain SDK operations blocking a new session.
- API keys and signing credentials belong only in ignored `android-sdk-v5-as/secrets.properties`. Do not copy secrets from the old project or commit APKs, recordings, logs or local SDK paths.
- Validate controller changes with `./scripts/test_acoustics.sh`; validate Android changes with `./gradlew :sample:assembleDebug` in the Gradle directory. Report separately what is compiled, simulated and verified on an actual aircraft.

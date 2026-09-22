# Be Your Eye Community user guide

[中文](USER_MANUAL.zh-CN.md) · [Project home](README.md) · [Build and install](docs/community/BUILD.md)

This guide covers the local Community developer preview. No account or subscription is needed. Camera processing, monitors and records stay on the phone; the first model download needs internet. There is no public APK yet: build the app yourself. Monitoring requires Android 8+, arm64 and 8 GB RAM. Physical-device, natural-scene and sustained-use acceptance remain open; see [device scope](docs/community/DEVICE_SUPPORT.md) and [current evidence](evidence/current/05-release.json).

## 1. Before you start

1. Secure the phone on a stable stand, use the rear camera in portrait orientation, and connect power.
2. Keep the camera, target and lighting stable. If the phone moves, stop and reconfigure the monitor.
3. Keep the app visible. Switching to another app, the system home screen or the lock screen stops monitoring; returning does not restart it. The in-app dark-screen option can keep monitoring active; tap to return to the live view. Returning to the app's own home screen does not stop the monitor.
4. Only one monitor can run at a time. Stop it before starting another.
5. Allow camera access when prompted. Local notifications are optional: without permission, monitoring and local records still work. Community does not request microphone access.
6. For numeric reading, confirm the first download of about 78 MB. You can cancel and retry later. Installed, verified models do not depend on a renewable service lease. Reference matching and Catalog detection are experimental; try them on your own scene.

## 2. Reference-image monitoring

The home-screen illustration helps identify the feature; it is not monitoring input and is not uploaded.

Reference matching is intended for a visually distinct object entering or leaving a fixed scene, such as a parcel, cup or visible component. Its real-object performance remains unverified. Keep the target large enough in the frame and choose clear reference photos. If it is too small or indistinct, try a closer view and optional recognition testing.

### Create a monitor

1. Choose **Reference images** on the monitoring screen.
2. Select 3–20 different pictures of one target. The app handles different sizes, orientations, transparent backgrounds and common phone image formats. It rejects unreadable files and definite duplicates; every picture shown in the configuration participates in matching.
3. Leave the name blank for an automatic name, or enter your own. Set an appearance, continued-presence or disappearance condition, its duration and notifications.
4. Save and start monitoring. The app prepares the selected model and starts the camera. The target need not already be in view. If preparation fails, follow the explanation and retry.
5. Recognition testing is optional, from setup or a stopped monitor's details. It shows feedback without creating records and is not a prerequisite for saving a complete configuration.

An unfinished configuration can be resumed from its draft card, with selected pictures preserved. A saved configuration that has never received a monitoring camera frame is ready; a previously running monitor is stopped and can be restarted.

### Events and history

- Stable target presence creates a record; continued presence does not repeatedly create the same event.
- The first trigger can save one image from the triggering frame for local review. It stays on the monitoring phone.
- After five continuous seconds without the target, the appearance record closes with its start, end and duration.
- Blur, occlusion or missing camera evidence pauses judgment instead of being treated as confirmed departure.
- Brief disappearance and return remain one episode; a confirmed departure followed by a new appearance starts another.
- Stop the monitor before replacing references or deleting it. If the target was still present when monitoring stopped, the record says it ended because monitoring stopped, not because the target left.

## 3. Numeric reading

The home-screen illustration identifies the feature; actual readings come from the camera.

Numeric reading is intended for a fixed single-line display, such as a timer, percentage, balance, temperature or meter. Supported formats include signed numbers, decimals, grouping separators, currency, short units, percentages, scientific notation, `MM:SS` and `HH:MM:SS`. Recognition on a particular display still needs checking.

### Select the number

1. Choose **Numeric reading**. Without a manual region, the app searches the visible frame for readable rows.
2. Thin corner marks indicate tracking, not a scanning boundary. The first accepted target anchors a fixed position. If it disappears, the app reports that it cannot read it rather than switching to a nearby number.
3. If several numbers are visible, drag from one corner of the intended number to the opposite corner to define a region; no long press is needed. Restricting the scan to that region can reduce ambiguity. Full-frame scanning may be slower or select the wrong number.
4. Drag the upper-left or lower-right handle to resize. Drag outside the region to replace it, or clear the region to return to full-frame scanning. Stop monitoring before changing the region.
5. Confirm the reading once it is stable; a steadily changing timer can also become stable. If a decimal point is missing, use the decimal-format correction action. The selected stable reading and your input remain while editing; return to live readings to rescan. This does not change digits or signs, train the model or rewrite later camera readings.

### Set the condition

If no stable value is available yet, a monitor can start with its baseline pending. It keeps reading but cannot create threshold events until you confirm the baseline and set a condition. Retrying a failed start keeps the same saved task.

From the monitor's details, confirm the baseline when available. This pauses monitoring and opens the reading-confirmation screen. Confirm the baseline and condition, then save and restart the same monitor.

- **Above:** record when the reading exceeds the value.
- **Below:** record when the reading falls below the value.
- **Outside a range:** record below the lower bound or above the upper bound.
- A condition creates one event; once the reading returns to normal, a later crossing can create another.
- Unreadable, occluded or missing target readings do not produce a normal value or a threshold event. Nearby numbers do not replace the fixed target.

### Keep the camera fixed

Automatic mode continues scanning the whole frame but accepts readings at the original anchored position and format. Thin automatic corners are not a scan boundary. For sustained use, secure the phone and draw a manual region; the solid border marks the restricted reading area.

The target can return to the same position after briefly disappearing. If the phone or display layout moves, stop and adjust or redraw the region.

## 4. Text-described visual targets

A description searches the signed Catalog for an available visual target, such as an apple appearing. It is not a general-purpose AI prompt. Only an active, licensed model explicitly covering the target and compatible with the phone can be used. No exact match means unsupported; the app does not substitute a nearby category or generic model.

### Create a monitor

1. Choose **Text description** and enter the object or visible phenomenon.
2. Review the exact supported target and model from the Catalog. A specialized scene requires an explicitly matching model; an unsupported description stays on setup with an explanation. No model requires a subscription.
3. Choose the target, model, condition and duration, then save and start. The target need not already be visible. Optional recognition testing is available in setup and stopped-monitor details.

### Events and history

- The app highlights the configured target only. Stable appearance creates a record; confirmed absence closes it and records its duration.
- One appearance episode creates one record. Blur, missing frames and inference errors are unavailable evidence, not confirmed absence.
- At most one private trigger image is saved for the first trigger. Stop, resume and deletion follow the same local-storage rules as reference monitoring.

## 5. Running, dark screen and recovery

- The run screen shows the camera view, recognition state, event count and latest event. You can return to the app's home screen, open monitor settings or stop.
- The in-app dark-screen option keeps recognition running while the app remains visible. Tap it to restore the view. This is different from locking the phone, which stops capture.
- Returning to the app's home screen does not stop the active monitor. Reopening that monitor restores the live view.
- If the system interrupts the process, the app reports stopped. Restart the monitor yourself; it does not silently resume camera capture.
- Offline use requires the exact model to be installed and verified and still valid under the signed Catalog. Downloads or updates need internet.
- Ready means saved but never supplied with a monitoring camera frame. Stopped means it ran and is now stopped. Neither means that a background camera is still working.

## 6. Records

History groups records by date:

- Reference targets have an appearance record with a trigger image, start, end and duration; an ongoing episode remains marked present.
- Catalog target records use the same appearance and private-image rules.
- Numeric threshold events show the reading, condition and time.
- Use the filter to choose all records, visual targets or numeric readings.
- Images and full history remain on the monitoring phone. A paired phone receives text only and cannot view or remotely retrieve camera images.

## 7. Troubleshooting

### No number is being read

Check clarity, glare and clipped characters. In automatic mode, keep the number at its original position. With a manual region, keep the complete number within the solid border. Ordinary unreadable frames keep being observed; repeated retries are unnecessary.

### The wrong number is tracked

Before starting, draw a region around the intended number. Resize with the corner handles or clear and redraw it. Automatic corner marks cannot be clicked and do not restrict scanning. Monitoring will not switch to a different position after it starts.

### A reference target is not found

Make the target clearer and closer in appearance to the references. The app compares up to three salient regions and the whole frame. A small or occluded target, or more prominent background objects, may prevent a match. Adjust the fixed camera position or add references with useful angles and lighting, then try again.

### The phone gets hot or recognition slows

Keep the phone ventilated and powered. During setup, severe thermal conditions slow processing; critical conditions clear stale confirmation and release the camera, so inspect the scene again after recovery. During continuous monitoring, a critical thermal state records unavailable and stops the monitor. Let it cool and restart manually.

### When should I retry?

Retry explicit camera, model-preparation or initialization failures. Ordinary non-recognition, brief blur or a temporarily absent target continue being observed automatically.

## 8. Privacy and current limits

No account is required. Monitors, reference material and trigger images remain in app-private storage; ordinary frames stay in memory. Deleting a monitor removes its associated private media and attachments. Uninstalling deletes app data, so preserve any records you need first.

Paired alerts send encrypted text only. There is no cloud sync, AI conversation or audio recording. This preview is not a safety alarm.

## Alerts on another phone

On both phones, open **About → Paired phones**. A creates a group; B scans A's QR code and confirms the relay, or enters the copied pairing code. B allows notifications and starts receiving. A sends a test alert; verify it in B's inbox and notification shade. Then enable notifications for the actual monitor so that its events can send encrypted text.

No account, API key or project-operated server is needed. The default is the independent free public ntfy service; a compatible HTTPS relay can be selected instead. Quotas, battery restrictions, network loss and force-stop affect delivery. Open the app and start receiving again after a restart.

Images stay on the monitoring phone. Leaving a group cannot revoke a key already copied by another member; create a new group to exclude a member. Only the monitoring phone needs vision models and their supported hardware. Physical pairing and actual monitor-trigger delivery remain unverified. Follow the [pairing guide](docs/community/PAIRING.md) for setup and delivery limits.

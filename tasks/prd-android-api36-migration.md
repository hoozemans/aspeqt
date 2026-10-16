# PRD: AspeQt Android 16 (API 36) Migration

## Introduction

AspeQt is a Qt/C++ application that emulates Atari 8-bit SIO peripherals, connecting to real Atari hardware via SIO2PC-USB (FTDI FT232) cables or SIO2BT Bluetooth adapters. The Android build uses Qt for Android with JNI bridging to Java for USB serial and Bluetooth communication.

The app currently targets an outdated Android API level and uses deprecated/removed APIs that cause crashes or non-functional behavior on modern Android devices (API 31+). This migration brings the app up to Android 16 (API 36) compatibility.

## Goals

- Eliminate all crash-causing API violations (PendingIntent, BroadcastReceiver, permissions)
- Migrate storage access from legacy external storage to Storage Access Framework (SAF)
- Update Bluetooth permissions model for API 31+
- Modernize the Android manifest for API 36 compliance
- Fix pre-existing bugs discovered during audit (QTime wraparound, string comparison)
- Update the visual theme and system behavior compliance (edge-to-edge, predictive back)

## User Stories

### US-001: Fix PendingIntent Mutability Flag

**Description:** As a user connecting a USB SIO2PC device on Android 12+, I need the USB permission request to not crash so that I can use the app at all.

**Acceptance Criteria:**
- [ ] In `android/src/net/greblus/SIO2PCUS4A.java` line 84, add `PendingIntent.FLAG_MUTABLE` to the `PendingIntent.getBroadcast()` call
- [ ] App does not crash when requesting USB device permission on API 31+
- [ ] USB device permission flow still works on older API levels
- [ ] Project compiles without errors

### US-002: Fix BroadcastReceiver Export Flag

**Description:** As a user on Android 13+, I need the USB broadcast receiver to be properly registered so that USB device events are received without crashing.

**Acceptance Criteria:**
- [ ] In `android/src/net/greblus/SerialActivity.java` line 242, add API-level check: use `Context.RECEIVER_NOT_EXPORTED` flag on API 33+, fall back to no-flag on older APIs
- [ ] USB attach/detach events are still received correctly
- [ ] Project compiles without errors

### US-003: Add `android:exported` to Activity Declaration

**Description:** As a user on Android 12+, I need the app to install and launch correctly, which requires the `exported` attribute on activities with intent filters.

**Acceptance Criteria:**
- [ ] In `android/AndroidManifest.xml`, add `android:exported="true"` to the `SerialActivity` `<activity>` element
- [ ] App installs and launches on API 31+ without manifest errors
- [ ] Project compiles without errors

### US-004: Update Bluetooth Permissions for API 31+

**Description:** As a user connecting an SIO2BT Bluetooth adapter on Android 12+, I need the app to request the correct permissions so that Bluetooth functionality works.

**Acceptance Criteria:**
- [ ] In `android/AndroidManifest.xml`, add `android:maxSdkVersion="30"` to the legacy `BLUETOOTH` and `BLUETOOTH_ADMIN` permissions
- [ ] Add `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` (with `neverForLocation` flag) permissions for API 31+
- [ ] In `android/src/net/greblus/SIO2BT.java` or `SerialActivity.java`, add runtime permission request for `BLUETOOTH_CONNECT` before any Bluetooth operations
- [ ] Project compiles without errors

### US-005: Remove Programmatic Bluetooth Enable

**Description:** As a user on Android 13+, I need the app to prompt me to enable Bluetooth via the system UI instead of trying to enable it programmatically, since `BluetoothAdapter.enable()` is blocked.

**Acceptance Criteria:**
- [ ] In `android/src/net/greblus/SIO2BT.java`, replace `m_BluetoothAdapter.enable()` (line 35-36) with an `ACTION_REQUEST_ENABLE` intent launched via the activity
- [ ] In `android/src/net/greblus/SerialActivity.java`, handle the `onActivityResult` callback for the Bluetooth enable request
- [ ] User is shown a system dialog to enable Bluetooth rather than it being toggled silently
- [ ] Project compiles without errors

### US-006: Migrate File Access to Storage Access Framework (SAF)

**Description:** As a user on Android 11+, I need to be able to browse and select ATR/XFD disk image files using the system file picker, since the legacy file browsing approach using raw `java.io.File` and `Environment.getExternalStorageDirectory()` is blocked by scoped storage.

**Acceptance Criteria:**
- [ ] Replace all uses of `SimpleFileDialog.java` with SAF intents (`ACTION_OPEN_DOCUMENT` for opening files, `ACTION_CREATE_DOCUMENT` for saving)
- [ ] In `android/AndroidManifest.xml`, remove `android:requestLegacyExternalStorage="true"`
- [ ] In `android/AndroidManifest.xml`, remove or scope `WRITE_EXTERNAL_STORAGE` and `READ_EXTERNAL_STORAGE` permissions to `android:maxSdkVersion="28"`
- [ ] Use `takePersistableUriPermission()` on selected URIs so files can be re-accessed across app restarts
- [ ] In `android/src/net/greblus/SerialActivity.java`, update the file chooser flow (lines 62-67 and surrounding) to launch SAF intents and handle results
- [ ] Pass selected file content/URI back to C++ via JNI as currently done, adapting to content URIs if needed
- [ ] `SimpleFileDialog.java` can be deleted or left unused
- [ ] User can open ATR/XFD files from any storage location the system picker can access
- [ ] Project compiles without errors

### US-007: Fix QTime Midnight Wraparound Bug

**Description:** As a user, I need serial port timeouts to work correctly at all times of day, including across midnight, so that the app doesn't hang or behave erratically.

**Acceptance Criteria:**
- [ ] In `serialport-android.cpp` lines 342 and 390, replace `QTime::currentTime()` usage with `QElapsedTimer`
- [ ] Use `timer.start()` before the wait loop, and `timer.elapsed()` to check elapsed milliseconds
- [ ] All timeout logic in `serialport-android.cpp` uses `QElapsedTimer` instead of `QTime`
- [ ] Project compiles without errors

### US-008: Migrate Theme from Holo to Material

**Description:** As a user, I want the app to look modern and consistent with the current Android design language instead of the outdated Holo theme from Android 3.0.

**Acceptance Criteria:**
- [ ] In `android/AndroidManifest.xml`, change `android:theme="@android:style/Theme.Holo"` to `@style/Theme.AppCompat` or a Material theme
- [ ] If using AppCompat or Material, add the appropriate dependency to the build configuration
- [ ] App launches and displays correctly with the new theme
- [ ] Project compiles without errors

### US-009: Handle Edge-to-Edge Display (API 36)

**Description:** As a user on Android 16, I need the app's UI to not be obscured by system bars, since edge-to-edge rendering is mandatory.

**Acceptance Criteria:**
- [ ] Verify Qt rendering surface handles system bar insets correctly
- [ ] No UI elements are hidden behind the status bar or navigation bar
- [ ] If needed, apply window insets handling on the Qt/Android side
- [ ] Project compiles without errors

### US-010: Handle Predictive Back Gesture (API 36)

**Description:** As a user on Android 16, I need the back button/gesture to work correctly since `onBackPressed()` and `KEYCODE_BACK` are no longer dispatched for apps targeting API 36.

**Acceptance Criteria:**
- [ ] Either add `android:enableOnBackInvokedCallback="false"` to the manifest `<application>` element as a quick opt-out, OR migrate to the `OnBackInvokedCallback` API
- [ ] Back navigation works correctly on API 36
- [ ] Project compiles without errors

### US-011: Fix Deprecated `getParcelableExtra` Call

**Description:** As a developer, I want to use the non-deprecated API for extracting parcelable extras so the code is future-proof and warning-free.

**Acceptance Criteria:**
- [ ] In `android/src/net/greblus/SerialActivity.java` line 251, add API-level check: use the type-safe `getParcelableExtra(String, Class)` overload on API 33+, fall back to the old method on older APIs
- [ ] USB device extra is correctly extracted in both code paths
- [ ] Project compiles without errors

### US-012: Fix String Comparison Bug

**Description:** As a user, I need file dialog cancellation to be detected correctly so the app doesn't try to open a file when I cancelled the dialog.

**Acceptance Criteria:**
- [ ] In `android/src/net/greblus/SerialActivity.java` lines 120 and 140, replace `!=` string comparisons with `.equals()` (e.g., `!"Cancelled".equals(m_chosen)`)
- [ ] File chooser cancellation is correctly detected
- [ ] Project compiles without errors

## Functional Requirements

- FR-1: `PendingIntent.getBroadcast()` must include `FLAG_MUTABLE` for USB permission intents
- FR-2: Dynamic `BroadcastReceiver` registration must include `RECEIVER_NOT_EXPORTED` on API 33+
- FR-3: All activities with intent filters must declare `android:exported`
- FR-4: Bluetooth permissions must use the granular model (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`) on API 31+ with runtime requests
- FR-5: Bluetooth must not be programmatically enabled; use system intent instead
- FR-6: File access must use Storage Access Framework; no raw filesystem access to external storage
- FR-7: Selected file URIs must be persisted via `takePersistableUriPermission()` for re-access
- FR-8: Serial port timeouts must use monotonic clocks (`QElapsedTimer`), not wall-clock time
- FR-9: App theme must be Material or AppCompat based
- FR-10: App must handle edge-to-edge display on API 36
- FR-11: Back navigation must work on API 36 (opt-out or migrate)
- FR-12: `getParcelableExtra` must use the type-safe overload on API 33+
- FR-13: String comparisons in Java must use `.equals()`, not `==`/`!=`

## Non-Goals

- No migration from qmake to CMake (out of scope for this effort)
- No upgrade of the vendored `usb-serial-for-android` library (separate task)
- No migration from Qt 5 to Qt 6 (separate task, though may be prerequisite)
- No functional changes to the Atari SIO emulation logic
- No new features — this is strictly a compatibility/compliance migration
- No iOS or desktop platform changes
- No Play Store listing or signing configuration

## Technical Considerations

- **Build system:** The project uses qmake (`.pro` file). Gradle integration for Android deployment may need `compileSdkVersion` and `targetSdkVersion` set to 36 in `build.gradle`.
- **Qt version:** Qt for Android's JNI bridge uses `QJniObject`. Verify compatibility with the Qt version in use. Qt 6.x is recommended for API 36.
- **SAF complexity:** The Storage Access Framework returns `content://` URIs, not file paths. The C++ side (`serialport-android.cpp` and disk image loading) may need adaptation to read from `ContentResolver` input streams via JNI instead of file paths.
- **Testing:** Each fix should be tested on both a modern device (API 33+) and an older device or emulator (API 28-30) to verify backward compatibility.
- **Dependencies:** US-006 (SAF migration) is the largest and most complex task. All other stories are independent of each other and can be done in parallel.

## Success Metrics

- App installs and launches without crashes on Android 16 (API 36) emulator or device
- USB SIO2PC connection works end-to-end on API 36
- Bluetooth SIO2BT connection works end-to-end on API 36
- File picker opens, user can select ATR/XFD files, and disk images mount correctly
- No deprecated-API warnings at runtime (logcat)
- All existing functionality works on API 28–36 range

## Open Questions

- Which Qt version is being used for the Android build? Qt 6.x may be required for full API 36 support.
- Does the C++ disk image loading code accept content URIs or only file paths? This affects SAF migration complexity.
- Should `SimpleFileDialog.java` be deleted entirely or kept for reference?
- Is the vendored `usb-serial-for-android` library compatible with modern Android, or does it need updating as part of this migration?
- For edge-to-edge (US-009), does Qt's Android platform plugin handle window insets automatically, or is manual intervention needed?

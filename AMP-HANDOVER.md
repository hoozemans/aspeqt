# AspeQt → Android 16 (API 36) Migration Handover

## Project Overview

**AspeQt** is a Qt/C++ application that emulates Atari 8-bit SIO peripherals. It connects to a real Atari via SIO2PC-USB (FTDI FT232) cables or SIO2BT Bluetooth adapters. The Android build uses Qt for Android with JNI bridging to Java code for USB serial and Bluetooth communication.

### Codebase Structure (Android-relevant)

| File | Role |
|------|------|
| `android/AndroidManifest.xml` | App manifest, permissions, activity declaration |
| `android/src/net/greblus/SerialActivity.java` | Main activity (extends QtActivity), JNI bridge, file chooser, broadcast receiver |
| `android/src/net/greblus/SIO2PCUS4A.java` | USB serial device driver (FTDI via usb-serial-for-android) |
| `android/src/net/greblus/SIO2BT.java` | Bluetooth serial device driver (HC-06 SPP) |
| `android/src/net/greblus/SerialDevice.java` | Interface for serial devices |
| `android/src/net/greblus/SimpleFileDialog.java` | Custom file/folder picker using raw `java.io.File` |
| `android/src/com/hoho/android/usbserial/` | Vendored usb-serial-for-android library (FTDI driver) |
| `serialport-android.cpp` | C++ serial port backend, calls Java via `QJniObject` |
| `aspeqt.pro` | qmake project file |

---

## Migration Checklist

### 1. 🔴 PendingIntent Missing Mutability Flag (Crash on API 31+)

**File:** `SIO2PCUS4A.java:84`

```java
// BROKEN — crashes on API 31+
pintent = PendingIntent.getBroadcast(sa, 0, new Intent(ACTION_USB_PERMISSION), 0);
```

**Fix:** Add `PendingIntent.FLAG_MUTABLE` (needed because the intent is filled in by the system with USB device info):

```java
pintent = PendingIntent.getBroadcast(sa, 0, new Intent(ACTION_USB_PERMISSION),
    PendingIntent.FLAG_MUTABLE);
```

---

### 2. 🔴 Bluetooth Permissions Overhaul (API 31+)

**File:** `AndroidManifest.xml:27-28`

The manifest declares legacy permissions:
```xml
<uses-permission android:name="android.permission.BLUETOOTH"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
```

**Fix:** Add new granular permissions, keep legacy ones for older devices:
```xml
<!-- Legacy (API <31) -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>
<!-- API 31+ -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation"/>
```

Must also request `BLUETOOTH_CONNECT` at runtime before any BT operations.

**File:** `SIO2BT.java:35-36`

```java
// BROKEN — blocked since API 33, deprecated since API 31
m_BluetoothAdapter.enable();
```

**Fix:** Cannot programmatically enable Bluetooth anymore. Must prompt user via:
```java
Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
```

---

### 3. 🔴 Storage Access Migration (Scoped Storage)

**Files:** `AndroidManifest.xml:23-24`, `SimpleFileDialog.java`, `SerialActivity.java:62-67`

Current approach:
- Requests `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE`
- Uses `android:requestLegacyExternalStorage="true"`
- `SimpleFileDialog.java` browses the filesystem with raw `java.io.File` and `Environment.getExternalStorageDirectory()`

**This is the largest migration task.** Since API 30, legacy external storage is blocked. Since API 33, `READ/WRITE_EXTERNAL_STORAGE` are no-ops.

**Options:**
1. **Storage Access Framework (SAF)** — Use `Intent.ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT` to let the system file picker handle file selection. This would replace `SimpleFileDialog.java` entirely.
2. **`MANAGE_EXTERNAL_STORAGE`** — Grants full access but requires Play Store justification (likely rejected for this use case).
3. **MediaStore API** — Not applicable for ATR/XFD disk image files.

**Recommended:** Use SAF. Replace `SimpleFileDialog` with system intents. Store URIs via `takePersistableUriPermission()` for repeated access.

---

### 4. 🔴 BroadcastReceiver Registration Flag (API 33+)

**File:** `SerialActivity.java:242`

```java
// BROKEN on API 33+ — must specify export behavior
SerialActivity.s_activity.registerReceiver(new USBReceiver(), filter);
```

**Fix:**
```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    SerialActivity.s_activity.registerReceiver(new USBReceiver(), filter, Context.RECEIVER_NOT_EXPORTED);
} else {
    SerialActivity.s_activity.registerReceiver(new USBReceiver(), filter);
}
```

---

### 5. 🟡 Manifest: `android:exported` Required (API 31+)

**File:** `AndroidManifest.xml:7`

The `<activity>` element does not declare `android:exported`. Since it has intent filters, this is required on API 31+.

**Fix:** Add `android:exported="true"` to the SerialActivity declaration (it has `MAIN`/`LAUNCHER` and USB intent filters).

---

### 6. 🟡 Theme.Holo → Material/AppCompat

**File:** `AndroidManifest.xml:7`

```xml
android:theme="@android:style/Theme.Holo"
```

`Theme.Holo` is extremely outdated (API 11 era). Should migrate to `Theme.Material3.DayNight` or at minimum `Theme.AppCompat`.

---

### 7. 🟡 Edge-to-Edge (Mandatory for targetSdk 36)

For apps targeting API 36, edge-to-edge is enforced and cannot be opted out. The app draws into the status bar and navigation bar areas.

**Impact:** Since this is a Qt app, the Qt rendering surface should handle this mostly automatically, but verify that UI elements aren't hidden behind system bars. May need to handle window insets on the Qt/QML side.

---

### 8. 🟡 Predictive Back (API 36)

`onBackPressed()` is no longer called and `KEYCODE_BACK` is no longer dispatched for apps targeting API 36.

**Quick opt-out:** Add to manifest:
```xml
android:enableOnBackInvokedCallback="false"
```

**Proper fix:** Migrate to `OnBackInvokedCallback` API. Since this is a Qt app, Qt's back button handling may need to be verified.

---

### 9. 🟡 QTime Misuse in C++ (Bug, not API-related)

**File:** `serialport-android.cpp:342, 390`

```cpp
QTime startTime = QTime::currentTime();
// ...
int elapsed = QTime::currentTime().msecsTo(startTime);
```

`QTime` wraps at midnight, causing potential hangs/timeouts at day boundary.

**Fix:** Use `QElapsedTimer`:
```cpp
QElapsedTimer timer;
timer.start();
// ...
qint64 elapsed = timer.elapsed();
```

---

### 10. 🟢 Deprecated `getParcelableExtra` (API 33+)

**File:** `SerialActivity.java:251`

```java
// Deprecated
UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
```

**Fix:**
```java
UsbDevice device;
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
} else {
    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
}
```

---

### 11. 🟢 Build System Consideration

The project uses **qmake** (`.pro` file). Modern Qt for Android (Qt 6.x) works with both qmake and CMake, but the Gradle integration for Android deployment may need updating:

- Verify `build.gradle` and `gradle-wrapper.properties` are current
- Ensure `compileSdkVersion` and `targetSdkVersion` are set to 36
- The vendored `usb-serial-for-android` library is old — consider updating or replacing

---

### 12. 🟢 String Comparison Bug (Pre-existing)

**File:** `SerialActivity.java:120, 140`

```java
// BUG — compares object references, not values
if (m_chosen != "Cancelled")
```

**Fix:**
```java
if (!"Cancelled".equals(m_chosen))
```

---

## Suggested Order of Work

1. **Fork the repo** and set up Android build environment (Qt 6.x + Android SDK 36)
2. **Manifest fixes** (items 1, 2 permissions, 4, 5) — quick wins to get it running
3. **BroadcastReceiver & PendingIntent** (items 1, 4) — crash fixes
4. **Bluetooth migration** (item 2) — runtime permissions + remove `enable()`
5. **Storage migration** (item 3) — biggest task, replace SimpleFileDialog with SAF
6. **C++ fixes** (item 9) — QElapsedTimer
7. **UI/UX compliance** (items 6, 7, 8) — theme, edge-to-edge, predictive back
8. **Cleanup** (items 10, 12) — deprecated APIs, bugs

## References

- [Android 16 behavior changes (all apps)](https://developer.android.com/about/versions/16/behavior-changes-all)
- [Android 16 behavior changes (targeting API 36)](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Scoped Storage migration](https://developer.android.com/training/data-storage)
- [Bluetooth permissions (API 31+)](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [PendingIntent mutability](https://developer.android.com/reference/android/app/PendingIntent#FLAG_MUTABLE)

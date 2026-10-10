# PS3Dec for Android

A native Android app for decrypting and encrypting PS3 disc images, with the same flow as the container web app: pick an image, a key and a mode, press Start, watch progress. It works on folders you choose through the system folder picker, so it needs no broad storage permission.

The conversion is done by a Kotlin engine (`engine/`) that produces output identical to the reference C tool. The Android app (`app/`) provides the Compose interface, the storage layer and the foreground service.

## Development environment

You need a JDK 21 and the Android SDK command-line tools plus a few SDK packages. No NDK is needed.

### JDK 21

Gradle and the Android Gradle Plugin are built and tested against LTS JDKs, so use JDK 21 even if a newer JDK is your system default. On Arch Linux:

```sh
sudo pacman -S jdk21-openjdk      # installs next to your default JDK; the default is not changed
```

The build runs Gradle itself on JDK 21 (`gradle/gradle-daemon-jvm.properties`) and compiles with a JDK 21 toolchain, so you do not have to switch the system default. If no JDK 21 is installed, Gradle stops with a message saying so; it does not download one.

### Android SDK

Unpack the [command-line tools](https://developer.android.com/studio#command-line-tools-only) so that `sdkmanager` is at `~/Android/sdk/cmdline-tools/latest/bin/sdkmanager`, then:

```sh
export ANDROID_HOME=$HOME/Android/sdk          # fish: set -x ANDROID_HOME $HOME/Android/sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

Check the result:

```sh
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --list_installed
$ANDROID_HOME/platform-tools/adb version
```

Gradle finds the SDK through `android/local.properties` (not committed; Android Studio writes it for you), or through `ANDROID_HOME`:

```properties
sdk.dir=/home/you/Android/sdk
```

### Device

Enable Developer options on the device (Settings, About, tap the build number seven times), turn on USB debugging, connect it over USB and accept the debugging prompt on the device. `adb devices` should then list it as `device`.

### Test device

The app is tested on an AYN Thor, as reported by the device over `adb`:

| | |
|---|---|
| Model | AYN Thor (`kalama`, SoC `QCS8550`, Snapdragon 8 Gen 2 class) |
| Android | 13, API level 33, security patch 2024-01-01 |
| CPU / memory | 8 cores (`arm64-v8a`), about 11 GiB RAM |
| Displays | Built-in screen 1080 x 1920 and a second screen 1080 x 1240; the app only targets the first |
| Internal storage | 219 GB, `f2fs` on `/data`, exposed to apps at `/storage/emulated` through FUSE |
| Memory card | None was available when this was recorded, so card behavior (exFAT versus FAT32 file-size limit, provider quirks) is not verified yet |

Since the device runs API 33, the app's `minSdk` of 30 is exercised at its upper end only; Android 11 and 12 behavior is not tested on hardware.

The device's `adb` serial is not recorded here. Pass `-s <serial>` to `adb` if more than one device is attached.

## Building and testing

```sh
cd android
./gradlew :engine:test            # engine unit tests, run on the JVM, no device needed
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```

### Instrumented tests

The Compose interface tests (`app/src/androidTest`) run on an attached device or emulator:

```sh
./gradlew :app:connectedDebugAndroidTest
```

They drive the stateless screens with fixture state (setup, image list, selection and Start gating, overwrite confirmation, progress, outcomes, interruption notice, folders screen), so no folders or images are needed on the device. Note that Gradle uninstalls the app after the run, which also drops its folder grants.

### Install on a device

With a device attached and authorized:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.tonyp7.ps3dec/.MainActivity
```

### Golden vectors

The engine must produce exactly what the reference C tool produces. `engine/src/test/resources/golden-vectors.tsv` holds, for each synthetic image (a seed and a region layout), the SHA-256 of the image and of the C tool's output for it. The tests rebuild each image from its seed, convert it with the engine at several pool sizes, and compare. Layouts include converted regions of 4095, 4096 and 4097 sectors (the C tool's buffer is 4096 sectors), several converted regions, and 3k3y images in both directions.

The hashes only need regenerating when a layout is added or changed, and that needs the C tool:

```sh
cmake -S .. -B ../build -G Ninja && cmake --build ../build          # from the repository root
python3 tools/golden_vectors.py                                     # rewrites golden-vectors.tsv
python3 tools/golden_vectors.py --check                             # fails if the file would change
```

If a test fails because the engine and the C tool disagree, do not regenerate the hashes. First work out which of the two is right; the C tool's buffer handling is the more likely place for an edge-case bug than the straightforward per-region walk in the engine, but that is a finding to confirm, not assume. The image generator in `tools/golden_vectors.py` and `SyntheticImage.kt` must stay in step; each vector records its input hash so a drift shows up as such.

## The interface

- **First launch:** a setup flow asks for the three folders (images, keys, output) through the system folder picker, explains that a subfolder must be chosen, rejects an output folder that is the image folder (or inside or containing it), and offers the optional notification permission. The main screen appears only after "Continue".
- **Main screen:** the image list (name, size, classification, suggested key, reason for invalid images), the action and key choice for the selected image (only the chosen key file is read; "Choose another key..." opens a filterable list, which keeps a folder of thousands of `.dkey` files fast), Start with the reason it is disabled, and the running job's progress at the top. Two panes from about 600 dp wide (a handheld in landscape), one scrolling list otherwise.
- **Overwriting:** if the output file exists, a confirmation is shown before anything is sent; the existing file is kept until the new one is complete.
- **Gamepad:** every control is focusable with a visible focus ring, so the whole flow works with the directional pad and confirm button.
- **Theme:** follows the system light or dark setting.
- **Folders** (top bar): shows each folder and whether it is available, and lets you choose any of them again (disabled while a job runs).

## Keys

A `.dkey` file (as distributed by redump) holds the **disc key** itself, as 32 hex characters, and the app uses it as is, like `PS3Dec d key <hex>`. It is not a D1 value: treating it as one derives the disc key a second time and produces a garbage image without any error. A D1 is only used for 3k3y images, which carry theirs in the header. The golden vectors cover both the `d1` and the `key` type of the C tool.

## Releases and signing

A release is cut by pushing a `vX.Y.Z` tag on `main`'s HEAD. The release workflow then runs the engine tests, builds a signed release APK named `PS3Dec-X.Y.Z.apk`, and attaches it to the GitHub Release next to the Flatpak and the container image reference, with its SHA-256 and the signing certificate's SHA-256 in the notes. If the Android job fails there is no release at all.

An APK can only be updated by an APK signed with the same key, so releases are always signed with one release key. **If that key is lost, installed copies can never be updated** (users would have to uninstall, which also drops their folder grants). Keep a backup of the keystore and its passwords outside GitHub, which cannot show secrets again once set.

### One-time setup

Create the keystore (outside the repository; `*.jks` and `*.keystore` are git-ignored in any case):

```sh
keytool -genkeypair -storetype PKCS12 -keystore ps3dec-release.jks -alias ps3dec-release \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=PS3Dec, O=<you>"
```

With PKCS12 the key password equals the keystore password. Then create a GitHub environment named `PS3Dec` (repository Settings, Environments) and add these environment secrets:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 ps3dec-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `ps3dec-release` |
| `ANDROID_KEY_PASSWORD` | the key password (same value as the keystore password) |

Only the Android job of the release workflow declares that environment, and the workflow runs only for tag pushes, so pull requests (including from forks) never see the secrets. The environment is also where to restrict who can trigger a signing build (required reviewers, or deployments limited to `v*` tags).

### Building a signed APK locally

The same Gradle configuration is used locally; signing is read from the environment, nothing secret is in the repository:

```sh
cd android
PS3DEC_KEYSTORE_FILE=/path/to/ps3dec-release.jks \
PS3DEC_KEYSTORE_PASSWORD=... PS3DEC_KEY_ALIAS=ps3dec-release PS3DEC_KEY_PASSWORD=... \
  ./gradlew :app:assembleRelease -Pps3dec.versionName=X.Y.Z
```

Without the four variables, `assembleRelease` produces an unsigned APK. Without `-Pps3dec.versionName` the version is `0.0.0-dev`. The version code is `major * 10000 + minor * 100 + patch`, so minor and patch must be below 100.

### Verifying a downloaded APK

```sh
sha256sum PS3Dec-X.Y.Z.apk                                   # compare with the release notes
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs PS3Dec-X.Y.Z.apk
```

The certificate SHA-256 should be the same in every release; if it changes, the APK cannot update an earlier install.

## Behavior on a device

### Performance

Measured on the Thor. `tools/benchmark.sh` runs the engine through `app_process` on the device's own runtime (files in `/data/local/tmp`, output flushed every 128 MiB like the app does); the app figures come from the storage lab running real jobs through the Storage Access Framework. Figures depend on the device's state (thermal, free space, what else is writing), so treat them as orders of magnitude.

| | MiB/s |
|---|---|
| Engine only: copy, output to `/dev/null` (page cache warm) | about 5600 |
| Engine only: decrypt, output to `/dev/null`, 1 / 2 / 4 / 8 workers | 385 / 552 / 797 / 768 |
| Engine only: encrypt, output to `/dev/null`, 1 / 2 / 4 / 8 workers | 365 / 516 / 760 / 732 |
| Plain files in `/data/local/tmp`, 6 GiB, 4 workers, flushed: copy / decrypt / encrypt | 218 / 189 / 179 |
| App, through the provider, flushed: 16 GiB decrypt in about 45 s | about 365 |
| App, through the provider, flushed: 6 GiB decrypt | about 360 |

What this shows:

- The engine alone reaches roughly 750 to 800 MiB/s with four workers; eight gain nothing on this SoC (a mix of performance and efficiency cores), so the default pool is capped at four.
- Real jobs are bound by the storage and the path to it, not by crypto: the engine is two to four times faster than any figure that includes writing the output. The bulk-ECB decryption variant (one `AES/ECB` call per buffer plus an XOR pass) is therefore not needed and has not been applied. It is still in the benchmark for comparison, but its in-memory figures are dominated by JIT warm-up and should not be read as a ready gain.
- A 40 GB image takes on the order of two to four minutes on this device.
- End to end on a real disc: Nier (USA), 5.9 GiB, decrypted through the app in 19 s (about 310 MiB/s); the output's SHA-256 equals the one produced on the host by the C tool. With 4,370 key files in the key folder the main screen is listed in about 1.3 s after launch.

```sh
ANDROID_HOME=~/Android/sdk android/tools/benchmark.sh 4096 1,2,4,8 null    # engine only
ANDROID_HOME=~/Android/sdk android/tools/benchmark.sh 6144 4 file          # with file output
```

### Why the output is flushed

A job writes tens of gigabytes. Left alone, the kernel buffers whatever the storage cannot absorb as dirty pages, and on the Thor a 16 GiB conversion eventually pushed the system into critical memory pressure: the low-memory killer ended the app **while it was a foreground service** (exit reason `LOW_MEMORY`, importance 100), leaving a partial output and the in-progress marker behind. The same job completed once the output channel flushed (`FileChannel.force`) every 128 MiB, about twice as fast, with under 120 MB of dirty data and memory pressure under 1%. That is `CacheFriendlyWriteChannel` in `app/.../storage/`.

Flushing bounds dirty pages but cannot make the system promise the process survives: other apps, thermal limits or the user can still end it. The app therefore treats being killed as an ordinary event: a persisted marker is written when a job starts and cleared when it ends, so on the next launch an interrupted job is reported and its partial output removed.

### Interrupted jobs

A job can be cut short by the system (low memory, the time limit on foreground services in Android 15 and later) or by the user stopping the app. Verified on the Thor with a job killed by `am force-stop` at about a third:

- the partial output (`*.ps3dec.part`, 5.8 GB in that test) and the in-progress marker remain;
- on the next launch the marker is turned into a notice ("the previous job (name) was interrupted") that stays until the user dismisses it, the partial output is removed, and a new job is accepted;
- an existing output of the same name is never touched by an interrupted replacement.

### Foreground service

A running job is held by a foreground service of the data-sync type: a progress notification (name, percent, size, time left) with a Cancel action, and a partial wake lock that is held only while the job runs. Verified on the Thor: the job keeps running with the screen off, the notification updates, tapping Cancel stops the job within a moment, removes the temporary file, leaves an earlier output of the same name untouched, and releases the wake lock.

### Storage lab

Debug builds contain a harness, `StorageLabActivity`, that runs the storage layer (listing, classification, the output and replace protocol, recovery, free space, a conversion through the provider) against whatever folder you pick. It creates `iso`, `keys` and `out` inside the folder you choose and removes them afterwards, so pick an empty folder (not a storage root, which Android refuses):

```sh
adb shell mkdir -p /sdcard/ps3dec-lab
adb shell am start -n io.github.tonyp7.ps3dec/.lab.StorageLabActivity --ei mib 256
adb logcat -s StorageLab:I        # PASS / FAIL lines, ending in ALL CHECKS PASSED
```

Tap "Choose lab folder" on the device and select the folder (the grant persists, so later runs start automatically). "Revoke grant (test)" releases the grant to check how a lost folder is reported. "Start long job" seeds a large image in the lab folder and runs it through the real runtime and service (`--ei bigmib 16384` sets its size; grant `POST_NOTIFICATIONS` first with `adb shell pm grant io.github.tonyp7.ps3dec android.permission.POST_NOTIFICATIONS`).

What was observed on the Thor's internal storage (the `ExternalStorageProvider`):

- Created and renamed documents keep exactly the requested names, including `*.ps3dec.part` and mixed-case extensions such as `beta.ISO`. The app still verifies every name, since other providers may not.
- Renaming works, and replacing an existing output (rename aside, move into place, delete the old) works. A failure injected at the final rename restores the original.
- Free space is available by `fstatvfs` on a descriptor and equals `StatFs` of the volume.
- Folder pickers refuse the storage root and the Download folder ("Can't use this folder"); the user has to choose a subfolder.
- Directories are reported with the directory MIME type, so a folder named `x.iso` is not listed as an image.
- A revoked grant surfaces as a `SecurityException` on listing, which the app reports as an unavailable folder. The grant survives killing the process.

Not yet verified on hardware: a memory card (exFAT or FAT32), where provider names, rename support and the 4 GiB file-size limit may behave differently.

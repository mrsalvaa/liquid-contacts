<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Liquid Contacts

**English** | [Español](README.es.md)

A contacts app for Android with the same liquid-glass look as the rest of the suite (Calendar,
Clock, Gallery, Calc). Your contacts stay in the system address book, so any other app sees them.

## Privacy

- No telemetry, analytics, crash reporting, ads or Google Play Services. 100% free software.
- The only connection the app can make is to the optional server you configure yourself. Without one, none.
- Your contacts stay in the phone's address book. No backups or device transfer of the app's data.
- The trash is a copy of deleted contacts, with their photo, in the app's private files. It is removed after 30 days or when you empty it.

## Features

- List with search (ignores accents and ñ; numbers in any format), Spanish alphabetical order, side index for fast scrolling, and favorites.
- Details with call and message through the standard `ACTION_DIAL` and `ACTION_SENDTO` intents, so they work with any phone and messaging app.
- Create, edit and delete: several numbers, emails and addresses, birthdays and other dates (with or without year), notes, websites, labels and photo.
- Labels stored as system contact groups; in vCard they are `CATEGORIES`.
- Duplicates by confidence (same name; or a shared number or email with different names, marked "possible duplicate" and not preselected), with a preview where anyone can be left out. Nothing is merged without confirmation.
- Import and export `.vcf` (vCard 2.1, 3.0 and 4.0 on import; 3.0 and 4.0 on export), and share a contact.
- Other apps can ask to view, add, edit or pick a contact, and open a `.vcf`. This input is treated as untrusted.
- Argentine numbers: `011 15 2345-6789`, `+54 9 11 2345-6789` and `+54 11 2345-6789` are the same number.
- Appearance settings shared with the suite: Liquid or Solid style, theme, background, accent, and 5 app icons plus a monochrome one.

### Default area code

Numbers saved without an area code (like `15 2345-6789`) are recognized by their last 8 digits. That
only works when the subscriber number has 8 digits or more, which in Argentina means Buenos Aires
(area 11). In the rest of the country it has 6 or 7 digits, and taking 8 would mix in digits of the
area code, so those numbers are **not** recognized unless you set a **default area code** in Settings.
With it they are completed and compared exactly. The list shows a small notice when this applies,
and the setting shows how many contacts it completes. If a number matches several contacts, none is
chosen. This approximate match is never used to detect or merge duplicates.

The default country comes from the SIM the first time (not the network, which changes when you
travel) and can be changed in Settings.

## Permissions

| Permission | Why |
|---|---|
| `READ_CONTACTS` | To show your contacts. Asked when opening the app, after a screen that explains why. |
| `WRITE_CONTACTS` | To create, edit, merge and delete contacts. Asked together with the one above. |
| `INTERNET` | Only for the CardDAV server you configure. No other connections, and none at all without a server. |
| `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | Added by WorkManager: sync only with a connection, and keep going after a restart. |

That is all, verified in the merged manifest (debug and release) and in the release APK. The account
and sync adapter need nothing extra. Photos use the system photo picker and `.vcf` files the system
file picker, so there is no storage permission; calling and messaging use intents that need no
permission.

## Optional server (CardDAV)

The app works completely without a server. **Until you save one, it makes no network connections.** It has its own CardDAV client (the same approach as Liquid Calendar): it needs nothing else, and doesn't use DAVx5.

**To set it up:** Settings → CardDAV server. Write the address, username and password → *Test and save* → *Find address books* → tap an address book. Before anything is saved you see a summary: how many contacts would be downloaded, how many are already in other accounts, and a button to back up what you have (.vcf).

### Example with Radicale

```ini
# /etc/radicale/config
[server]
hosts = 0.0.0.0:5232
[auth]
type = htpasswd
htpasswd_filename = /etc/radicale/users
htpasswd_encryption = bcrypt
[rights]
type = owner_only
```

- Address: `https://your-server:5232/` (or the URL of your reverse proxy). The app finds the address books from there.
- Create the address book first, from Radicale's web page (`/.web/`), as an address book.
- **Try it first with a new, empty address book,** not your real one.

### Certificates

- **Normal certificate** (Let's Encrypt...): choose *System*. Certificates installed by the user or by apps don't count.
- **Self-signed:** choose *Fingerprint* and paste the SHA-256 one (`openssl x509 -in cert.pem -noout -fingerprint -sha256`). If you test the connection, the app shows the fingerprint it saw so you can compare it; it is never accepted on its own.
- **Your own CA:** choose *My CA* and import its certificate. It applies only to this account.
- In every case the certificate must be valid and its name **must match the address** (`subjectAltName`). There is no "trust everything" option.

### Without encryption (http)

Only if you turn it on, and only to local network or VPN addresses (private IPs, `100.64.0.0/10`, `.local`, `.lan`, `.home.arpa`, `.ts.net`). The address is checked again when connecting, so a name that resolves to a public IP gets nothing. A visible warning stays on screen, because your password and contacts travel readable on that network.

### How it syncs

- One Android account per address book, so contacts live in the system address book and any app sees them. Contacts in other accounts are never uploaded or touched.
- In the background every 30 minutes to 12 hours, or by hand, only with a connection. Local changes are uploaded soon after.
- **First sync:** only downloads. Nothing is uploaded or deleted.
- **Conflicts:** nothing is overwritten on its own. If a contact changed on both sides (or was edited on one and deleted on the other) you see both versions and choose.
- **Mass-delete protection:** if the server returns an empty address book, or if more than half and more than 10 contacts would be deleted (in either direction), sync stops *before deleting anything* and asks you.
- **Wrong password:** sync stops until you write it again, and it never retries, so the server doesn't block you. After 3 failures in 10 minutes the app doesn't even try.
- **Nothing is lost between clients:** when you edit a contact, only what changed in its vCard is rewritten. Fields the app doesn't show (`X-` properties, `IMPP`, `RELATED`, multi-level `ORG`...) go back to the server as they came. Tests include a real vCard uploaded by DAVx5 4.5.21 to a local Radicale, with a made-up contact, plus Apple and Nextcloud shapes.
- **DAVx5:** if it syncs the same address book you'd have the contacts twice. The summary warns you (and, if half or more already match, suggests not syncing). Android may show matching copies as one; merging them for real only happens from *Duplicates*, with a preview.
- The password is encrypted with Android Keystore, excluded from backups and never logged. It is not kept in Android's account manager, which would store it unencrypted.

## Known limitations

- **Photo size:** Android's address book stores contact photos up to a limit set by each phone (720 px on the one tested). The app crops 1:1 to that size and cannot store more. Android may also compress it again.
- **Trash:** it only keeps what is deleted from this app; anything deleted in another app or on the server does not arrive there. It keeps the data the app handles: what other apps added to a contact on this phone (such as a messaging app's data) doesn't come back on restore. A server contact keeps its whole vCard, but returns as a new card with a different UID.
- **Undo** of a merge lasts 10 seconds and is a copy in memory: it is lost if the app is closed. It restores the contacts' data, but recreated contacts get a new id, so **whatever other apps had linked to them is not recovered**.
- **Undo after an import** only reverts merges into existing contacts; new contacts stay (you can delete them).
- **"Insert or edit" from another app** (`ACTION_INSERT_OR_EDIT`) always opens a **new contact** form; it doesn't offer choosing an existing one. A plain `ACTION_EDIT` with a contact opens that contact. Nothing is saved until you tap save.
- A contact that Android built from cards in several accounts is edited one card at a time; each card shows its account.
- Merging across accounts only links the contacts: each account keeps its copy.
- The default area code exists only for Argentina.
- When editing, a contact's photo goes up as stored on the phone (720 px JPEG), not the original on the server. A photo given as a link stays as it is and is never downloaded.
- Only one server; to use another one, remove the account first.
- Temporary shared files are deleted when the app starts and after 10 minutes when returning to it. If the app isn't opened again, the file stays in the app's cache until then.
- With TalkBack, a field that has text takes two stops (label, then value); the date wheels were not tested with TalkBack.

## Download and install

Changes in each version are in the [changelog](CHANGELOG.md).

Get the APK from [Releases](../../releases) and install it.

Check that the file wasn't altered:

```bash
sha256sum -c liquid-contacts-v1.2.0.apk.sha256
```

```
ea2eca84e6aca162aae3e3c3c71b8750644c87a8954e883f0626e28146879eae  liquid-contacts-v1.2.0.apk
```

And that it's signed with the project's key (SHA-256 fingerprint of the certificate):

```bash
apksigner verify --print-certs liquid-contacts-v1.2.0.apk
```

```
4F:27:F0:6D:76:C3:F6:02:0F:DF:94:8D:5A:E0:F7:FD:09:6A:88:5B:B6:00:4B:95:EC:0E:CC:E4:02:2D:80:06
```

## Building

Needs JDK 17 and the Android SDK (platform 35).

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :core:test             # logic tests (JVM)
./gradlew :app:lintDebug
./gradlew :app:assembleDebug     # dev.liquidcontacts.debug, named "Contacts (test)"
./gradlew :app:assembleProfile   # same, optimized: real performance
./gradlew :app:assembleRelease
```

Debug and profile include a tool that creates made-up contacts; the release does not contain it.

## Layout

```
core/   Logic without Android, tested on the JVM: numbers, search, order, avatars, duplicates, vCard, validation, CardDAV, certificates, sync plan
app/    The app: contacts/ (system address book), external/ (other apps' requests), sync/ (accounts and sync engine), ui/
```

## What to copy to phone and messages

Self-contained files for the next apps. Change the package when copying. Nothing here touches the server.

**Pure logic (`:core`), needs `com.googlecode.libphonenumber:libphonenumber`:**

| File (`core/src/main/kotlin/dev/liquidcontacts/core/`) | What it does |
|---|---|
| `model/Contact.kt` | Data models |
| `phone/PhoneNumbers.kt` | Number normalization and `PhoneMatcher` (who a number belongs to) |
| `text/ContactText.kt` | `Fold`, `SpanishOrder`, `ContactSearch` |
| `text/Clean.kt` | Cleaning untrusted text (only if the app receives data from other apps) |
| `avatar/Avatars.kt` | Initials and stable color; uses `text/ContactText.kt` |

**Their tests (`core/src/test/kotlin/dev/liquidcontacts/core/`):** `phone/PhoneNumbersTest.kt`, `text/ContactTextTest.kt`, `avatar/AvatarsTest.kt`.

**Android (`app/src/main/java/dev/liquidcontacts/`):**

| File | What it does | Depends on |
|---|---|---|
| `contacts/SystemContacts.kt` | `ContactsContract` access | `core/model` |
| `ui/components/ContactComponents.kt` | Avatar, flat row, side index, search, notice, undo, switch row, danger button (see [DESIGN.md](DESIGN.md)) | `core/avatar`, `ui/glass` |

Phone and messages only read the system address book: they need `READ_CONTACTS`, and no server.

**Not to copy:** `core/vcard`, `core/dupes`, `core/external`, `app/.../external`, `data/` and the screens, which are specific to this app; and nothing about the server: `core/dav`, `core/tls`, `core/sync`, `core/ServerUrl.kt`, `core/Redact.kt`, `app/.../sync`.

## Dependencies

Besides AndroidX, Compose and Kotlin, the APK includes:

| Library | Version | License | Network |
|---|---|---|---|
| [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) (glass effect) | 1.0.0 | Apache-2.0 | None |
| [libphonenumber](https://github.com/google/libphonenumber) (base artifact only) | 9.0.42 | Apache-2.0 | None: country data is inside the jar, and it has no `java.net` references |
| [ez-vcard](https://github.com/mangstadt/ez-vcard) (includes a few Apache Commons Codec classes, Apache-2.0) | 0.12.2 | BSD-2-Clause | None: a photo given as a link is discarded, never downloaded |
| [vinnie](https://github.com/mangstadt/vinnie) | 2.0.2 | MIT | None |
| [OkHttp](https://square.github.io/okhttp/) | 5.3.2 | Apache-2.0 | Only to the server you configure |
| [WorkManager](https://developer.android.com/jetpack/androidx/releases/work) | 2.10.5 | Apache-2.0 | None: schedules the background sync |

ez-vcard is used without its optional jsoup, FreeMarker and Jackson dependencies, and without its hCard template and images.

Only for tests, **not in the APK**:

| Library | Version | License | Why |
|---|---|---|---|
| [Apache FreeMarker](https://freemarker.apache.org/) | 2.3.32 | Apache-2.0 | ez-vcard's `VCard` class mentions FreeMarker exceptions, and the JVM that runs the `:core` tests requires them when loading it. Android verifies lazily and never reaches them. Declared as `testRuntimeOnly`. |
| kotlin-test, JUnit 4 | | Apache-2.0, EPL-1.0 | Test framework |

### Audit

- **Full tree** (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`): 97 artifacts, all Apache-2.0 except ez-vcard (BSD-2-Clause) and vinnie (MIT), according to their POMs. All compatible with GPL-3.0-or-later.
- **No Google Play Services, Firebase or datatransport:** not in the tree and not in the release APK's code (0 matches of `com/google/android/gms`, `firebase`, `datatransport`).
- **Network:** only OkHttp, only from `core/dav`, and only with a configured server. No other library connects.
- **Final manifest:** only the two permissions above. Exported: the 5 icon aliases, `ExternalActivity` and `PickActivity`. `MainActivity` and the `FileProvider` are not exported. `tools:node="remove"` drops androidx.core's internal permission, `androidx.startup` and profileinstaller's receiver.
- **F-Droid:** `allowBackup=false`, `dependenciesInfo` off, no ART profiles in the APK. Compose brings `libandroidx.graphics.path.so` (Apache-2.0, source in AOSP) from Maven; the repo has no binaries.

<details>
<summary>The 97 release dependencies</summary>

| Dependency | Version | License |
|---|---|---|
| `androidx.activity:activity` | 1.10.1 | Apache-2.0 |
| `androidx.activity:activity-compose` | 1.10.1 | Apache-2.0 |
| `androidx.activity:activity-ktx` | 1.10.1 | Apache-2.0 |
| `androidx.annotation:annotation` | 1.9.1 | Apache-2.0 |
| `androidx.annotation:annotation-experimental` | 1.4.1 | Apache-2.0 |
| `androidx.annotation:annotation-jvm` | 1.9.1 | Apache-2.0 |
| `androidx.arch.core:core-common` | 2.2.0 | Apache-2.0 |
| `androidx.arch.core:core-runtime` | 2.2.0 | Apache-2.0 |
| `androidx.autofill:autofill` | 1.0.0 | Apache-2.0 |
| `androidx.collection:collection` | 1.5.0 | Apache-2.0 |
| `androidx.collection:collection-jvm` | 1.5.0 | Apache-2.0 |
| `androidx.collection:collection-ktx` | 1.5.0 | Apache-2.0 |
| `androidx.compose:compose-bom` | 2025.10.01 | — |
| `androidx.compose.animation:animation` | 1.9.4 | Apache-2.0 |
| `androidx.compose.animation:animation-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.animation:animation-core` | 1.9.4 | Apache-2.0 |
| `androidx.compose.animation:animation-core-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.foundation:foundation` | 1.9.4 | Apache-2.0 |
| `androidx.compose.foundation:foundation-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.foundation:foundation-layout` | 1.9.4 | Apache-2.0 |
| `androidx.compose.foundation:foundation-layout-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.material:material-icons-core` | 1.7.8 | Apache-2.0 |
| `androidx.compose.material:material-icons-core-android` | 1.7.8 | Apache-2.0 |
| `androidx.compose.material:material-icons-extended` | 1.7.8 | Apache-2.0 |
| `androidx.compose.material:material-icons-extended-android` | 1.7.8 | Apache-2.0 |
| `androidx.compose.material:material-ripple` | 1.9.4 | Apache-2.0 |
| `androidx.compose.material:material-ripple-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.material3:material3` | 1.4.0 | Apache-2.0 |
| `androidx.compose.material3:material3-android` | 1.4.0 | Apache-2.0 |
| `androidx.compose.runtime:runtime` | 1.9.4 | Apache-2.0 |
| `androidx.compose.runtime:runtime-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.runtime:runtime-annotation` | 1.9.4 | Apache-2.0 |
| `androidx.compose.runtime:runtime-annotation-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.runtime:runtime-saveable` | 1.9.4 | Apache-2.0 |
| `androidx.compose.runtime:runtime-saveable-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui-geometry` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui-geometry-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui-graphics` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui-graphics-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui-text` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui-text-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui-unit` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui-unit-android` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui-util` | 1.9.4 | Apache-2.0 |
| `androidx.compose.ui:ui-util-android` | 1.9.4 | Apache-2.0 |
| `androidx.concurrent:concurrent-futures` | 1.1.0 | Apache-2.0 |
| `androidx.core:core` | 1.16.0 | Apache-2.0 |
| `androidx.core:core-ktx` | 1.16.0 | Apache-2.0 |
| `androidx.core:core-viewtree` | 1.0.0 | Apache-2.0 |
| `androidx.customview:customview-poolingcontainer` | 1.0.0 | Apache-2.0 |
| `androidx.emoji2:emoji2` | 1.4.0 | Apache-2.0 |
| `androidx.graphics:graphics-path` | 1.0.1 | Apache-2.0 |
| `androidx.interpolator:interpolator` | 1.0.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-common` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-common-java8` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-common-jvm` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-livedata-core` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-process` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-android` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose-android` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx-android` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-android` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate` | 2.9.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-android` | 2.9.4 | Apache-2.0 |
| `androidx.profileinstaller:profileinstaller` | 1.4.0 | Apache-2.0 |
| `androidx.savedstate:savedstate` | 1.3.3 | Apache-2.0 |
| `androidx.savedstate:savedstate-android` | 1.3.3 | Apache-2.0 |
| `androidx.savedstate:savedstate-compose` | 1.3.3 | Apache-2.0 |
| `androidx.savedstate:savedstate-compose-android` | 1.3.3 | Apache-2.0 |
| `androidx.savedstate:savedstate-ktx` | 1.3.3 | Apache-2.0 |
| `androidx.startup:startup-runtime` | 1.1.1 | Apache-2.0 |
| `androidx.tracing:tracing` | 1.2.0 | Apache-2.0 |
| `androidx.versionedparcelable:versionedparcelable` | 1.1.1 | Apache-2.0 |
| `com.github.mangstadt:vinnie` | 2.0.2 | MIT |
| `com.google.guava:listenablefuture` | 1.0 | Apache-2.0 |
| `com.googlecode.ez-vcard:ez-vcard` | 0.12.2 | BSD-2-Clause |
| `com.googlecode.libphonenumber:libphonenumber` | 9.0.42 | Apache-2.0 |
| `io.github.kyant0:backdrop` | 1.0.0 | Apache-2.0 |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.21 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib-common` | 2.2.21 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.8.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom` | 1.8.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.8.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.8.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.8.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.8.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.8.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.8.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | 1.8.1 | Apache-2.0 |
| `org.jspecify:jspecify` | 1.0.0 | Apache-2.0 |

</details>

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

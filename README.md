<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Liquid Contacts

**English** | [Español](README.es.md)

> Work in progress: the full README arrives with the settings step.

## Dependencies

Libraries that go in the APK (besides AndroidX, Compose and Kotlin):

| Library | Version | License | Network |
|---|---|---|---|
| [libphonenumber](https://github.com/google/libphonenumber) (base artifact only) | 9.0.42 | Apache-2.0 | None: country data is inside the jar |
| [ez-vcard](https://github.com/mangstadt/ez-vcard) | 0.12.2 | BSD-2-Clause | None: a photo given as a link is discarded, never downloaded |
| [vinnie](https://github.com/mangstadt/vinnie) | 2.0.2 | MIT | None |

ez-vcard is used without its optional jsoup, FreeMarker and Jackson dependencies.

Only for tests, **not in the APK**:

| Library | Version | License | Why |
|---|---|---|---|
| [Apache FreeMarker](https://freemarker.apache.org/) | 2.3.32 | Apache-2.0 | ez-vcard's `VCard` class mentions FreeMarker exceptions, and the JVM that runs the `:core` tests requires them when loading it. Android verifies lazily and never reaches them. Declared as `testRuntimeOnly`. |

## License

GPL-3.0-or-later.

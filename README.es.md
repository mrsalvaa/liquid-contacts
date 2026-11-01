<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Liquid Contacts

[English](README.md) | **Español**

> En construcción: el README completo llega con el paso de ajustes.

## Dependencias

Librerías que van en el APK (además de AndroidX, Compose y Kotlin):

| Librería | Versión | Licencia | Red |
|---|---|---|---|
| [libphonenumber](https://github.com/google/libphonenumber) (solo el artefacto base) | 9.0.42 | Apache-2.0 | Ninguna: los datos de los países van dentro del jar |
| [ez-vcard](https://github.com/mangstadt/ez-vcard) | 0.12.2 | BSD-2-Clause | Ninguna: una foto que viene como enlace se descarta, nunca se descarga |
| [vinnie](https://github.com/mangstadt/vinnie) | 2.0.2 | MIT | Ninguna |

ez-vcard se usa sin sus dependencias opcionales jsoup, FreeMarker y Jackson.

Solo para los tests, **no van en el APK**:

| Librería | Versión | Licencia | Por qué |
|---|---|---|---|
| [Apache FreeMarker](https://freemarker.apache.org/) | 2.3.32 | Apache-2.0 | La clase `VCard` de ez-vcard menciona excepciones de FreeMarker y la JVM que corre los tests de `:core` las exige al cargarla. Android verifica de forma perezosa y nunca llega a usarlas. Declarada como `testRuntimeOnly`. |

## Licencia

GPL-3.0-or-later.

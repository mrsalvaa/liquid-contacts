<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Liquid Contacts

[English](README.md) | **Español**

App de contactos para Android con la misma estética de cristal líquido que el resto de la suite
(Calendar, Clock, Gallery, Calc). Tus contactos quedan en la agenda del sistema, así que cualquier
otra app los ve.

## Privacidad

- Sin telemetría, estadísticas, informes de fallos, publicidad ni Google Play Services. 100 % software libre.
- La única conexión que puede hacer la app es al servidor opcional que configures vos. Sin él, ninguna.
- Tus contactos quedan en la agenda del teléfono. Sin copias de seguridad ni transferencia de los datos de la app.
- La papelera es una copia de los contactos borrados, con su foto, en los archivos privados de la app. Se elimina sola a los 30 días o cuando la vaciás.

## Funciones

- Lista con búsqueda (sin importar tildes ni eñes; números en cualquier formato), orden alfabético en español, índice lateral para desplazarse rápido y favoritos.
- Detalle con llamar y mandar mensaje por los intents estándar `ACTION_DIAL` y `ACTION_SENDTO`, para que funcione con cualquier app de teléfono y mensajes.
- Crear, editar y borrar: varios números, emails y direcciones, cumpleaños y otras fechas (con o sin año), notas, sitios web, etiquetas y foto.
- Etiquetas guardadas como grupos de la agenda del sistema; en vCard van como `CATEGORIES`.
- Duplicados por confianza (mismo nombre; o un número o email compartido con nombres distintos, rotulado "posible duplicado" y sin preseleccionar), con vista previa donde se puede sacar a cualquiera. Nada se une sin confirmar.
- Importar y exportar `.vcf` (vCard 2.1, 3.0 y 4.0 al importar; 3.0 y 4.0 al exportar) y compartir un contacto.
- Otras apps pueden pedir ver, agregar, editar o elegir un contacto y abrir un `.vcf`. Esa entrada se trata como no confiable.
- Números argentinos: `011 15 2345-6789`, `+54 9 11 2345-6789` y `+54 11 2345-6789` son el mismo número.
- Ajustes de apariencia compartidos con la suite: estilo Liquid o Sólido, tema, fondo, énfasis y 5 íconos de la app más uno monocromo.

### Código de área por defecto

Los números guardados sin código de área (como `15 2345-6789`) se reconocen por sus últimos 8
dígitos. Eso solo funciona cuando el abonado tiene 8 dígitos o más, que en Argentina significa
Buenos Aires (área 11). En el resto del país tiene 6 o 7 dígitos, y tomar 8 mezclaría dígitos del
código de área, así que esos números **no** se reconocen salvo que cargues un **código de área por
defecto** en Ajustes. Con él se completan y se comparan exacto. La lista muestra un aviso discreto
cuando esto aplica, y el ajuste dice cuántos contactos completa. Si un número coincide con varios
contactos, no se elige ninguno. Esta coincidencia aproximada nunca se usa para detectar ni unir
duplicados.

El país por defecto sale de la SIM la primera vez (no de la red, que cambia al viajar) y se puede
cambiar en Ajustes.

## Permisos

| Permiso | Para qué |
|---|---|
| `READ_CONTACTS` | Mostrar tus contactos. Se pide al abrir la app, después de una pantalla que explica para qué. |
| `WRITE_CONTACTS` | Crear, editar, unir y borrar contactos. Se pide junto con el anterior. |
| `INTERNET` | Solo para el servidor CardDAV que configures. Ninguna otra conexión, y ninguna sin servidor. |
| `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | Los agrega WorkManager: sincronizar solo con conexión y seguir después de reiniciar. |

Nada más, verificado en el manifest mergeado (debug y release) y en el APK de release. La cuenta y
el sincronizador no necesitan nada extra. Las fotos usan el selector de fotos del sistema y los
`.vcf` el selector de archivos, así que no hay permiso de almacenamiento; llamar y mandar mensajes
usan intents que no piden permiso.

## Servidor opcional (CardDAV)

La app funciona completa sin servidor. **Hasta que guardes uno, no hace ninguna conexión de red.** Tiene su propio cliente CardDAV (el mismo enfoque que Liquid Calendar): no necesita nada más y no usa DAVx5.

**Para configurarlo:** Ajustes → Servidor CardDAV. Escribí dirección, usuario y contraseña → *Probar y guardar* → *Buscar libretas* → tocá una libreta. Antes de guardar nada ves un resumen: cuántos contactos se bajarían, cuántos ya están en otras cuentas, y un botón para respaldar lo que ya tenés (.vcf).

### Ejemplo con Radicale

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

- Dirección: `https://tu-servidor:5232/` (o la URL de tu proxy inverso). La app encuentra las libretas desde ahí.
- Creá la libreta antes, desde la página web de Radicale (`/.web/`), como libreta de direcciones.
- **Probá primero con una libreta nueva y vacía,** no con la real.

### Certificados

- **Certificado normal** (Let's Encrypt...): elegí *Del sistema*. Los certificados instalados por el usuario o por apps no cuentan.
- **Autofirmado:** elegí *Huella* y pegá la SHA-256 (`openssl x509 -in cert.pem -noout -fingerprint -sha256`). Si probás la conexión, la app muestra la huella que vio para que la compares; nunca se acepta sola.
- **Tu propia CA:** elegí *Mi CA* e importá su certificado. Vale solo para esta cuenta.
- En todos los casos el certificado tiene que estar vigente y su nombre **tiene que coincidir con la dirección** (`subjectAltName`). No existe una opción de "confiar en todo".

### Sin cifrar (http)

Solo si lo activás, y solo hacia direcciones de red local o VPN (IP privadas, `100.64.0.0/10`, `.local`, `.lan`, `.home.arpa`, `.ts.net`). La dirección se vuelve a revisar al conectar, así que un nombre que resuelve a una IP pública no recibe nada. Una advertencia queda a la vista, porque tu contraseña y tus contactos viajan legibles por esa red.

### Cómo sincroniza

- Una cuenta de Android por libreta, así los contactos viven en la agenda del sistema y cualquier app los ve. Los contactos de otras cuentas nunca se suben ni se tocan.
- En segundo plano cada 30 minutos a 12 horas, o a mano, solo con conexión. Los cambios de acá se suben al poco tiempo.
- **Primera sincronización:** solo baja. No se sube ni se borra nada.
- **Conflictos:** nada se pisa solo. Si un contacto cambió en los dos lados (o se editó en uno y se borró en el otro), ves las dos versiones y elegís.
- **Protección contra borrado masivo:** si el servidor devuelve una libreta vacía, o si se borrarían más de la mitad y más de 10 contactos (en cualquier dirección), la sincronización frena *antes de borrar nada* y te pregunta.
- **Contraseña incorrecta:** la sincronización frena hasta que la escribas de nuevo, y nunca reintenta, para que el servidor no te bloquee. Después de 3 fallos en 10 minutos, la app ni lo intenta.
- **No se pierde nada entre clientes:** al editar un contacto, solo se reescribe lo que cambió de su vCard. Los campos que la app no muestra (propiedades `X-`, `IMPP`, `RELATED`, `ORG` de varios niveles...) vuelven al servidor como vinieron. Hay tests con una vCard real subida por DAVx5 4.5.21 a un Radicale local, con un contacto inventado, además de formas de Apple y Nextcloud.
- **DAVx5:** si sincroniza la misma libreta tendrías los contactos dos veces. El resumen avisa (y, si coincide la mitad o más, sugiere no sincronizar). Android puede mostrar copias iguales como una sola; unirlas de verdad solo se hace desde *Duplicados*, con vista previa.
- La contraseña va cifrada con Android Keystore, excluida de copias de seguridad y nunca en los registros. No se guarda en el gestor de cuentas de Android, que la dejaría sin cifrar.

## Limitaciones conocidas

- **Tamaño de las fotos:** la agenda de Android guarda las fotos de contacto hasta un máximo que define cada celular (720 px en el probado). La app recorta en 1:1 a ese tamaño y no puede guardar más. Android además puede volver a comprimirla.
- **Papelera:** solo guarda lo que se borra desde esta app; lo que se borre en otra app o en el servidor no llega ahí. Guarda los datos que la app maneja: lo que otras apps agregaron a un contacto de este celular (por ejemplo, datos de una app de mensajería) no vuelve al restaurarlo. Un contacto del servidor conserva toda su vCard, pero vuelve como tarjeta nueva, con otro UID.
- **Deshacer** una unión dura 10 segundos y es una copia en memoria: se pierde si se cierra la app. Restaura los datos de los contactos, pero los recreados tienen otro id, así que **no se recupera lo que otras apps tenían vinculado a ellos**.
- **Deshacer después de importar** solo revierte las uniones con contactos existentes; los contactos nuevos quedan (se pueden borrar).
- **"Insertar o editar" desde otra app** (`ACTION_INSERT_OR_EDIT`) siempre abre el formulario de un **contacto nuevo**; no ofrece elegir uno existente. Un `ACTION_EDIT` con un contacto abre ese contacto. Nada se guarda hasta que tocás guardar.
- Un contacto que Android armó con fichas de varias cuentas se edita de a una ficha; cada ficha muestra su cuenta.
- Unir contactos de cuentas distintas solo los vincula: cada cuenta conserva su copia.
- El código de área por defecto solo existe para Argentina.
- Al editar, la foto de un contacto sube tal como está en el celu (JPEG de 720 px), no la original del servidor. Una foto que viene como enlace queda igual y nunca se descarga.
- Un solo servidor; para usar otro, primero quitá la cuenta.
- Los archivos temporales de compartir se borran al arrancar la app y, a los 10 minutos, al volver a ella. Si la app no se vuelve a abrir, el archivo queda en su caché hasta entonces.
- Con TalkBack, un campo con texto ocupa dos paradas (rótulo y valor); las ruedas de fecha no se probaron con TalkBack.

## Descargar e instalar

Los cambios de cada versión están en el [registro de cambios](CHANGELOG.es.md).

Bajá el APK desde [Releases](../../releases) e instalalo.

Comprobá que el archivo no fue alterado:

```bash
sha256sum -c liquid-contacts-v1.3.3.apk.sha256
```

```
c3371a4343e9fff0417464ac16894580276c9b29005bf31de8837d2d3dc2cb16  liquid-contacts-v1.3.3.apk
```

Y que está firmado con la clave del proyecto (huella SHA-256 del certificado):

```bash
apksigner verify --print-certs liquid-contacts-v1.3.3.apk
```

```
4F:27:F0:6D:76:C3:F6:02:0F:DF:94:8D:5A:E0:F7:FD:09:6A:88:5B:B6:00:4B:95:EC:0E:CC:E4:02:2D:80:06
```

## Compilar

Hace falta JDK 17 y el SDK de Android (plataforma 35).

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :core:test             # tests de la lógica (JVM)
./gradlew :app:lintDebug
./gradlew :app:assembleDebug     # dev.liquidcontacts.debug, se llama "Contactos (prueba)"
./gradlew :app:assembleProfile   # igual, optimizada: rendimiento real
./gradlew :app:assembleRelease
```

Debug y profile incluyen una herramienta que crea contactos inventados; la release no la contiene.

## Estructura

```
core/   Lógica sin Android, probada en la JVM: números, búsqueda, orden, avatares, duplicados, vCard, validación, CardDAV, certificados, plan de sincronización
app/    La app: contacts/ (agenda del sistema), external/ (pedidos de otras apps), sync/ (cuentas y motor), ui/
```

## Qué copiar a phone y messages

Archivos autocontenidos para las próximas apps. Al copiar, cambiá el paquete. Nada de esto toca el servidor.

**Lógica pura (`:core`), necesita `com.googlecode.libphonenumber:libphonenumber`:**

| Archivo (`core/src/main/kotlin/dev/liquidcontacts/core/`) | Qué hace |
|---|---|
| `model/Contact.kt` | Modelos de datos |
| `phone/PhoneNumbers.kt` | Normalización de números y `PhoneMatcher` (a quién pertenece un número) |
| `text/ContactText.kt` | `Fold`, `SpanishOrder`, `ContactSearch` |
| `text/Clean.kt` | Limpieza de texto no confiable (solo si la app recibe datos de otras apps) |
| `avatar/Avatars.kt` | Iniciales y color estable; usa `text/ContactText.kt` |

**Sus tests (`core/src/test/kotlin/dev/liquidcontacts/core/`):** `phone/PhoneNumbersTest.kt`, `text/ContactTextTest.kt`, `avatar/AvatarsTest.kt`.

**Android (`app/src/main/java/dev/liquidcontacts/`):**

| Archivo | Qué hace | Depende de |
|---|---|---|
| `contacts/SystemContacts.kt` | Acceso a `ContactsContract` | `core/model` |
| `ui/components/ContactComponents.kt` | Avatar, fila plana, índice lateral, búsqueda, aviso, deshacer, fila con interruptor, botón destructivo (ver [DESIGN.md](DESIGN.md)) | `core/avatar`, `ui/glass` |

Phone y messages solo leen la agenda del sistema: necesitan `READ_CONTACTS` y ningún servidor.

**No copiar:** `core/vcard`, `core/dupes`, `core/external`, `app/.../external`, `data/` y las pantallas, que son propios de esta app; y nada del servidor: `core/dav`, `core/tls`, `core/sync`, `core/ServerUrl.kt`, `core/Redact.kt`, `app/.../sync`.

## Dependencias

Además de AndroidX, Compose y Kotlin, el APK incluye:

| Librería | Versión | Licencia | Red |
|---|---|---|---|
| [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) (efecto de cristal) | 1.0.0 | Apache-2.0 | Ninguna |
| [libphonenumber](https://github.com/google/libphonenumber) (solo el artefacto base) | 9.0.42 | Apache-2.0 | Ninguna: los datos de los países van dentro del jar, y no tiene referencias a `java.net` |
| [ez-vcard](https://github.com/mangstadt/ez-vcard) (incluye unas pocas clases de Apache Commons Codec, Apache-2.0) | 0.12.2 | BSD-2-Clause | Ninguna: una foto que viene como enlace se descarta, nunca se descarga |
| [vinnie](https://github.com/mangstadt/vinnie) | 2.0.2 | MIT | Ninguna |
| [OkHttp](https://square.github.io/okhttp/) | 5.3.2 | Apache-2.0 | Solo hacia el servidor que configures |
| [WorkManager](https://developer.android.com/jetpack/androidx/releases/work) | 2.10.5 | Apache-2.0 | Ninguna: programa la sincronización en segundo plano |

ez-vcard se usa sin sus dependencias opcionales jsoup, FreeMarker y Jackson, y sin su plantilla e imágenes de hCard.

Solo para los tests, **no van en el APK**:

| Librería | Versión | Licencia | Por qué |
|---|---|---|---|
| [Apache FreeMarker](https://freemarker.apache.org/) | 2.3.32 | Apache-2.0 | La clase `VCard` de ez-vcard menciona excepciones de FreeMarker y la JVM que corre los tests de `:core` las exige al cargarla. Android verifica de forma perezosa y nunca llega a usarlas. Declarada como `testRuntimeOnly`. |
| kotlin-test, JUnit 4 | | Apache-2.0, EPL-1.0 | Marco de pruebas |

### Auditoría

- **Árbol completo** (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`): 97 artefactos, todos Apache-2.0 salvo ez-vcard (BSD-2-Clause) y vinnie (MIT), según sus POM. Todos compatibles con GPL-3.0-or-later.
- **Sin Google Play Services, Firebase ni datatransport:** no están en el árbol ni en el código del APK de release (0 coincidencias de `com/google/android/gms`, `firebase`, `datatransport`).
- **Red:** solo OkHttp, solo desde `core/dav` y solo con un servidor configurado. Ninguna otra librería se conecta.
- **Manifest final:** solo los dos permisos de arriba. Exportados: los 5 alias de íconos, `ExternalActivity` y `PickActivity`. `MainActivity` y el `FileProvider` no están exportados. `tools:node="remove"` quita el permiso interno de androidx.core, `androidx.startup` y el receptor de profileinstaller.
- **F-Droid:** `allowBackup=false`, `dependenciesInfo` apagado, sin perfiles ART en el APK. Compose trae `libandroidx.graphics.path.so` (Apache-2.0, fuente en AOSP) desde Maven; el repo no tiene binarios.

<details>
<summary>Las 97 dependencias de la release</summary>

| Dependencia | Versión | Licencia |
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

## Licencia

GPL-3.0-or-later. Ver [LICENSE](LICENSE).

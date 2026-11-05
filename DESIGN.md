# Diseño: Liquid Contacts dentro de la suite

SPDX-License-Identifier: GPL-3.0-or-later

Qué reutiliza esta app, qué escribió para sí, y qué conviene saber antes de hacer phone y messages.

## Origen de `ui/glass`

`app/src/main/java/dev/liquidcontacts/ui/glass` es una copia **sin cambios** de
`app/src/main/java/dev/liquidcalendar/ui/glass` de Liquid Calendar, commit `25ca0d6`
(`Components.kt`, `Controls.kt`, `Dialog.kt`, `Theme.kt`, `UiIcons.kt`). Solo cambian el paquete y
el encabezado SPDX. De ese mismo commit salen `ui/screens/AppearanceCard.kt`,
`ui/screens/Common.kt`, `util/AppIcons.kt`, `util/Strings.kt`, `util/Display.kt`,
`data/Stores.kt` y los textos de `res/values*/strings_glass.xml`.

Colores, formas, blur y animaciones están descritos en el `DESIGN.md` de Liquid Calc.

## Valores de referencia del cristal

Contacts y Calendar tienen el `ui/glass` de referencia; Clock, Gallery y Calc se comparan contra él. Cada fila es lo que debe verse igual en toda la suite.

| Fondo o estilo | `tint` | `tintStrong` | Notas |
|---|---|---|---|
| Movimiento y color de énfasis, claro | blanco 32 % | blanco 50 % | `LightGlass`, con manchas (`blobs`) |
| Movimiento y color de énfasis, oscuro | `#0A0C12` 28 % | `#0A0C12` 42 % | `DarkGlass`, con manchas (`blobs`) |
| Liso, claro | blanco 70 % | blanco 85 % | `PlainLightGlass`, base `#F2F3F5`, acento `#1A73E8` |
| Liso, oscuro | blanco 5 % | blanco 9 % | `PlainDarkGlass`, base `#0E0F12`, acento `#8AB4F8` |
| Sólido | opaco | opaco | `SolidLight` y `SolidDark`, sin efectos |

**Píldoras flotantes** (pestañas y las dos de la selección múltiple): `CircleShape`, `strong = true`, `frost = 4.dp`, `tint = p.pillVeil()`.

- `pillVeil()` baja el velo a blanco 30 % solo en Liquid, claro y liso; en los demás casos usa el de la paleta. Con 85 % la refracción casi no se ve.
- Tienen que ir en el slot `floating` de `GlassRoot`: es el único que ve la lista por debajo. Dentro de la página solo se ve el fondo y, en Liso, parecen sólidas.
- Liso es `backgroundMode == 2`; `animatedBackground` no tiene que ver.


## Ajustes

`data/AppSettings.kt` guarda en `files/settings.json` los 8 campos de personalización de la suite,
con los mismos nombres, valores y valores por defecto que Calendar: `themeMode`, `backgroundMode`,
`colorSet`, `accentColor`, `animatedBackground`, `uiStyle`, `solidAccent`, `appIcon`. Los propios
de Contactos van aparte: `defaultCountry`, `defaultAreaCode`, `areaCodeNoticeDismissed`,
`newContactAccount`.

Íconos: los 4 estilos en el orden de siempre (Liquid por defecto, Material, Samsung, iPhone) más monocromo. Los fondos son los de Calendar; el dibujo es una silueta de persona.

## Componentes propios

Todos en `ui/components/ContactComponents.kt`, escritos en el estilo de `ui/glass`. Para copiarlos
hace falta `ui/glass` y `core/avatar/Avatars.kt` (que usa `Fold`, de `core/text/ContactText.kt`).

| Componente | Uso | Notas |
|---|---|---|
| `ContactAvatar(name, modifier, size, photoUri, photo)` | Foto o iniciales | Color de `avatarColor`: 9 tonos de énfasis apagados (sin el gris), siempre el mismo para el mismo nombre. Miniaturas en caché LRU de 8 MB. Decorativo para TalkBack: el nombre lo dice la fila. |
| `ContactRow(name, onClick, modifier, subtitle, photoUri, starred, starredLabel, trailing)` | Fila de lista | **Plana**, sin cristal. 64 dp de alto mínimo; toda la fila es el botón; TalkBack lee nombre, dato y favorito. |
| `LetterHeader(letter)` | Letra de cada grupo | Encabezado para TalkBack. |
| `AlphabetIndex(letters, available, onLetter, modifier, jumpLabel)` | Índice lateral | Tocar o arrastrar salta a la letra. Si no entran todas (22 dp cada una), muestra algunas con puntos; al arrastrar se recorren todas. Con TalkBack, cada letra con contactos es un botón. Usar con `SpanishOrder.SECTIONS`. |
| `SearchField(value, onValueChange, placeholder, clearLabel, modifier, focusRequester)` | Búsqueda | Píldora de cristal con lupa y botón de borrar de 48 dp. Usar con `ContactSearch(query, numbers::searchForms)`. |
| `NoticeCard(text, action, onAction, dismissLabel, onDismiss, modifier)` | Aviso discreto en una lista | No es un diálogo. Toda la tarjeta lleva a la acción; la cruz lo descarta. |
| `UndoHost(spec, onDone)`, `LocalUndo`, `UndoSpec` | Barra de Deshacer | Dura 10 s o lo que pida "Tiempo para actuar" del sistema. Va en la capa flotante de `GlassRoot`. |
| `SwitchRow(title, checked, onChange, modifier, subtitle)` | Fila con interruptor | TalkBack la lee como una sola cosa, diciendo qué activa. Usar en vez de `SettingRow` + `GlassSwitch`. |
| `DangerButton(text, onClick, modifier, icon, enabled)` | Acción destructiva | Ver la excepción de abajo. |

**Listas largas:** filas planas y cristal solo en contenedores (barra, búsqueda, avisos, diálogos).
Medido en un Samsung a 120 Hz con 520 contactos inventados, con la versión optimizada: de 1,1 a
2,5 % de cuadros con tirones al desplazar, según el fondo, y de 0,6 a 1,9 % en el índice lateral.
No hicieron falta los modos livianos de Calc.

La burbuja del índice queda siempre armada y solo cambia su opacidad: crear una superficie de
cristal en cada toque daba 10 % de tirones y picos de 36 ms.

## Excepción local: una sola ventana emergente

`ui/glass/Dialog.kt` suma `GlassWindow` y `WindowTitle`, y `ActionSheetHost` y `PromptHost` los usan: los tres hosts comparten velo, tarjeta centrada, animación y desplazamiento. Es la primera diferencia de `ui/glass` con Calendar y Phone, que siguen con el menú abajo; `UiIcons` suma `ExpandMore`. Para elegir una opción se usa `ui/components/Dropdown.kt`: flecha hacia abajo, ventana con la opción marcada y Cancelar al final.

## Excepción local: el rojo de las acciones destructivas

`GlassButton(danger = true)` de `ui/glass` usa `danger` translúcido con texto blanco, con poco
contraste en tema claro. Sin tocar `ui/glass`, Contactos usa `DangerButton` con `DangerFill`
(`#9F1818`, opaco) en todos sus botones destructivos, y `dangerText` (`#B3261E` en claro) para el
texto de error. Los menús no usan `SheetAction(danger = true)`, cuyo texto `#E5484D` tampoco llega en
claro: el botón rojo está en el diálogo que sigue.

Contraste de texto blanco contra el píxel más claro del fondo del botón, medido sobre capturas del
emulador en los 4 fondos o estilos × claro y oscuro, en la pantalla y en el diálogo: **peor caso
5,37:1** (brillo superior en claro con fondo liso o de énfasis), el resto entre 7,35:1 y 8,02:1.

**Pendiente en Calendar:** tiene el mismo `danger` translúcido y hay que corregirlo allá.

## Accesibilidad: lo que viene de `ui/glass`

- `GlassChip` mide 40 dp visibles, pero su zona táctil real es de **48,0 dp**: en un chip con el nodo
  en y=475..600, tocar responde de y=475 a y=600 y no en 474 ni 601 (126 px a 2,625 de densidad).
  Es la extensión automática de Compose; no hizo falta ampliar nada. Si hay otro elemento pegado,
  esa extensión se reparte: dejar 8 dp entre chips.
- Un `GlassTextField` con texto no puede fundir su rótulo sin tocar `ui/glass`: rótulo y valor son
  dos paradas de TalkBack. Vacío, la ayuda ya es el nombre y es una sola parada.

## Sincronización con el servidor

Vive solo en esta app; phone y messages leen el proveedor del sistema.

- **Cliente propio**, copiado de Calendar (`core/dav`, `ServerUrl`, `LoginGuard`, `RetryPolicy`, `CredentialStore`). OkHttp no sale de `core`.
- **Una cuenta de Android por libreta.** El autenticador no autentica (la contraseña está en `CredentialStore`) y el sincronizador solo pide una sincronización a WorkManager; sirven para que Android acepte los contactos, marque los cambios de cualquier app y ofrezca la cuenta.
- **Ficha:** `SOURCE_ID` es la URL, `SYNC1` el ETag, `SYNC2` el UID y `SYNC4` la vCard del servidor sin foto.
- **`core/sync/ContactSyncPlan`** decide qué hacer sin tocar nada (función pura, con tests): nada se pisa; el borrado masivo se frena antes de aplicar nada.
- **`VCards.update`** reescribe solo lo que cambió, categoría por categoría, sobre la vCard del servidor.
- **`core/tls/ServerTrust`**: delega en el verificador del sistema y agrega huella o CA; el nombre lo verifica siempre OkHttp. No existe "confiar en todo".
- Lo que el usuario tiene que resolver (frenos, conflictos, contraseña) aparece como aviso en la lista y en la pantalla del servidor: sin notificaciones, para no pedir ese permiso.

## Lecciones para las próximas apps

Errores que aparecieron acá, con su solución. Varios venían de código copiado o se verían igual en
phone y messages.

| Problema | Por qué pasaba | Solución |
|---|---|---|
| **Teclado y formularios:** con un campo enfocado, el formulario quedaba aplastado. | `WindowInsets.safeDrawing` ya incluye el teclado; sumar `imePadding()` lo descuenta dos veces. | Usar solo el relleno de `safeDrawing`. Revisar en Calendar: `EventEditScreen`, `AccountScreen` y `CalendarManage` usan `imePadding`. |
| **Teclado al salir de una lista:** quedaba abierto, con el cursor flotando sobre la pantalla siguiente. | Las pestañas siguen creadas debajo y el campo no pierde el foco. | `LocalFocusManager.clearFocus()` antes de navegar. |
| **Ruedas de fecha:** escribir "5" en el día ponía 6; escribir un año no hacía nada. | El número escrito se tomaba como posición de la rueda. Es el mismo error que tuvieron los minutos de Calendar. | Pasar `typed` a `WheelPicker` en toda rueda cuya etiqueta no sea su posición. |
| **Grupos del sistema al guardar:** se perdía "Mis contactos" de Google. | Reescribir las etiquetas borraba todas las pertenencias a grupos. | Borrar solo las de grupos editables (`SYSTEM_ID` nulo, no solo lectura, no `AUTO_ADD`). |
| **Datos que la app no muestra:** se perdía un segundo apodo o el departamento de la empresa. | Guardar borra y reescribe los tipos que maneja la app. | Apodo, empresa y nota solo se reescriben si cambiaron. Tipos ajenos nunca se tocan. |
| **Unir no borraba:** la ficha "borrada" seguía oculta dentro del contacto unido. | Al quedar con el mismo nombre y número, Android junta las fichas y el id de contacto viejo deja de existir, así que borrar por ese id no hace nada. | Borrar por **ficha**, con los ids tomados antes de escribir. Verificar en la base, no en la pantalla: la pantalla mostraba el resultado correcto. |
| **Fotos al unir:** se perdía la foto si solo la tenía el segundo contacto. | `load()` no lee fotos, así que la vista previa no la tenía. | `loadFull()` para vistas previas y respaldos, y volver a tomarla de los originales al unir. |
| **Foto grande:** puede romper el guardado. | Un blob grande no entra en una transacción de la agenda. | Validar que sea una imagen y achicarla a 720 px en JPEG antes de guardar (`VcfFiles.normalizePhoto`). |
| **Nombres para TalkBack:** botones sin nombre, interruptores que no decían qué activan, letras del índice anunciadas como botón que no hacían nada, campos con texto sin decir qué campo son. | Con `null` de descripción o controles sueltos, TalkBack no tiene qué decir; `role = Button` no agrega la acción. | `SwitchRow`; nombre en cada ícono o `clearAndSetSemantics` si la fila ya es el botón; `onClick` en la semántica; `contentDescription` en campos con texto. |
| **vCard en Android 12:** todos los archivos habrían dado "inválido". | `InputStream.readNBytes` existe recién desde la API 33, y el error quedaba atrapado por el manejo de fallas. | Lectura manual con un tope. Correr `lintDebug` siempre: lo detectó él, ninguna prueba en Android 15 lo hubiera visto. |
| **Texto de `.vcf` viejos:** "Muñoz" salía roto. | ez-vcard lee UTF-8 y su `CHARSET` solo aplica a quoted-printable. | Si no es UTF-8 válido, leer como Windows-1252 (`VCards.decode`). |
| **Clases faltantes de ez-vcard:** `NoClassDefFoundError` de FreeMarker. | La clase `VCard` lo menciona y la JVM lo exige al cargarla. | `testRuntimeOnly` de FreeMarker y `-dontwarn` en R8; usar `VCardWriter` directo en vez de la fachada `Ezvcard`. |
| **Aviso "directo al ajuste":** no se movía. | Se pedía traer a la vista el título, que ya estaba visible. | `BringIntoViewRequester` sobre el campo mismo. |
| **Datos de prueba mezclados:** un contacto de prueba puede juntarse con uno real. | Android agrega fichas por nombre y número. | Marcar la ficha al crearla (`SOURCE_ID`) y borrar por esa marca, nunca por nombre, etiqueta ni contacto. |

| **CA importada vencida:** se aceptaba. | El verificador de Java no revisa la vigencia de sus autoridades de confianza. | Filtrar las vencidas al armar el almacén (`ServerTrust.parsePem`). |
| **Rechazo de certificado como "error de red":** confundía justo al configurar. | Con IPv4 e IPv6, OkHttp informa el último intento; el error de TLS queda en los anteriores. | Buscar `SSLException` en `suppressed` y `cause` (`DavClient.sslCause`). |
| **Resultado fuera de la pantalla:** "Probar conexión" parecía no hacer nada. | El mensaje quedaba debajo de los botones. | `BringIntoViewRequester` sobre el mensaje, y `liveRegion` para TalkBack. |

### Para probar

- Nunca capturas ni pruebas que escriban en un celu real: emulador con datos inventados.
- `adb input` no le llega a TalkBack. Los toques de `adb emu event mouse x y 0 1|0` sí, y con ellos funciona deslizar para recorrer y el doble toque.
- Una imagen `default` del emulador no trae TalkBack; `google_apis_playstore` sí.
- En `uiautomator dump`, un chip elegido aparece como `checked`, no `selected`, y `bounds` puede incluir la zona táctil extendida de Compose: para saber la real, tocar y ver hasta dónde responde.
- Para probar lo que devuelve "elegir", hace falta otra app sin permiso de contactos: desde la misma app no se ve nada.

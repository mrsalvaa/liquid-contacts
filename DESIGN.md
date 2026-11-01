# Diseño: qué reutiliza Liquid Contacts de la suite

SPDX-License-Identifier: GPL-3.0-or-later

> Borrador del paso 2: se completa en el paso 4 (ajustes y documentación).

## Origen de `ui/glass`

`app/src/main/java/dev/liquidcontacts/ui/glass` es una copia **sin cambios** de
`app/src/main/java/dev/liquidcalendar/ui/glass` de Liquid Calendar, commit `25ca0d6`
(`Components.kt`, `Controls.kt`, `Dialog.kt`, `Theme.kt`, `UiIcons.kt`). Solo cambian el paquete y
el encabezado SPDX. De ese mismo commit salen `ui/screens/AppearanceCard.kt`,
`ui/screens/Common.kt`, `util/AppIcons.kt`, `util/Strings.kt`, `util/Display.kt`,
`data/Stores.kt` y los textos de `res/values*/strings_glass.xml`.

## Componentes propios (`ui/components/ContactComponents.kt`)

Escritos en el estilo de `ui/glass`, para copiar a phone y messages:

| Componente | Qué es |
|---|---|
| `ContactAvatar` | Foto o iniciales sobre uno de los 9 tonos de énfasis apagados (`avatarColor`), siempre el mismo para el mismo nombre (`core/avatar/Avatars`). |
| `ContactRow` | Fila **plana** (sin cristal propio), 64 dp de alto mínimo, toda la fila es el botón. |
| `LetterHeader` | Letra de cada grupo, encabezado para TalkBack. |
| `AlphabetIndex` | Índice lateral: tocar o arrastrar salta a la letra, con burbuja de cristal siempre armada (solo cambia su opacidad). Si no entran todas las letras, muestra algunas con puntos. |
| `SearchField` | Píldora de cristal con lupa y botón de borrar de 48 dp. |
| `NoticeCard` | Aviso discreto dentro de la lista, con acción y cruz para descartar. |

Regla de las listas largas: filas planas y cristal solo en contenedores (barra, búsqueda, avisos,
diálogos). Medido en un Samsung a 120 Hz con 520 contactos inventados: de 1,1 a 2,5 % de cuadros
con tirones al desplazar, según el fondo, sin los modos livianos de Calc.

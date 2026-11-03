<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Registro de cambios

[English](CHANGELOG.md) | **Español**

## Sin publicar

### Cambios
- Selección múltiple: arriba, los seleccionados con Todos y Ninguno; abajo, la píldora cambia a las opciones y el botón de contacto nuevo se va. Respeta el estilo Liquid Glass o Sólido y sigue activa aunque no quede nadie seleccionado.
- "Guardar en" se elige en una ventana. El servidor figura siempre: si todavía no hay una libreta activada, la fila lleva a activarla.
- Ya no se ofrece guardar en cuentas de apps de mensajería como WhatsApp o Signal.
- La foto se acomoda en 1:1 antes de guardarla y se abre en una ventana sobre la pantalla actual.

### Arreglado
- Calidad de las fotos: el detalle y la lista mostraban la miniatura de 96 px estirada; ahora usan la foto grande. Se guarda con menos compresión.

## 1.1.0

### Novedades
- Mantener apretado un contacto abre sus opciones: llamar, mensaje, favorito, editar, etiqueta, compartir, seleccionar y borrar.
- Mantener apretada la foto activa la selección múltiple, con favorito, etiqueta, compartir, unir y borrar.
- Tocar la foto la abre a pantalla completa (también desde el detalle).
- Papelera: lo que borrás desde la app queda 30 días y se puede restaurar. Al borrar aparece Deshacer.
- Apodo del servidor, que se muestra en lugar del nombre técnico de la cuenta.

### Cambios
- "Guardar en" al crear un contacto es un desplegable en orden alfabético, con las libretas del servidor y su apodo.
- El servidor CardDAV está primero en Ajustes.
- Ya no se ofrece guardar en libretas de solo lectura ni en cuentas que no suben cambios.

## 1.0.2

### Cambios
- Íconos de la app: el cuerpo de la persona ahora es entero y queda detrás del borde del círculo o la ficha, en vez de un medio círculo con base plana.

## 1.0.1

### Novedades
- Lista de contactos con búsqueda (sin importar tildes ni eñes, también por número en cualquier formato), orden alfabético en español, índice lateral y favoritos.
- Detalle del contacto con llamar, mensaje, email, mapa y sitio web por los intents estándar de Android.
- Crear, editar y borrar contactos: varios números, emails y direcciones, cumpleaños y otras fechas (con o sin año), notas, sitios web, etiquetas y foto (con el selector de fotos del sistema).
- Etiquetas: crear, cambiar el nombre y borrar.
- Duplicados por confianza (mismo nombre, o un número o email compartido con nombres distintos) con vista previa de la unión, donde se puede sacar a cualquier contacto; nada se une sin confirmar.
- País por defecto (de la SIM la primera vez) y código de área por defecto, con un aviso en la lista cuando hay números sin código de área.
- Unir se puede deshacer durante 10 segundos, con un respaldo .vcf opcional antes, también al unir desde una importación; los contactos con fichas en varias cuentas muestran la cuenta de cada ficha al editar.
- Importar archivos .vcf (vCard 2.1, 3.0 y 4.0): vista previa, elegir la cuenta, y los contactos que ya tenés se omiten o se unen solo si lo pedís. Límites de tamaño, cantidad y foto; un archivo roto da un mensaje claro. Las fotos que vienen como enlace nunca se descargan.
- Exportar y compartir .vcf: todos, seleccionados o por etiqueta, con o sin fotos, en vCard 3.0 o 4.0. Compartir usa un permiso temporal.
- Otras apps pueden pedir ver, agregar, editar o elegir un contacto y abrir un .vcf. Agregar y editar solo rellenan el formulario: nada se guarda hasta que tocás guardar. Elegir devuelve solo lo que elegís.
- Servidor CardDAV opcional, con cliente propio: una cuenta de Android por libreta, primera sincronización que solo baja (con resumen y respaldo), conflictos que resolvés vos, protección contra borrado masivo en las dos direcciones, huella de certificado o CA propia, http solo a red local o VPN con advertencia. Sin servidor no hay conexiones.
- Los campos que la app no muestra vuelven al servidor como vinieron al editar un contacto.
- Ajustes de apariencia compartidos con el resto de la suite y cinco íconos de la app.

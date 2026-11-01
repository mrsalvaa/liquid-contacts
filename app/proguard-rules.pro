# SPDX-License-Identifier: GPL-3.0-or-later
# kotlinx.serialization trae sus propias reglas; mantenemos los modelos por claridad.
-keep,includedescriptorclasses class dev.liquidcontacts.data.**$$serializer { *; }
-keepclassmembers class dev.liquidcontacts.data.** {
    *** Companion;
}

# ez-vcard: solo se usa el formato de texto. Lo que menciona de HTML (jsoup), plantillas
# (freemarker) y JSON (jackson) no se incluye en el APK ni se llama nunca.
-dontwarn org.jsoup.**
-dontwarn freemarker.**
-dontwarn com.fasterxml.jackson.**

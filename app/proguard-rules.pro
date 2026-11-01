# SPDX-License-Identifier: GPL-3.0-or-later
# kotlinx.serialization trae sus propias reglas; mantenemos los modelos por claridad.
-keep,includedescriptorclasses class dev.liquidcontacts.data.**$$serializer { *; }
-keepclassmembers class dev.liquidcontacts.data.** {
    *** Companion;
}

// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Lógica sin Android: números, búsqueda, orden, avatares, duplicados y modelos. Se prueba en la JVM
// y se puede copiar tal cual a phone y messages.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.libphonenumber)
    // Solo vCard de texto (2.1, 3.0 y 4.0): fuera lo que ez-vcard usa para HTML, plantillas y JSON.
    implementation(libs.ezvcard) {
        exclude(group = "org.jsoup")
        exclude(group = "org.freemarker")
        exclude(group = "com.fasterxml.jackson.core")
    }
    testImplementation(libs.kotlin.test)
    // Solo para los tests en la JVM: la clase VCard menciona excepciones de freemarker y la JVM las
    // exige al cargarla. Android la verifica de forma perezosa y nunca llega a usarlas (ver las
    // reglas -dontwarn de app/proguard-rules.pro), así que freemarker no va en el APK.
    testRuntimeOnly(libs.freemarker)
}

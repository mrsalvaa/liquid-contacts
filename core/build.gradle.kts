// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Lógica sin Android: números, búsqueda, orden, avatares, duplicados, modelos, vCard y CardDAV. Se prueba en la JVM
// y se puede copiar tal cual a phone y messages.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val integrationTest = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get(), configurations.implementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("integrationTest") {
    description = "Pruebas de integración contra el Radicale local de prueba (127.0.0.1:5232)."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.libphonenumber)
    implementation(libs.okhttp)
    // XmlPullParser: Android ya lo trae; en la JVM (tests) lo da kxml2.
    compileOnly(libs.kxml2)
    // Solo vCard de texto (2.1, 3.0 y 4.0): fuera lo que ez-vcard usa para HTML, plantillas y JSON.
    implementation(libs.ezvcard) {
        exclude(group = "org.jsoup")
        exclude(group = "org.freemarker")
        exclude(group = "com.fasterxml.jackson.core")
    }
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kxml2)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // Solo para los tests en la JVM: la clase VCard menciona excepciones de freemarker y la JVM las
    // exige al cargarla. Android la verifica de forma perezosa y nunca llega a usarlas (ver las
    // reglas -dontwarn de app/proguard-rules.pro), así que freemarker no va en el APK.
    testRuntimeOnly(libs.freemarker)
}

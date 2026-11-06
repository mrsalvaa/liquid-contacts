// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Clave de release fuera del repo. Ruta configurable con -PsigningProps=... o la variable
// de entorno LIQUID_CONTACTS_SIGNING; si no existe, la release se firma con la clave de debug.
val signingProps = (findProperty("signingProps") as String?)
    ?: System.getenv("LIQUID_CONTACTS_SIGNING")
    ?: "${System.getProperty("user.home")}/.android-keys/liquid-contacts.properties"
val releaseSigning = file(signingProps).takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}

android {
    namespace = "dev.liquidcontacts"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.liquidcontacts"
        minSdk = 31
        targetSdk = 35
        versionCode = 11
        versionName = "1.3.0"
        resValue("string", "account_type", "dev.liquidcontacts.carddav")
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "account_type", "dev.liquidcontacts.debug.carddav")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // Como la release (optimizada, sin depuración) pero instalable junto a ella como
        // dev.liquidcontacts.debug: para probar en el celu con el rendimiento real.
        create("profile") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-profile"
            resValue("string", "account_type", "dev.liquidcontacts.debug.carddav")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Herramientas que solo existen en debug y profile (p. ej. la agenda de prueba con datos
    // inventados): la release no las compila.
    sourceSets["profile"].java.srcDir("src/debug/java")
    sourceSets["profile"].manifest.srcFile("src/debug/AndroidManifest.xml")

    lint {
        abortOnError = true
        checkDependencies = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    // De ez-vcard solo se usa el formato de texto: fuera la plantilla y las imágenes de hCard.
    packaging {
        resources.excludes += "ezvcard/io/html/**"
    }

    // F-Droid: sin el bloque de dependencias firmado por Google dentro del APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.backdrop)
    implementation(libs.work.runtime)

    testImplementation(libs.kotlin.test)
}

// Sin perfiles ART dentro del APK (assets/dexopt): sin profileinstaller no se usan fuera de
// Google Play, y F-Droid los desaconseja porque pueden hacer que el build no sea reproducible.
tasks.matching { it.name.contains("ArtProfile") }.configureEach { enabled = false }

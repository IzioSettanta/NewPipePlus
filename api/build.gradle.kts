/*
 * SPDX-FileCopyrightText: 2026
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.android.application)
}

android {
    compileSdk = 36
    namespace = "org.schabi.newpipe.api"

    defaultConfig {
        applicationId = "org.schabi.newpipe.api"
        minSdk = 21
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    implementation(libs.newpipe.extractor)

    implementation(libs.reactivex.rxjava)
    implementation(libs.reactivex.rxandroid)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)

    implementation(libs.squareup.okhttp)
}

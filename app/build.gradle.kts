plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kbtv.caster"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.kbtv.caster"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Feature flags (VERIFICATION.md §8.3): -Pkbtv.flags.<name>=true; default false == pre-change behavior
        val flagPlaybackPlan = (project.findProperty("kbtv.flags.playbackPlan") as? String)?.toBooleanStrictOrNull() ?: false
        buildConfigField("boolean", "FLAG_PLAYBACK_PLAN_ROUTING", flagPlaybackPlan.toString())
        val flagAirplay = (project.findProperty("kbtv.flags.airplay") as? String)?.toBooleanStrictOrNull() ?: false
        buildConfigField("boolean", "FLAG_AIRPLAY_PROTOCOL", flagAirplay.toString())

        // UxPlay mirror kernel (Phase 2): real TV is arm64, TV emulator x86 (32-bit).
        // -Pkbtv.build.abi=<abi> builds a single-ABI APK for slow network-adb installs.
        val singleAbi = project.findProperty("kbtv.build.abi") as? String
        ndk { abiFilters += (singleAbi?.let { listOf(it) } ?: listOf("arm64-v8a", "x86_64", "x86")) }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs = listOf("-Xallow-any-scripts-in-source")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        // oboe audio library is consumed via prefab from its AAR
        prefab = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
        checkDependencies = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // Packaging options to exclude conflicting files
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Activity & Fragment
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Network - for HTTP server
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Low-latency audio output for the AirPlay mirror kernel (prefab)
    implementation("com.google.oboe:oboe:1.9.3")

    // ExoPlayer for media playback
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")

    // Cling UPnP library - vendored source (org.fourthline.cling); transitive deps from libs/.
    // javax.annotation (CDDL) is compile-only: its sole referencer, vendored
    // ManagedUpnpServiceConfiguration, is never instantiated — keeping it out
    // of the APK avoids shipping CDDL-licensed code (docs/LICENSE-SWITCH.md §3).
    implementation(fileTree("libs") {
        include("*.jar")
        exclude("javax.annotation*.jar")
    })
    compileOnly(fileTree("libs") { include("javax.annotation*.jar") })

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // AndroidX Test
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

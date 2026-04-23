import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("kotlin-parcelize")
    id("androidx.room")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization") version libs.versions.kotlin
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.play.publisher)
}

room {
    schemaDirectory("$projectDir/schemas")
}

val keystoreProperties = Properties()
rootProject.file("keystore.properties").let {
    if (it.exists()) {
        keystoreProperties.load(FileInputStream(it))
    }
}

android {
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ismartcoding.plain"
        minSdk = 28
        targetSdk = 36

        val abiFilterList = if (hasProperty("abiFilters")) property("abiFilters").toString().split(';') else listOf()
        val singleAbiNum =
            when (abiFilterList.takeIf { it.size == 1 }?.first()) {
                "armeabi-v7a" -> 2
                "arm64-v8a" -> 1
                else -> 0
            }

        val vCode = 556
        versionCode = vCode - singleAbiNum
        versionName = "3.1.2"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += abiFilterList.ifEmpty {
                listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
            storeFile = file(keystoreProperties.getProperty("storeFile", "release.jks"))
            storePassword = keystoreProperties.getProperty("storePassword")
        }
    }


    // https://stackoverflow.com/questions/52731670/android-app-bundle-with-in-app-locale-change/52733674#52733674
    bundle {
        language {
            enableSplit = false
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isShrinkResources = false
            isMinifyEnabled = false
            isDebuggable = true
            ndk {
                debugSymbolLevel = "NONE"
            }
            buildConfigField("String", "CHANNEL", "\"GITHUB\"")
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"))
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isShrinkResources = true
            isMinifyEnabled = true
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"))
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("github") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"GITHUB\"")
        }
        create("google") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"GOOGLE\"")
        }
        create("fdroid") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"FDROID\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        jniLibs {
            // Required so the bundled cloudflared binary is extracted to the
            // app's nativeLibraryDir with execute permission instead of being
            // memory-mapped from the APK (which can't be ProcessBuilder-exec'd).
            useLegacyPackaging = true
            excludes += listOf("META-INF/*")
            keepDebugSymbols += listOf("**/*.so")
        }
        resources {
            excludes += listOf("META-INF/*")
        }
    }
    namespace = "com.ismartcoding.plain"

    // Bundle cloudflared as a native library per ABI so it can be executed at runtime.
    // Use a plain File path (not a Provider) — AGP 9 rejects Providers in the SourceSet API.
    // Task ordering is wired manually in androidComponents { onVariants { ... } } below.
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(file("$buildDir/generated/cloudflared/jniLibs"))
        }
    }
}

// ---------- Cloudflare Tunnel binary download ----------
// Cloudflared releases (Linux ARM/ARM64 are usable on Android — same Linux kernel ABI).
// We rename the binary to libcloudflared.so so Android packages it as an executable
// native library inside applicationInfo.nativeLibraryDir (the only place modern Android
// allows executing app-shipped binaries from).
val cloudflaredVersion = providers.gradleProperty("cloudflaredVersion").orElse("2024.10.1")
val cloudflaredAbiToAsset = mapOf(
    "arm64-v8a" to "cloudflared-linux-arm64",
    "armeabi-v7a" to "cloudflared-linux-arm",
)

val downloadCloudflared = tasks.register("downloadCloudflared") {
    val outRoot = File("$buildDir/generated/cloudflared/jniLibs")
    outputs.dir(outRoot)
    inputs.property("version", cloudflaredVersion)
    doLast {
        val ver = cloudflaredVersion.get()
        cloudflaredAbiToAsset.forEach { (abi, asset) ->
            val abiDir = File(outRoot, abi).apply { mkdirs() }
            val target = File(abiDir, "libcloudflared.so")
            if (target.exists() && target.length() > 1_000_000) {
                logger.lifecycle("cloudflared already present for $abi (${target.length()} bytes)")
                return@forEach
            }
            val url = "https://github.com/cloudflare/cloudflared/releases/download/$ver/$asset"
            logger.lifecycle("Downloading cloudflared $ver for $abi from $url")
            try {
                URI(url).toURL().openStream().use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                target.setExecutable(true)
            } catch (e: Exception) {
                logger.warn("WARNING: failed to download cloudflared for $abi: ${e.message}. Tunnel feature will be disabled in this build.")
                // Create an empty placeholder so Gradle does not fail the merge step.
                if (!target.exists()) target.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        // Ensure cloudflared is downloaded before native libs are merged.
        afterEvaluate {
            tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
                .configureEach { dependsOn(downloadCloudflared) }
            tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }
                .configureEach { dependsOn(downloadCloudflared) }
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-nowarn")
    }
}
play {
    serviceAccountCredentials.set(file("play-config.json"))
    track.set("internal")
    defaultToAppBundles.set(true)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    implementation(project(":lib"))

    implementation(platform(libs.compose.bom))

    // https://github.com/google/accompanist/releases
    implementation(libs.compose.lifecycle.runtime)
    implementation(libs.compose.activity)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.material3)
    // https://developer.android.com/jetpack/androidx/releases/navigation
    implementation(libs.compose.navigation)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.dash)
    implementation(libs.media3.hls)

    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.exifinterface)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // https://developer.android.com/jetpack/androidx/releases/room
    implementation(libs.room.runtime)
//    annotationProcessor(libs.room.compiler)
    ksp(libs.room.compiler)

    // coil: https://coil-kt.github.io/coil/changelog/
    implementation(libs.coil)
    implementation(libs.coil.video)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.zxing.core)

    implementation(libs.androidx.work.runtime.ktx)

    // https://developer.android.com/jetpack/androidx/releases/datastore
    implementation(libs.androidx.datastore.preferences)

    debugImplementation(libs.leakcanary.android)
    implementation(kotlin("stdlib", libs.versions.kotlin.get()))

    // For cryptography (Ed25519 support on all Android versions)
    implementation(libs.tink.android)

    // WebRTC for screen mirroring
    implementation(libs.webrtc.sdk.android)

    // AI Image Search: MediaPipe is open source (included for all flavors).
    // LiteRT is excluded from fdroid to pass F-Droid FOSS checks.
    implementation(libs.mediapipe.tasks.vision)
    "githubImplementation"(libs.litert)
    "googleImplementation"(libs.litert)
}

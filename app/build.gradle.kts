import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 provides built-in Kotlin, so the standalone kotlin-android plugin is not applied.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * Release signing, as Electric Eel does it: keystore.properties at the repository root (git
 * ignores it) with storeFile (relative to the root), storePassword, keyAlias and keyPassword.
 * Without it, release builds are signed with the debug key and can only update debug-signed
 * installs.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKey = !keystoreProperties.isEmpty

android {
    namespace = "org.cygnusx1.qlauncher"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "org.cygnusx1.qlauncher"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
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

    // Lists the res/values-* languages for Android 13+ (the default one is in res/resources.properties).
    androidResources {
        generateLocaleConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xcontext-parameters",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.tv.material)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.tvprovider)
    implementation(libs.materialkolor.utilities)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.video)
    implementation(libs.lottie.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.zip4j)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}

// Build gate: every Kotlin source carries the GPLv3 header.
val checkLicenceHeaders by tasks.registering {
    group = "verification"
    description = "Fails when a Kotlin source file lacks the GPLv3 licence header."
    val sources = fileTree("src") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val missing = sources.files.filter { f -> !f.useLines { lines -> lines.take(8).any { "GNU General Public License" in it } } }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing GPLv3 header:\n" + missing.joinToString("\n") { it.relativeTo(projectDir).path })
        }
    }
}
tasks.named("check") { dependsOn(checkLicenceHeaders) }

/**
 * Copies the release APK to `build/outputs/release/QLauncher_<versionName>.apk`, the name the
 * update feed (`updates/qlauncher.json`) points at on the GitHub release.
 */
abstract class ReleaseApkTask : DefaultTask() {
    @get:InputFiles
    abstract val apkFolder: DirectoryProperty

    @get:Internal
    abstract val loader: Property<BuiltArtifactsLoader>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun copy() {
        val built = loader.get().load(apkFolder.get()) ?: throw GradleException("No release APK in ${apkFolder.get()}")
        File(built.elements.single().outputFile).copyTo(output.get().asFile, overwrite = true)
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val name = "QLauncher_${android.defaultConfig.versionName}.apk"
        val releaseApk = tasks.register<ReleaseApkTask>("releaseApk") {
            group = "build"
            description = "Copies the release APK to build/outputs/release/$name."
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
            output.set(layout.buildDirectory.file("outputs/release/$name"))
        }
        tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(releaseApk) }
    }
}

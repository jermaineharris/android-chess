import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.huttsmedia.chess"
    compileSdk = 36

    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.huttsmedia.chess"
        minSdk = 30
        versionCode = 8
        versionName = "2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

val rustDir = rootProject.file("rust")
val jniOutDir = layout.projectDirectory.dir("src/main/jniLibs")

fun sdkDir(): File {
    val local = rootProject.file("local.properties")
    val fromProps = if (local.exists()) {
        local.readLines().firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("sdk.dir=")
    } else {
        null
    }
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    return file(fromProps ?: fromEnv ?: error("Set sdk.dir in local.properties or ANDROID_HOME"))
}

tasks.register<Exec>("cargoNdkBuild") {
    workingDir = rustDir
    val ndkDir = sdkDir().resolve("ndk/29.0.14206865").absolutePath
    environment("ANDROID_NDK_HOME", ndkDir)
    environment("ANDROID_NDK_ROOT", ndkDir)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", jniOutDir.asFile.absolutePath,
        "build", "--release"
    )
    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    outputs.dir(jniOutDir.asFile)
}

tasks.register("fetchStockfish") {
    val cacheDir = File(System.getProperty("user.home"), ".cache/hutts-chess/stockfish")
    val src = File(cacheDir, "stockfish-android-armv8")
    val destDir = jniOutDir.dir("arm64-v8a").asFile
    val dest = File(destDir, "libstockfish.so")
    val licenseDest = layout.projectDirectory.dir("src/main/assets/stockfish").asFile
    outputs.file(dest)
    doLast {
        destDir.mkdirs()
        licenseDest.mkdirs()
        if (!src.isFile) {
            throw GradleException(
                "Stockfish binary missing at $src — download sf_17.1 stockfish-android-armv8.tar from official-stockfish/Stockfish releases into ~/.cache/hutts-chess/"
            )
        }
        src.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
        File(cacheDir, "Copying.txt").takeIf { it.isFile }?.copyTo(File(licenseDest, "Copying.txt"), overwrite = true)
        File(cacheDir, "AUTHORS").takeIf { it.isFile }?.copyTo(File(licenseDest, "AUTHORS"), overwrite = true)
    }
}

tasks.named("fetchStockfish") {
    dependsOn("cargoNdkBuild")
}

tasks.named("preBuild").configure {
    dependsOn("fetchStockfish")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20250107")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

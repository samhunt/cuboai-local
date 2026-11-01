import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

val pythonLibModules = listOf(
    "cuboai_pure.py",
    "cuboai_messages.py",
    "cuboai_session.py",
    "cuboai_transport_py.py",
    "cuboai_mpegts.py",
    "cuboai_pts.py",
    "cuboai_stream_video.py",
)

val pythonLibDir = layout.buildDirectory.dir("python-lib")
val tutkRef = providers.gradleProperty("cuboai.tutkRef").orElse("main")

val fetchPythonLib by tasks.registering {
    description = "Download LAN protocol modules at build time. They are not part of this repo."
    val modules = pythonLibModules
    val ref = tutkRef
    val destDir = pythonLibDir
    outputs.dir(destDir)
    doLast {
        val dest = destDir.get().asFile
        dest.mkdirs()
        val archive = dest.resolveSibling("tutk.tar.gz")
        val url = "https://codeload.github.com/Fredde87/cuboai-tutk/tar.gz/${ref.get()}"
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        connection.getInputStream().use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }
        copy {
            from(tarTree(archive))
            modules.forEach { include("**/$it") }
            eachFile { relativePath = RelativePath(true, name) }
            includeEmptyDirs = false
            into(dest)
        }
        archive.delete()
        val missing = modules.filterNot { dest.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            error("LAN library download from $url was missing: $missing")
        }
    }
}

val syncPythonLib by tasks.registering(Copy::class) {
    description = "Copy downloaded LAN protocol modules into the Chaquopy source set."
    dependsOn(fetchPythonLib)
    from(pythonLibDir)
    include(*pythonLibModules.toTypedArray())
    into(layout.projectDirectory.dir("src/main/python"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

val syncLicense by tasks.registering(Copy::class) {
    description = "Bundle MIT LICENSE and third-party NOTICE into the APK."
    from(rootProject.projectDir.resolve("LICENSE"))
    from(rootProject.projectDir.resolve("NOTICE"))
    into(layout.projectDirectory.dir("src/main/assets"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

afterEvaluate {
    tasks.named("preBuild").configure { dependsOn(syncPythonLib, syncLicense) }
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("PythonSources") }.configureEach {
        dependsOn(syncPythonLib)
    }
    tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }.configureEach {
        dependsOn(syncLicense)
    }
}

fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val versionNameFromEnv = envOrNull("VERSION_NAME")?.removePrefix("v") ?: "0.1.0-dev"
val versionCodeFromEnv = envOrNull("VERSION_CODE")?.toIntOrNull()
    ?: (envOrNull("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)

android {
    namespace = "com.cuboai.local"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cuboai.local"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeFromEnv
        versionName = versionNameFromEnv
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        val storePath = envOrNull("SIGNING_STORE_FILE")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = envOrNull("SIGNING_STORE_PASSWORD")
                    ?: error("SIGNING_STORE_PASSWORD is required when SIGNING_STORE_FILE is set")
                keyAlias = envOrNull("SIGNING_KEY_ALIAS")
                    ?: error("SIGNING_KEY_ALIAS is required when SIGNING_STORE_FILE is set")
                keyPassword = envOrNull("SIGNING_KEY_PASSWORD")
                    ?: error("SIGNING_KEY_PASSWORD is required when SIGNING_STORE_FILE is set")
            }
        } else {
            val keystorePropsFile = rootProject.file("keystore.properties")
            if (keystorePropsFile.exists()) {
                val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
                create("release") {
                    storeFile = rootProject.file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.14"
        pyc {
            src = true
            pip = false
            stdlib = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-datasource:1.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.amazonaws:aws-android-sdk-cognitoidentityprovider:2.81.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

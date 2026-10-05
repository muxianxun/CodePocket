plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.dsh.codepocket"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.codepocket"
        minSdk = 24
        // Deliberately 28, not 34: Android 10+ refuses to exec() files inside an app's own
        // data directory for apps targeting 29+, and a downloaded C/C++ toolchain (clang)
        // plus its output binary MUST be exec'ed. Apps targeting <= 28 keep the legacy
        // untrusted_app_25 SELinux domain.
        //
        // Proven by A/B test on the physical device (Android 17 / HyperOS, SELinux
        // Enforcing), same APK, same probe binary, only targetSdk differing:
        //   targetSdk 34 -> IOException: error=13, Permission denied
        //   targetSdk 28 -> exit code 0, output EXEC_FROM_APPDATA_OK
        // Cost: cannot be published on Google Play, and some legacy storage behaviour is
        // retained. Both acceptable here; it buys working C/C++ with no root, no Shizuku.
        targetSdk = 28
        versionCode = 4
        versionName = "0.4.0"

        ndk {
            // Default ships both targets: arm64-v8a for the real phone, x86_64 for the
            // MuMu emulator. Chaquopy downloads a separate CPython runtime per ABI, so a
            // single-ABI build is available for quick iteration:
            //   gradle assembleDebug -Pabis=x86_64
            val requested = (project.findProperty("abis") as String?)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
            abiFilters += (requested ?: listOf("arm64-v8a", "x86_64"))
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
        debug {
            isMinifyEnabled = false
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
    }

    testOptions {
        // Unit tests run on the JVM, where every android.* method is a stub that throws
        // ("Method i in android.util.Log not mocked") unless default values are returned.
        // Without this, any test whose code path calls Diag.log fails for the wrong reason.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        abortOnError = false
    }
}

chaquopy {
    defaultConfig {
        // Chaquopy 17 supports Python 3.10-3.14 on AGP 7.3-9.2.
        version = "3.13"
        // Build-time Python (used by Chaquopy's pip tooling); the CPython that ships in
        // the APK is downloaded per-ABI from Chaquopy's Maven repository.
        buildPython("C:\\Program Files\\Python314\\python.exe")
        pip {
            // Pure-stdlib for now; add("numpy") etc. here when needed.
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Java pipeline: Janino compiles .java -> .class, R8/D8 converts .class -> .dex, and
    // the system dalvikvm executes the dex. Only the last step spawns a process, and it is
    // a system binary, so none of this needs root or Shizuku.
    //
    // ECJ was tried first and rejected: both 3.46 and 3.18 reference JDK-only
    // javax.lang.model / javax.annotation.processing classes that Android does not ship
    // (verified on device with two ClassNotFoundExceptions). Janino is built for
    // embedding and has no such dependency.
    implementation("org.codehaus.janino:janino:3.1.12")
    implementation("com.android.tools:r8:9.4.28")

    // Termux packages are .deb archives whose payload is data.tar.xz, and java.util.zip
    // has no xz support. Pure-Java, works on Android.
    implementation("org.tukaani:xz:1.9")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

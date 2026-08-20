import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists()

if (hasReleaseKeystore) {
    keystorePropertiesFile.inputStream().use { input ->
        keystoreProperties.load(input)
    }
}

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { input ->
        localProperties.load(input)
    }
}

// SECURITY: this is a public repo, so the RPi's LAN IP must never be
// hardcoded/committed anywhere in source — it's read only from
// local.properties (gitignored) or the environment, same as NEXTTRAIN_API_KEY
// above.
val devServerUrl = localProperties.getProperty("NEXTTRAIN_DEV_SERVER_URL")
    ?: System.getenv("NEXTTRAIN_DEV_SERVER_URL")
    ?: ""

// The dev flavor's network-security-config needs that same host to permit
// cleartext HTTP to it, but a static checked-in XML would bake the IP into
// git history regardless of gitignore going forward — so it's regenerated
// here at configuration time instead, into a gitignored file.
// "0.0.0.0" is an inert placeholder for contributors without a configured
// dev server (matches no reachable host, so cleartext stays denied for them).
val devServerHost = devServerUrl.takeIf { it.isNotBlank() }
    ?.let { URI(it).host }
    ?: "0.0.0.0"
file("src/dev/res/xml").mkdirs()
file("src/dev/res/xml/network_security_config.xml").writeText(
    """
    <?xml version="1.0" encoding="utf-8"?>
    <network-security-config>
        <domain-config cleartextTrafficPermitted="true">
            <domain includeSubdomains="false">$devServerHost</domain>
        </domain-config>
    </network-security-config>
    """.trimIndent() + "\n"
)

android {
    namespace = "com.nexttrain"
    compileSdk = 36 // Android 16

    defaultConfig {
        applicationId = "com.nexttrain"
        minSdk = 31  // Android 12 (widget lock screen support)
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        // SECURITY: shared API secret read from local.properties (gitignored) or
        // a CI environment variable at build time only — never hardcoded, never
        // committed. See local.properties.example.
        val apiKey = localProperties.getProperty("NEXTTRAIN_API_KEY")
            ?: System.getenv("NEXTTRAIN_API_KEY")
            ?: ""
        buildConfigField("String", "NEXTTRAIN_API_KEY", "\"$apiKey\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                // SECURITY: signing material is sourced from local, untracked properties.
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"

            // Points at the standalone dev server container on the RPi (see
            // nexttrain-server/deploy-dev.sh), reached directly over LAN IP —
            // no Cloudflare tunnel. See devServerUrl above for where the
            // actual host comes from.
            buildConfigField("String", "SERVER_URL", "\"$devServerUrl\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "SERVER_URL", "\"https://nexttrain.pidatastudios.com.au\"")
        }
    }

    // Omit the Play Store dependency-metadata block: for sideloaded/local release
    // builds it's what makes ART/Play Protect try to phone home to verify the app
    // before installing the baseline profile, which fails offline with
    // INSTALL_BASELINE_PROFILE_FAILED.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Baseline profiles are a pure startup-speed optimisation, not a
    // correctness requirement — but on some devices/installers the
    // embedded assets/dexopt/baseline.prof triggers a hard install
    // failure (INSTALL_BASELINE_PROFILE_FAILED). This app is distributed
    // as a directly-sideloaded APK (GitHub release, not Google Play,
    // which is the only distribution channel where these profiles are
    // actually put to use), so the install-time risk outweighs the
    // startup-speed benefit — drop them from the packaged release APK by
    // disabling AGP's baseline-profile compile task outright (excluding
    // the packaged path doesn't work: assets/dexopt/** isn't routed
    // through the packaging.resources merge, it's added directly by AGP).
    androidComponents {
        onVariants { variant ->
            project.tasks.matching { task ->
                task.name == "compile${variant.name.replaceFirstChar { it.uppercase() }}ArtProfile"
            }.configureEach {
                enabled = false
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // Local fallback to let Android Studio run the release variant on-device.
                signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Networking — plain OkHttp + Gson (NextTrainApiClient), no Retrofit: every
    // call goes through the same shared httpGet()/getRaw() plumbing rather than
    // Retrofit's declarative service-interface pattern, so Retrofit itself
    // (and its Gson converter, which only exists to bridge Retrofit) were
    // unused dead weight. Gson is depended on directly since the app code
    // uses com.google.gson.Gson itself.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Preference
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Pull-to-refresh
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    testImplementation("junit:junit:4.13.2")
}

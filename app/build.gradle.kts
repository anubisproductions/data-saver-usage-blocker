import java.util.Properties

plugins {
    id("com.android.application")
}

// Signing credentials live in keystore.properties, which is gitignored. The build still
// works without it - it just produces an unsigned release, which is what CI or a fresh
// clone should do rather than failing.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.anubisproductions.datagate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anubisproductions.datagate"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "0.7"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    /*
     * Ship every language in the base APK instead of splitting by language.
     *
     * Play's default is to deliver only the language splits matching the device's *system*
     * locale. That silently breaks the in-app language switcher: a tester whose phone is in
     * English and who picks Urdu gets the RTL layout and Urdu dates, because the framework
     * applies the locale, but English strings, because values-ur was never downloaded.
     *
     * Measured, not assumed: the same versionCode side-loaded as a universal APK renders full
     * Urdu on a Samsung, while the Play-delivered bundle renders English on a Pakistani
     * tester's phone. See FINDINGS.md F16.
     *
     * The alternative - requesting the split at runtime through Play Core's
     * SplitInstallManager - would link a library that talks to Play from inside this process,
     * and Play Billing was measured to merge INTERNET (KNOWN_ISSUES.md #9). Twelve locales of
     * strings cost a few tens of kilobytes in a 1.3 MB app. Not a close call.
     */
    bundle {
        language {
            enableSplit = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("long", "SPLASH_HOLD_MS", "0L")
        }
        debug {
            /*
             * The splash lasts about 150 ms on a mid-range phone, and a screenshot round
             * trip takes longer than that, so it could never be captured or reviewed. This
             * variant pins it open instead.
             *
             * A separate applicationId matters as much as the hold: it installs alongside
             * the real app rather than replacing it, so checking the splash no longer costs
             * the user their rules, baselines and Usage-access grant. Reinstalling over the
             * release build wiped all three more than once before this existed.
             */
            applicationIdSuffix = ".splashtest"
            versionNameSuffix = "-splashtest"
            buildConfigField("long", "SPLASH_HOLD_MS", "0L")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}

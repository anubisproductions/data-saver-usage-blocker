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

    /*
     * Two distributions of the same app.
     *
     * IzzyOnDroid and F-Droid both scan what a build links against, and Play's in-app review
     * library is a proprietary blob - shipping it would earn the listing a NonFreeDep flag on
     * an app whose entire pitch is that you can audit it. The prompt is also pointless there:
     * outside the Play Store there are no ratings to leave.
     *
     * Splitting by flavour rather than by a runtime check keeps the library off the FOSS
     * variant's classpath altogether, so the claim survives decompilation and not just
     * reading. src/play and src/foss each supply their own ReviewPrompt.
     *
     * Same applicationId and same versionCode in both, so neither store sees a fork - but the
     * signatures differ, so a device takes one or the other, never both at once.
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
            versionNameSuffix = "-foss"
        }
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

    /*
     * Play In-App Review, Play flavour only. Added for FINDINGS/MARKETING: the app had no public ratings at all,
     * which costs both conversion and ranking, and the two feed each other.
     *
     * Checked before adopting: this library talks to the Play Store app over IPC, not over a
     * socket, and contributes no INTERNET permission to the merged manifest. That is verified
     * in CI terms by the assertion in tools/check_permissions.sh - if a future version of this
     * dependency ever adds it, the build is the thing that should fail, not the store listing's
     * central claim.
     */
    "playImplementation"("com.google.android.play:review-ktx:2.0.2")
}

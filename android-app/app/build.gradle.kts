import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val alphaKeystore = providers.environmentVariable("FM_ALPHA_KEYSTORE").orNull
val requireAlphaSigning = providers.environmentVariable("FM_REQUIRE_ALPHA_SIGNING").orNull == "true"
check(!requireAlphaSigning || !alphaKeystore.isNullOrBlank()) { "Official alpha builds require the persistent signing keystore." }

android {
    namespace = "in.financeministry.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "in.financeministry.app"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (!alphaKeystore.isNullOrBlank()) {
            create("alphaDistribution") {
                storeFile = file(alphaKeystore)
                storePassword = providers.environmentVariable("FM_ALPHA_STORE_PASSWORD").get()
                keyAlias = "finance-alpha"
                keyPassword = providers.environmentVariable("FM_ALPHA_STORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            if (!alphaKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("alphaDistribution")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation("androidx.compose.material3:material3")
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

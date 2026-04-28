plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.rssai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rssai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        val rssApiBaseUrl = providers.gradleProperty("RSS_API_BASE_URL").orElse("").get()
        val rssApiToken = providers.gradleProperty("RSS_API_TOKEN").orElse("").get()
        val defaultLlmProvider = providers.gradleProperty("DEFAULT_LLM_PROVIDER").orElse("openai_compatible").get()
        val defaultAiModel = providers.gradleProperty("DEFAULT_AI_MODEL").orElse("gpt-5.4").get()
        val defaultCodexModel = providers.gradleProperty("DEFAULT_CODEX_MODEL").orElse("gpt-5.4").get()
        val defaultAiContentFormatting = providers.gradleProperty("DEFAULT_AI_CONTENT_FORMATTING_ENABLED").orElse("false").get()
        val defaultBrowserBypass = providers.gradleProperty("DEFAULT_BROWSER_BYPASS_ENABLED").orElse("true").get()

        fun String.escaped() = replace("\\", "\\\\").replace("\"", "\\\"")

        buildConfigField("String", "DEFAULT_RSS_API_BASE_URL", "\"${rssApiBaseUrl.escaped()}\"")
        buildConfigField("String", "DEFAULT_RSS_API_TOKEN", "\"${rssApiToken.escaped()}\"")
        buildConfigField("String", "DEFAULT_LLM_PROVIDER", "\"${defaultLlmProvider.escaped()}\"")
        buildConfigField("String", "DEFAULT_AI_MODEL", "\"${defaultAiModel.escaped()}\"")
        buildConfigField("String", "DEFAULT_CODEX_MODEL", "\"${defaultCodexModel.escaped()}\"")
        buildConfigField("boolean", "DEFAULT_AI_CONTENT_FORMATTING_ENABLED", defaultAiContentFormatting)
        buildConfigField("boolean", "DEFAULT_BROWSER_BYPASS_ENABLED", defaultBrowserBypass)
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

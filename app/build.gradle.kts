plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.oleg.coltbot"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.oleg.coltbot"; minSdk = 26; targetSdk = 34; versionCode = 2; versionName = "2.0"
        ndk { abiFilters.add("arm64-v8a") }   // LiteRT тянет нативные библиотеки; нужен только 64-битный ARM (все современные телефоны)
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions {
        jvmTarget = "17"
        // LiteRT 2.x может быть собран более новым Kotlin, чем 1.9.24 в проекте: разрешаем читать его метаданные
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
}
dependencies {
    // Google LiteRT (бывший TensorFlow Lite): CompiledModel API, CPU/GPU
    implementation("com.google.ai.edge.litert:litert:2.2.0")
}

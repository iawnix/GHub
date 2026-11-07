plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "dev.remotegpt.app"
    compileSdk = 36
    defaultConfig {
        applicationId = providers.gradleProperty("previewApplicationId").orElse("dev.remotegpt.app").get()
        resValue("string", "app_name", if (providers.gradleProperty("previewApplicationId").isPresent) "GHub Preview" else "GHub")
        minSdk = 29
        targetSdk = 36
        versionCode = 54
        versionName = "0.1.53"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("proguard-test-rules.pro")
    }
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()
    if (testBuildType == "release") {
        sourceSets["androidTest"].java.setSrcDirs(listOf("src/releaseTest/java"))
    }
    sourceSets["androidTest"].assets.srcDir("schemas")
    sourceSets["main"].assets.srcDir(rootProject.file("licenses"))
    bundle { language { enableSplit = false } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        create("release") {
            val path = System.getenv("RELEASE_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("RELEASE_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
            else if (providers.gradleProperty("testBuildType").orNull == "release") signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":codex-client"))
    implementation(project(":terminal"))
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.37.0")
    implementation("ru.noties:jlatexmath-android:0.2.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "dev.usagetime.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.usagetime.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 39
        versionName = "0.4.10"
        val currentVersion = requireNotNull(versionName)
        val updateNotes = providers.fileContents(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
            .asText.get().lineSequence()
            .dropWhile { !it.startsWith("## $currentVersion - ") }
            .drop(1)
            .takeWhile { !it.startsWith("## ") }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").replace("**", "").replace("`", "") }
            .joinToString("\n")
        val javaString = updateNotes.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\t", "\\t").replace("\n", "\\n")
        buildConfigField("String", "VERSION_UPDATE_NOTES", "\"$javaString\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        getByName("debug") {
            providers.environmentVariable("USAGETIME_KEYSTORE_PATH").orNull?.let { path ->
                storeFile = file(path)
                storePassword = providers.environmentVariable("USAGETIME_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("USAGETIME_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("USAGETIME_KEY_PASSWORD").get()
            }
        }
        providers.environmentVariable("USAGETIME_KEYSTORE_PATH").orNull?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = providers.environmentVariable("USAGETIME_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("USAGETIME_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("USAGETIME_KEY_PASSWORD").get()
            }
        }
    }
    androidResources {
        localeFilters += listOf("zh-rCN", "zh-rHK", "zh-rTW", "en")
    }
    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/**/LICENSE.txt")
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("preview") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-safety-tests.pro")
            matchingFallbacks += "debug"
        }
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

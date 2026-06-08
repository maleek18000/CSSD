plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
android {
    namespace = "com.animeday"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-XXLanguage:+BreakContinueInInlineLambdas")
    }
}
cloudstream {
    version = 1
    description = "Anime Day - anime, movies, cartoons from multiple servers"
}
dependencies {
    val cloudstream by configurations
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    cloudstream("com.lagradost:cloudstream3:pre-release")
}

import org.jetbrains.kotlin.konan.properties.Properties

version = 14

android {
    namespace = "com.stremio"

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val properties = Properties()
        val secretsFile = project.rootProject.file("secrets.properties")
        if (secretsFile.exists()) {
            properties.load(secretsFile.inputStream())
        }
        android.buildFeatures.buildConfig = true
        buildConfigField("String", "TMDB_API3", "\"${properties.getProperty("TMDB_API3") ?: "8ff0f5d3eb22a8130a33808a70688dce"}\"")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    language = "en"
    description = "Stremio addons on CloudStream. Supports both Catalog and TMDb-based providers with sections."
    authors = listOf("Hexated", "phisher98", "DieGon")
    status = 3
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/Stremio/stremio_icon.png"
}

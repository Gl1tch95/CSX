import org.jetbrains.kotlin.konan.properties.Properties

version = 1
android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())
        android.buildFeatures.buildConfig=true
        buildConfigField("String", "TMDB_KEY", "\"${properties.getProperty("TMDB_KEY")}\"")
    }
}

cloudstream {
    language = "en"
    description = "Vidking player via TMDB catalog"
    authors = listOf("megix")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    iconUrl = "https://www.vidking.net/favicon.ico"
}


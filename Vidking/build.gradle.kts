version = 1

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

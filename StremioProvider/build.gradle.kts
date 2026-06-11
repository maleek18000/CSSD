// use an integer for version numbers
version = 7


cloudstream {
    language = "en"
    description = "Allows you to use Stremio addons. Supports both Catalog (StremioC) and TMDb-based (StremioX) providers. Requires setup."

    authors = listOf("anhdaden", "hexated", "Cloudburst")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf("TvSeries", "Anime", "Movie")

    iconUrl = "https://www.stremio.com/website/favicon.ico"
}

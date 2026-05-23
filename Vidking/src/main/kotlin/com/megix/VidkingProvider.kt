package com.megix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class VidkingProvider : MainAPI() {
    override var mainUrl = "https://www.vidking.net"
    override var name = "Vidking"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val tmdbApi = "https://db.videasy.net/3"
    private val streamApi = "https://api.videasy.net"
    private val decryptApi = "https://enc-dec.app/api"

    override val mainPage = mainPageOf(
        "trending/movie/day" to "Trending Movies",
        "trending/tv/day" to "Trending Series",
        "movie/top_rated" to "Top Rated Movies",
        "tv/top_rated" to "Top Rated Shows",
        "trending/all/week" to "Popular",
        // Genre sections (movies)
        "discover/movie?with_genres=28&sort_by=popularity.desc" to "Action",
        "discover/movie?with_genres=12&sort_by=popularity.desc" to "Adventure",
        "discover/movie?with_genres=878&sort_by=popularity.desc" to "Sci-Fi",
        "discover/movie?with_genres=27&sort_by=popularity.desc" to "Horror",
        "discover/movie?with_genres=14&sort_by=popularity.desc" to "Fantasy",
        "discover/movie?with_genres=18&sort_by=popularity.desc" to "Drama",

        // Genre sections (tv)
        "discover/tv?with_genres=10759&sort_by=popularity.desc" to "Action (TV)",
        "discover/tv?with_genres=10759&sort_by=popularity.desc" to "Adventure (TV)",
        "discover/tv?with_genres=10765&sort_by=popularity.desc" to "Sci-Fi (TV)",
        "discover/tv?with_genres=10765&sort_by=popularity.desc" to "Fantasy (TV)",
        "discover/tv?with_genres=10765&sort_by=popularity.desc" to "Horror (TV)",
        "discover/tv?with_genres=18&sort_by=popularity.desc" to "Drama (TV)",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val defaultType = when {
            request.data.contains("/movie") -> "movie"
            request.data.contains("/tv") -> "tv"
            else -> null
        }

        val res = app.get(
            "${tmdbApi}/${request.data}?language=en-US&page=$page",
            timeout = 10L
        ).parsedSafe<TmdbPagedResults>() ?: throw ErrorLoadingException("Invalid response")

        val home = res.results.orEmpty().mapNotNull { it.toSearchResponse(defaultType) }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val res = app.get(
            "${tmdbApi}/search/multi?language=en-US&query=${quote(query)}&page=$page",
            timeout = 10L
        ).parsedSafe<TmdbPagedResults>() ?: return null

        val results = res.results.orEmpty().mapNotNull { it.toSearchResponse() }
        val hasNext = (res.totalPages ?: 0) > page
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<VidkingLinkData>(url)
        val type = data.type

        val detailUrl = if (type == "movie") {
            "${tmdbApi}/movie/${data.id}?language=en-US&append_to_response=external_ids"
        } else {
            "${tmdbApi}/tv/${data.id}?language=en-US&append_to_response=external_ids"
        }

        val detail = app.get(detailUrl, timeout = 10L).parsedSafe<TmdbDetail>() ?: return null
        val title = detail.title ?: detail.name ?: return null
        val poster = imageUrl(detail.posterPath, 500)
        val background = imageUrl(detail.backdropPath, 1280)
        val year = (detail.releaseDate ?: detail.firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val tags = detail.genres?.mapNotNull { it.name }

        if (type == "tv") {
            val seasons = detail.seasons.orEmpty()
                .filter { (it.seasonNumber ?: 0) > 0 && (it.episodeCount ?: 0) > 0 }

            val episodes = seasons.flatMap { season ->
                val seasonNum = season.seasonNumber ?: return@flatMap emptyList()
                val episodeCount = season.episodeCount ?: 0
                (1..episodeCount).map { ep ->
                    newEpisode(
                        VidkingLinkData(
                            id = detail.id,
                            type = "tv",
                            season = seasonNum,
                            episode = ep,
                            title = title,
                            year = year,
                            imdbId = detail.externalIds?.imdbId
                        ).toJson()
                    ) {
                        this.name = "Episode $ep"
                        this.season = seasonNum
                        this.episode = ep
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = detail.overview
                this.year = year
                this.tags = tags
                this.seasonNames = seasons.mapNotNull { season ->
                    val number = season.seasonNumber ?: return@mapNotNull null
                    SeasonData(number, season.name ?: "Season $number")
                }
                addImdbId(detail.externalIds?.imdbId)
            }
        }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            VidkingLinkData(
                id = detail.id,
                type = "movie",
                title = title,
                year = year,
                imdbId = detail.externalIds?.imdbId
            ).toJson()
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = detail.overview
            this.year = year
            this.tags = tags
            addImdbId(detail.externalIds?.imdbId)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<VidkingLinkData>(data)
        val title = linkData.title ?: return false

        if (linkData.type == "tv" && (linkData.season == null || linkData.episode == null)) {
            return false
        }

        val headers = mapOf(
            "Accept" to "*/*",
            "User-Agent" to USER_AGENT,
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )

        val encTitle = quote(quote(title))
        val imdbParam = linkData.imdbId ?: ""
        val yearParam = linkData.year?.toString() ?: ""

        val servers = listOf(
            "mb-flix",
            "cdn",
            "1movies",
            "moviebox",
            "primewire",
            "m4uhd",
            "hdmovie",
            "primesrcme",
            "visioncine",
            "overflix",
            "superflix",
            "cuevana",
            "lamovie",
            "myflixerzupcloud"
        )

        val found = java.util.concurrent.atomic.AtomicBoolean(false)
        val semaphore = Semaphore(6)

        servers.amap { server ->
            semaphore.withPermit {
                val url = if (linkData.type == "movie") {
                    "${streamApi}/$server/sources-with-title?title=$encTitle&mediaType=movie&year=$yearParam&tmdbId=${linkData.id}&imdbId=$imdbParam"
                } else {
                    "${streamApi}/$server/sources-with-title?title=$encTitle&mediaType=tv&year=$yearParam&tmdbId=${linkData.id}&episodeId=${linkData.episode}&seasonId=${linkData.season}&imdbId=$imdbParam"
                }

                val encrypted = runCatching {
                    app.get(url, headers = headers, timeout = 5L).text
                }.getOrNull() ?: return@withPermit

                val decrypted = runCatching {
                    app.post(
                        "$decryptApi/dec-videasy",
                        json = mapOf("text" to encrypted, "id" to linkData.id),
                        timeout = 5L
                    ).text
                }.getOrNull() ?: return@withPermit

                val result = runCatching { JSONObject(decrypted).optJSONObject("result") }.getOrNull() ?: return@withPermit

                val sources = result.optJSONArray("sources")
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        val item = sources.optJSONObject(i) ?: continue
                        val sourceUrl = item.optString("url")
                        val quality = item.optString("quality")
                        if (sourceUrl.isBlank()) continue

                        val linkType = when {
                            sourceUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                            sourceUrl.contains(".mp4", ignoreCase = true) || sourceUrl.contains(".mkv", ignoreCase = true) -> ExtractorLinkType.VIDEO
                            else -> INFER_TYPE
                        }

                        callback.invoke(
                            newExtractorLink(
                                "Vidking[${server.capitalizeServer()}]",
                                "Vidking[${server.capitalizeServer()}] $quality",
                                sourceUrl,
                                linkType
                            ) {
                                this.quality = getIndexQuality(quality)
                                this.headers = headers
                            }
                        )
                        found.set(true)
                    }
                }

                val subtitles = result.optJSONArray("subtitles")
                if (subtitles != null) {
                    for (i in 0 until subtitles.length()) {
                        val item = subtitles.optJSONObject(i) ?: continue
                        val subUrl = item.optString("url").ifBlank { continue }
                        val rawLang = item.optString("lang").ifBlank { item.optString("language") }.ifBlank { "Unknown" }
                        val normalized = getLanguage(rawLang) ?: rawLang
                        if (!normalized.equals("English", ignoreCase = true)) continue
                        subtitleCallback.invoke(newSubtitleFile(normalized, subUrl) {
                            this.headers = headers
                        })
                    }
                }

                val tracks = result.optJSONArray("tracks")
                if (tracks != null) {
                    for (i in 0 until tracks.length()) {
                        val item = tracks.optJSONObject(i) ?: continue
                        val kind = item.optString("kind").orEmpty()
                        if (kind.contains("caption", ignoreCase = true) || kind.contains("sub", ignoreCase = true)) {
                            val subUrl = item.optString("file").ifBlank { continue }
                            val rawLang = item.optString("label").ifBlank { "Unknown" }
                            val normalized = getLanguage(rawLang) ?: rawLang
                            if (!normalized.equals("English", ignoreCase = true)) continue
                            subtitleCallback.invoke(newSubtitleFile(normalized, subUrl) {
                                this.headers = headers
                            })
                        }
                    }
                }

                val captions = result.optJSONArray("captions")
                if (captions != null) {
                    for (i in 0 until captions.length()) {
                        val item = captions.optJSONObject(i) ?: continue
                        val subUrl = item.optString("url").ifBlank { continue }
                        val rawLang = item.optString("lan").ifBlank { "Unknown" }
                        val normalized = getLanguage(rawLang) ?: rawLang
                        if (!normalized.equals("English", ignoreCase = true)) continue
                        subtitleCallback.invoke(newSubtitleFile(normalized, subUrl) {
                            this.headers = headers
                        })
                    }
                }
            }
        }

        return found.get()
    }

    private fun quote(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private val languageMap = mapOf(
        "Afrikaans" to listOf("af", "afr"),
        "Albanian" to listOf("sq", "sqi"),
        "Amharic" to listOf("am", "amh"),
        "Arabic" to listOf("ar", "ara"),
        "Armenian" to listOf("hy", "hye"),
        "Azerbaijani" to listOf("az", "aze"),
        "Basque" to listOf("eu", "eus"),
        "Belarusian" to listOf("be", "bel"),
        "Bengali" to listOf("bn", "ben"),
        "Bosnian" to listOf("bs", "bos"),
        "Bulgarian" to listOf("bg", "bul"),
        "Catalan" to listOf("ca", "cat"),
        "Chinese" to listOf("zh", "zho"),
        "Croatian" to listOf("hr", "hrv"),
        "Czech" to listOf("cs", "ces", "cze"),
        "Danish" to listOf("da", "dan"),
        "Dutch" to listOf("nl", "nld", "dut"),
        "English" to listOf("en", "eng"),
        "Estonian" to listOf("et", "est"),
        "Filipino" to listOf("tl", "tgl", "fil"),
        "Finnish" to listOf("fi", "fin"),
        "French" to listOf("fr", "fra"),
        "Galician" to listOf("gl", "glg"),
        "Georgian" to listOf("ka", "kat"),
        "German" to listOf("de", "deu", "ger"),
        "Greek" to listOf("el", "ell", "gre"),
        "Gujarati" to listOf("gu", "guj"),
        "Hebrew" to listOf("he", "heb"),
        "Hindi" to listOf("hi", "hin"),
        "Hungarian" to listOf("hu", "hun"),
        "Icelandic" to listOf("is", "isl"),
        "Indonesian" to listOf("id", "ind"),
        "Italian" to listOf("it", "ita"),
        "Japanese" to listOf("ja", "jpn"),
        "Kannada" to listOf("kn", "kan"),
        "Kazakh" to listOf("kk", "kaz"),
        "Korean" to listOf("ko", "kor"),
        "Latvian" to listOf("lv", "lav"),
        "Lithuanian" to listOf("lt", "lit"),
        "Macedonian" to listOf("mk", "mkd", "mac"),
        "Malay" to listOf("ms", "msa"),
        "Malayalam" to listOf("ml", "mal"),
        "Maltese" to listOf("mt", "mlt"),
        "Marathi" to listOf("mr", "mar"),
        "Mongolian" to listOf("mn", "mon"),
        "Nepali" to listOf("ne", "nep"),
        "Norwegian" to listOf("no", "nor", "nob"),
        "Persian" to listOf("fa", "fas"),
        "Polish" to listOf("pl", "pol"),
        "Portuguese" to listOf("pt", "por"),
        "Punjabi" to listOf("pa", "pan"),
        "Romanian" to listOf("ro", "ron", "rum"),
        "Russian" to listOf("ru", "rus"),
        "Serbian" to listOf("sr", "srp"),
        "Sinhala" to listOf("si", "sin"),
        "Slovak" to listOf("sk", "slk"),
        "Slovenian" to listOf("sl", "slv"),
        "Spanish" to listOf("es", "spa"),
        "Swahili" to listOf("sw", "swa"),
        "Swedish" to listOf("sv", "swe"),
        "Tamil" to listOf("ta", "tam"),
        "Telugu" to listOf("te", "tel"),
        "Thai" to listOf("th", "tha"),
        "Turkish" to listOf("tr", "tur"),
        "Ukrainian" to listOf("uk", "ukr"),
        "Urdu" to listOf("ur", "urd"),
        "Uzbek" to listOf("uz", "uzb"),
        "Vietnamese" to listOf("vi", "vie"),
        "Welsh" to listOf("cy", "cym"),
        "Yiddish" to listOf("yi", "yid")
    )

    private fun getLanguage(language: String?): String? {
        language ?: return null

        var normalizedLang = if (language.contains("-")) {
            language.substringBefore("-")
        } else if (language.contains(" ")) {
            language.substringBefore(" ")
        } else if (language.contains("CR_")) {
            language.substringAfter("CR_")
        } else {
            language
        }.trim()

        if (normalizedLang.isBlank()) {
            normalizedLang = language.trim()
        }

        val tag = languageMap.entries.find { entry ->
            entry.value.contains(normalizedLang.lowercase()) || entry.key.equals(normalizedLang, ignoreCase = true)
        }?.key

        return tag ?: normalizedLang
    }

    private fun imageUrl(path: String?, size: Int): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("/")) "https://image.tmdb.org/t/p/w$size$path" else path
    }

    private fun String.capitalizeServer(): String = replaceFirstChar { it.uppercase() }

    private fun getIndexQuality(str: String?): Int {
        if (str.isNullOrBlank()) return Qualities.Unknown.value

        Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return it
        }

        val lower = str.lowercase()
        return when {
            lower.contains("8k") -> 4320
            lower.contains("4k") -> 2160
            lower.contains("2k") -> 1440
            else -> Qualities.Unknown.value
        }
    }

    private fun TmdbMedia.toSearchResponse(defaultType: String? = null): SearchResponse? {
        val resolvedType = mediaType ?: defaultType
        if (resolvedType == "person") return null

        val title = title ?: name ?: originalTitle ?: originalName ?: return null
        val tvType = if (resolvedType == "tv") TvType.TvSeries else TvType.Movie
        val data = VidkingLinkData(id = id, type = resolvedType ?: "movie").toJson()

        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, data, tvType) {
                this.posterUrl = imageUrl(posterPath, 500)
            }
        } else {
            newMovieSearchResponse(title, data, tvType) {
                this.posterUrl = imageUrl(posterPath, 500)
            }
        }
    }

    data class VidkingLinkData(
        val id: Int,
        val type: String,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val year: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null
    )

    data class TmdbPagedResults(
        val page: Int? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
        val results: List<TmdbMedia>? = null
    )

    data class TmdbMedia(
        val id: Int,
        @JsonProperty("media_type") val mediaType: String? = null,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null
    )

    data class TmdbDetail(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        val overview: String? = null,
        val genres: List<TmdbGenre>? = null,
        val seasons: List<TmdbSeason>? = null,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null
    )

    data class TmdbGenre(
        val id: Int? = null,
        val name: String? = null
    )

    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
        val name: String? = null
    )

    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null
    )
}

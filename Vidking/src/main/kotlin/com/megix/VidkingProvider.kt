package com.megix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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

    private val apiUrl = "https://api.themoviedb.org/3"
    private val streamApi = "https://api.videasy.net"
    private val decryptApi = "https://enc-dec.app/api"

    companion object {
        private const val apiKey = BuildConfig.TMDB_KEY
    }

    private fun getTmdbUrl(relativeUrl: String): String {
        val separator = if (relativeUrl.contains("?")) "&" else "?"
        return "$apiUrl/$relativeUrl${separator}api_key=$apiKey"
    }


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

        val separator = if (request.data.contains("?")) "&" else "?"
        val res = app.get(
            getTmdbUrl("${request.data}${separator}language=en-US&page=$page"),
            timeout = 10L
        ).parsedSafe<TmdbPagedResults>() ?: throw ErrorLoadingException("Invalid response")

        val home = res.results.orEmpty().mapNotNull { it.toSearchResponse(defaultType) }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val res = app.get(
            getTmdbUrl("search/multi?language=en-US&query=${quote(query)}&page=$page"),
            timeout = 10L
        ).parsedSafe<TmdbPagedResults>() ?: return null

        val results = res.results.orEmpty().mapNotNull { it.toSearchResponse() }
        val hasNext = (res.totalPages ?: 0) > page
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<LinkData>(url)
        val type = data.type ?: "movie"

        val append = "alternative_titles,credits,external_ids,videos,recommendations,content_ratings,release_dates"

        val resUrl = if (type == "movie") {
            getTmdbUrl("movie/${data.id}?language=en-US&append_to_response=$append")
        } else {
            getTmdbUrl("tv/${data.id}?language=en-US&append_to_response=$append")
        }


        val res = app.get(resUrl).parsedSafe<MediaDetail>() ?: return null
        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val ageRating = res.usAgeRating
        val orgTitle = res.originalTitle ?: res.originalName
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val genres = res.genres?.mapNotNull { it.name }
        val imdbId = res.external_ids?.imdb_id
        val isCartoon = genres?.contains("Animation") ?: false
        val isAnime = isCartoon && (res.original_language == "zh" || res.original_language == "ja" || res.original_language == "ko")
        val isAsian = !isAnime && (res.original_language == "zh" || res.original_language == "ko")
        val isBollywood = res.production_countries?.any { it.name == "India" } ?: false

        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name } }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            val name = cast.name ?: cast.originalName ?: return@mapNotNull null
            ActorData(
                Actor(name, getImageUrl(cast.profilePath)), roleString = cast.character
            )
        } ?: emptyList()

        val logo = fetchTmdbLogoUrl(type, res.id)

        val recommendations = res.recommendations?.results?.mapNotNull { media ->
            media.toSearchResponse()
        }

        val trailer = res.videos?.results.orEmpty()
            .filter { it.type == "Trailer" }
            .map { "https://www.youtube.com/watch?v=${it.key}" }
            .reversed()
            .ifEmpty {
                res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" } ?: emptyList()
            }

        if (type == "tv") {
            val lastSeason = res.last_episode_to_air?.season_number
            val episodes = res.seasons?.filter { (it.seasonNumber ?: 0) != 0 }?.mapNotNull { season ->
                app.get(getTmdbUrl("tv/${data.id}/season/${season.seasonNumber}?language=en-US"))
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        newEpisode(
                            LinkData(
                                id = data.id,
                                imdbId = res.external_ids?.imdb_id,
                                type = "tv",
                                season = eps.seasonNumber,
                                episode = eps.episodeNumber,
                                title = title,
                                year = season.airDate?.split("-")?.first()?.toIntOrNull(),
                                orgTitle = orgTitle,
                                isAnime = isAnime,
                                airedYear = year,
                                lastSeason = lastSeason,
                                epsTitle = eps.name,
                                jpTitle = res.alternative_titles?.results?.find { it.iso_3166_1 == "JP" }?.title,
                                date = season.airDate,
                                airedDate = res.releaseDate ?: res.firstAirDate,
                                isAsian = isAsian,
                                isBollywood = isBollywood,
                                isCartoon = isCartoon,
                                alttitle = res.title,
                                nametitle = res.name
                            ).toJson()
                        ) {
                            this.name = eps.name
                            this.season = eps.seasonNumber
                            this.episode = eps.episodeNumber
                            this.posterUrl = getImageUrl(eps.stillPath) ?: "https://github.com/SaurabhKaperwan/Utils/raw/refs/heads/main/missing_thumbnail.png"
                            this.score = Score.from10(eps.voteAverage)
                            this.description = eps.overview
                            this.runTime = eps.runtime
                        }
                    }
            }?.flatten() ?: listOf()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.contentRating = ageRating
                this.logoUrl = logo
                this.tags = keywords?.map { word -> word.replaceFirstChar { it.titlecase() } }
                    ?.takeIf { it.isNotEmpty() } ?: genres
                this.score = Score.from10(res.vote_average.toString())
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addImdbId(imdbId)
            }
        } else {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    id = data.id,
                    imdbId = res.external_ids?.imdb_id,
                    type = "movie",
                    title = title,
                    year = year,
                    orgTitle = orgTitle,
                    isAnime = isAnime,
                    jpTitle = res.alternative_titles?.results?.find { it.iso_3166_1 == "JP" }?.title,
                    airedDate = res.releaseDate ?: res.firstAirDate,
                    isAsian = isAsian,
                    isBollywood = isBollywood,
                    alttitle = res.title,
                    nametitle = res.name
                ).toJson()
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.contentRating = ageRating
                this.logoUrl = logo
                this.tags = keywords?.map { word -> word.replaceFirstChar { it.titlecase() } }
                    ?.takeIf { it.isNotEmpty() } ?: genres
                this.score = Score.from10(res.vote_average.toString())
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addImdbId(imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
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
            "cdn",
            "mb-flix",
            "cuevana",
            "hdmovie",
            "lamovie"
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
                                this.referer = "$mainUrl/"
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
                        subtitleCallback.invoke(newSubtitleFile("en", subUrl) {
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
                            subtitleCallback.invoke(newSubtitleFile("en", subUrl) {
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
                        subtitleCallback.invoke(newSubtitleFile("en", subUrl) {
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

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    private suspend fun fetchTmdbLogoUrl(
        type: String,
        tmdbId: Int?
    ): String? {
        if (tmdbId == null) return null
        val url = if (type == "movie")
            getTmdbUrl("movie/$tmdbId/images")
        else
            getTmdbUrl("tv/$tmdbId/images")

        val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
        val logos = json.optJSONArray("logos") ?: return null
        if (logos.length() == 0) return null

        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            val path = logo.optString("file_path")
            if (path.isNotBlank() && !path.endsWith(".svg", true)) {
                return "https://image.tmdb.org/t/p/w500$path"
            }
        }
        return null
    }

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
        val data = LinkData(id = id, type = resolvedType ?: "movie").toJson()

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

    private fun Media.toSearchResponse(defaultType: String? = null): SearchResponse? {
        val resolvedType = mediaType ?: defaultType
        if (resolvedType == "person") return null

        val title = title ?: name ?: return null
        val tvType = if (resolvedType == "tv") TvType.TvSeries else TvType.Movie
        val data = LinkData(id = id, type = resolvedType ?: "movie").toJson()

        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, data, tvType) {
                this.posterUrl = getImageUrl(posterPath)
            }
        } else {
            newMovieSearchResponse(title, data, tvType) {
                this.posterUrl = getImageUrl(posterPath)
            }
        }
    }

    data class LinkData(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("imdbId") val imdbId: String? = null,
        @param:JsonProperty("tvdbId") val tvdbId: Int? = null,
        @param:JsonProperty("type") val type: String? = null,
        @param:JsonProperty("season") val season: Int? = null,
        @param:JsonProperty("episode") val episode: Int? = null,
        @param:JsonProperty("epid") val epid: Int? = null,
        @param:JsonProperty("aniId") val aniId: String? = null,
        @param:JsonProperty("animeId") val animeId: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("year") val year: Int? = null,
        @param:JsonProperty("orgTitle") val orgTitle: String? = null,
        @param:JsonProperty("isAnime") val isAnime: Boolean = false,
        @param:JsonProperty("airedYear") val airedYear: Int? = null,
        @param:JsonProperty("lastSeason") val lastSeason: Int? = null,
        @param:JsonProperty("epsTitle") val epsTitle: String? = null,
        @param:JsonProperty("jpTitle") val jpTitle: String? = null,
        @param:JsonProperty("date") val date: String? = null,
        @param:JsonProperty("airedDate") val airedDate: String? = null,
        @param:JsonProperty("isAsian") val isAsian: Boolean = false,
        @param:JsonProperty("isBollywood") val isBollywood: Boolean = false,
        @param:JsonProperty("isCartoon") val isCartoon: Boolean = false,
        @param:JsonProperty("alttitle") val alttitle: String? = null,
        @param:JsonProperty("nametitle") val nametitle: String? = null,
    )

    data class TmdbPagedResults(
        @param:JsonProperty("page") val page: Int? = null,
        @param:JsonProperty("total_pages") val totalPages: Int? = null,
        @param:JsonProperty("results") val results: List<TmdbMedia>? = null
    )

    data class TmdbMedia(
        @param:JsonProperty("id") val id: Int,
        @param:JsonProperty("media_type") val mediaType: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("original_title") val originalTitle: String? = null,
        @param:JsonProperty("original_name") val originalName: String? = null,
        @param:JsonProperty("poster_path") val posterPath: String? = null
    )

    data class Genres(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
    )

    data class Keywords(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
    )

    data class KeywordResults(
        @param:JsonProperty("results") val results: ArrayList<Keywords>? = arrayListOf(),
        @param:JsonProperty("keywords") val keywords: ArrayList<Keywords>? = arrayListOf(),
    )

    data class LastEpisodeToAir(
        @param:JsonProperty("episode_number") val episode_number: Int? = null,
        @param:JsonProperty("season_number") val season_number: Int? = null,
    )

    data class Seasons(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("season_number") val seasonNumber: Int? = null,
        @param:JsonProperty("air_date") val airDate: String? = null,
    )

    data class Trailers(
        @param:JsonProperty("key") val key: String? = null,
        @param:JsonProperty("type") val type: String? = null,
    )

    data class ResultsTrailer(
        @param:JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
    )

    data class ExternalIds(
        @param:JsonProperty("imdb_id") val imdb_id: String? = null,
        @param:JsonProperty("tvdb_id") val tvdb_id: Int? = null,
    )

    data class Credits(
        @param:JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class Cast(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("original_name") val originalName: String? = null,
        @param:JsonProperty("character") val character: String? = null,
        @param:JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @param:JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class ResultsRecommendations(
        @param:JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("original_title") val originalTitle: String? = null,
        @param:JsonProperty("media_type") val mediaType: String? = null,
        @param:JsonProperty("poster_path") val posterPath: String? = null,
        @param:JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class AltTitles(
        @param:JsonProperty("iso_3166_1") val iso_3166_1: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("type") val type: String? = null,
    )

    data class ResultsAltTitles(
        @param:JsonProperty("results") val results: ArrayList<AltTitles>? = arrayListOf(),
    )

    data class ProductionCountries(
        @param:JsonProperty("name") val name: String? = null,
    )

    data class ContentRatings(
        @param:JsonProperty("results") val results: ArrayList<ContentRatingResult>? = arrayListOf()
    )

    data class ContentRatingResult(
        @param:JsonProperty("iso_3166_1") val iso3166_1: String? = null,
        @param:JsonProperty("rating") val rating: String? = null
    )

    data class ReleaseDates(
        @param:JsonProperty("results") val results: ArrayList<ReleaseDatesResult>? = arrayListOf()
    )

    data class ReleaseDatesResult(
        @param:JsonProperty("iso_3166_1") val iso3166_1: String? = null,
        @param:JsonProperty("release_dates") val releaseDates: ArrayList<ReleaseDateItem>? = arrayListOf()
    )

    data class ReleaseDateItem(
        @param:JsonProperty("certification") val certification: String? = null
    )

    data class MediaDetail(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("adult") val adult: Boolean = false,
        @param:JsonProperty("imdb_id") val imdbId: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("original_title") val originalTitle: String? = null,
        @param:JsonProperty("original_name") val originalName: String? = null,
        @param:JsonProperty("poster_path") val posterPath: String? = null,
        @param:JsonProperty("backdrop_path") val backdropPath: String? = null,
        @param:JsonProperty("release_date") val releaseDate: String? = null,
        @param:JsonProperty("first_air_date") val firstAirDate: String? = null,
        @param:JsonProperty("overview") val overview: String? = null,
        @param:JsonProperty("runtime") val runtime: Int? = null,
        @param:JsonProperty("vote_average") val vote_average: Any? = null,
        @param:JsonProperty("original_language") val original_language: String? = null,
        @param:JsonProperty("status") val status: String? = null,
        @param:JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @param:JsonProperty("keywords") val keywords: KeywordResults? = null,
        @param:JsonProperty("last_episode_to_air") val last_episode_to_air: LastEpisodeToAir? = null,
        @param:JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @param:JsonProperty("videos") val videos: ResultsTrailer? = null,
        @param:JsonProperty("external_ids") val external_ids: ExternalIds? = null,
        @param:JsonProperty("credits") val credits: Credits? = null,
        @param:JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
        @param:JsonProperty("alternative_titles") val alternative_titles: ResultsAltTitles? = null,
        @param:JsonProperty("production_countries") val production_countries: ArrayList<ProductionCountries>? = arrayListOf(),
        @param:JsonProperty("content_ratings") val contentRatings: ContentRatings? = null,
        @param:JsonProperty("release_dates") val releaseDates: ReleaseDates? = null
    ) {
        val usAgeRating: String?
            get() {
                contentRatings?.results?.firstOrNull { it.iso3166_1 == "US" }?.rating?.takeIf { it.isNotBlank() }?.let { return it }
                releaseDates?.results?.firstOrNull { it.iso3166_1 == "US" }?.releaseDates?.firstOrNull { !it.certification.isNullOrBlank() }?.certification?.let { return it }
                return null
            }
    }

    data class Episodes(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("overview") val overview: String? = null,
        @param:JsonProperty("air_date") val airDate: String? = null,
        @param:JsonProperty("still_path") val stillPath: String? = null,
        @param:JsonProperty("vote_average") val voteAverage: Double? = null,
        @param:JsonProperty("episode_number") val episodeNumber: Int? = null,
        @param:JsonProperty("season_number") val seasonNumber: Int? = null,
        @param:JsonProperty("runtime") val runtime: Int? = null,
    )

    data class MediaDetailEpisodes(
        @param:JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )
}

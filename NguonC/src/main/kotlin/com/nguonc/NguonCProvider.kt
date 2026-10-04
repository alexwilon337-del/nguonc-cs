package com.nguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon
    )

    // Mỗi mục = 1 đường dẫn API /api/films/<data>?page=N (tự phân trang vô hạn)
    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim mới cập nhật",
        "danh-sach/phim-le" to "Phim lẻ",
        "danh-sach/phim-bo" to "Phim bộ",
        "danh-sach/hoat-hinh" to "Hoạt hình",
        "danh-sach/tv-shows" to "TV Shows",
        "danh-sach/phim-dang-chieu" to "Phim đang chiếu",
        "quoc-gia/han-quoc" to "Hàn Quốc",
        "quoc-gia/trung-quoc" to "Trung Quốc",
        "quoc-gia/au-my" to "Âu Mỹ",
        "quoc-gia/nhat-ban" to "Nhật Bản",
        "quoc-gia/thai-lan" to "Thái Lan",
        "quoc-gia/viet-nam" to "Việt Nam",
        "the-loai/hanh-dong" to "Hành động",
        "the-loai/tinh-cam" to "Tình cảm",
        "the-loai/hai-huoc" to "Hài hước",
        "the-loai/co-trang" to "Cổ trang",
        "the-loai/kinh-di" to "Kinh dị",
        "the-loai/vien-tuong" to "Viễn tưởng",
    )

    // ---------- Data models ----------
    data class Paginate(
        @JsonProperty("current_page") val currentPage: Int? = null,
        @JsonProperty("total_page") val totalPage: Int? = null,
    )

    data class Item(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumb_url") val thumb: String? = null,
        @JsonProperty("poster_url") val poster: String? = null,
        @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
    )

    data class ListResponse(
        @JsonProperty("paginate") val paginate: Paginate? = null,
        @JsonProperty("items") val items: List<Item>? = null,
    )

    data class Link(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
    )

    data class Server(
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("items") val items: List<Link>? = null,
    )

    data class Movie(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumb_url") val thumb: String? = null,
        @JsonProperty("poster_url") val poster: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
        @JsonProperty("time") val time: String? = null,
        @JsonProperty("casts") val casts: String? = null,
        @JsonProperty("category") val category: Any? = null,
        @JsonProperty("episodes") val episodes: List<Server>? = null,
    )

    data class DetailResponse(@JsonProperty("movie") val movie: Movie? = null)

    // Dữ liệu gửi sang loadLinks (gom các server của cùng 1 tập)
    data class EpLink(val server: String, val embed: String?, val m3u8: String?)

    // ---------- Helpers ----------
    private fun Item.toSearch(): SearchResponse? {
        val slug = slug ?: return null
        val title = name ?: return null
        val url = "$mainUrl/phim/$slug"
        val img = fixUrlNull(thumb ?: poster)
        return if ((totalEpisodes ?: 1) > 1) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = img }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = img }
        }
    }

    // category có thể là map {"1":{group:{name}, list:[{name}]}, ...}
    private fun parseCategory(raw: Any?): Map<String, List<String>> {
        val out = mutableMapOf<String, List<String>>()
        val groups = when (raw) {
            is Map<*, *> -> raw.values
            is Collection<*> -> raw
            else -> return out
        }
        for (g in groups) {
            val m = g as? Map<*, *> ?: continue
            val gname = (m["group"] as? Map<*, *>)?.get("name") as? String ?: continue
            val names = (m["list"] as? Collection<*>)
                ?.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
                .orEmpty()
            out[gname] = names
        }
        return out
    }

    // ---------- Main page / search ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get("$mainUrl/api/films/${request.data}?page=$page")
            .parsedSafe<ListResponse>()
        val items = res?.items.orEmpty().mapNotNull { it.toSearch() }
        val hasNext = (res?.paginate?.currentPage ?: page) < (res?.paginate?.totalPage ?: page)
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val res = app.get(
            "$mainUrl/api/films/search",
            params = mapOf("keyword" to query)
        ).parsedSafe<ListResponse>()
        return res?.items.orEmpty().mapNotNull { it.toSearch() }
    }

    // ---------- Detail ----------
    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast("/")
        val m = app.get("$mainUrl/api/film/$slug").parsedSafe<DetailResponse>()?.movie
            ?: return null

        // Gom các server theo tên tập
        val byEp = LinkedHashMap<String, MutableList<EpLink>>()
        for (server in m.episodes.orEmpty()) {
            for (l in server.items.orEmpty()) {
                val epName = l.name ?: continue
                byEp.getOrPut(epName) { mutableListOf() }
                    .add(EpLink(server.serverName ?: "", l.embed, l.m3u8))
            }
        }

        val cats = parseCategory(m.category)
        val tags = cats.entries
            .filter { it.key.contains("Thể loại", true) }
            .flatMap { it.value }
        val year = cats.entries
            .firstOrNull { it.key.contains("Năm", true) }
            ?.value?.firstOrNull()?.toIntOrNull()
        val isSeries = (m.totalEpisodes ?: byEp.size) > 1 ||
            cats.values.flatten().any { it.contains("Phim bộ", true) }

        val title = m.name ?: slug
        val img = fixUrlNull(m.poster ?: m.thumb)
        val desc = m.description?.let { Jsoup.parse(it).text() }
        val minutes = m.time?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        val actors = m.casts?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

        return if (isSeries) {
            val eps = byEp.map { (epName, links) ->
                newEpisode(links.toJson()) {
                    name = if (epName.all { it.isDigit() }) "Tập $epName" else epName
                    episode = Regex("\\d+").find(epName)?.value?.toIntOrNull()
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                posterUrl = img
                plot = desc
                this.year = year
                this.tags = tags
                addActors(actors)
            }
        } else {
            val data = (byEp.values.firstOrNull() ?: emptyList<EpLink>()).toJson()
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                posterUrl = img
                plot = desc
                this.year = year
                this.tags = tags
                duration = minutes
                addActors(actors)
            }
        }
    }

    // ---------- Links ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = tryParseJson<List<EpLink>>(data) ?: return false
        for (l in links) {
            if (!l.m3u8.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name ${l.server}".trim(),
                        url = l.m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = mainUrl
                        quality = Qualities.Unknown.value
                    }
                )
            } else if (!l.embed.isNullOrBlank()) {
                loadExtractor(l.embed, mainUrl, subtitleCallback, callback)
            }
        }
        return links.isNotEmpty()
    }
}

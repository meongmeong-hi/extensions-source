package eu.kanade.tachiyomi.extension.ko.ntk

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class NTKNovel : NTKBase("NTK Novel", "novel") {

    @Serializable
    private data class SafeWorksResponse(
        val novels: List<SafeWork> = emptyList(),
        val hasMore: Boolean = false,
    )

    @Serializable
    private data class SafeWork(
        val id: String? = null,
        val sourceWorkId: String? = null,
        val title: String? = null,
        val workTitle: String? = null,
        val thumbnailUrl: String? = null,
        val coverUrl: String? = null,
        val imageUrl: String? = null,
        val thumbnail: String? = null,
        val genre: String? = null,
        val author: String? = null,
    )

    private val safeJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = "$rootUrl/api/novel-list".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "views")
            addQueryParameter("page", page.toString())
            addQueryParameter("pageSize", PAGE_SIZE.toString())
            addQueryParameter("withTotal", "1")
        }.build()
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$rootUrl/novel/updates", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$rootUrl/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query)
                addQueryParameter("kind", "novel")
            }.build()
            return GET(url, headers)
        }

        val sortFilter = filters.firstInstanceOrNull<NvSortFilter>()
        val statusFilter = filters.firstInstanceOrNull<NvStatusFilter>()
        val genreFilter = filters.firstInstanceOrNull<NvGenreFilter>()

        val sortParam = sortFilter?.let { sortList[it.state].value } ?: sortList[0].value
        val statusParam = statusFilter?.let { statusList[it.state].value } ?: statusList[0].value
        val genreParam = buildNvGenreParam(genreFilter)

        val url = "$rootUrl/api/novel-list".toHttpUrl().newBuilder().apply {
            if (statusParam == "-end") addQueryParameter("status", "end")
            if (sortParam != "new") addQueryParameter("sort", sortParam)
            genreParam?.let { addQueryParameter("g", it) }
            addQueryParameter("page", page.toString())
            addQueryParameter("pageSize", PAGE_SIZE.toString())
            addQueryParameter("withTotal", "1")
        }.build()
        return GET(url, apiHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val bodyString = response.body.string()
        return try {
            val data = safeJson.decodeFromString<SafeWorksResponse>(bodyString)

            val mangas = data.novels.mapNotNull { work ->
                val id = work.id ?: work.sourceWorkId ?: return@mapNotNull null
                SManga.create().apply {
                    url = "/novel/$id"
                    title = work.title ?: work.workTitle ?: ""
                    thumbnail_url = work.thumbnailUrl ?: work.coverUrl ?: work.imageUrl ?: work.thumbnail
                    genre = work.genre
                    author = work.author
                }
            }
            MangasPage(mangas, data.hasMore)
        } catch (e: Exception) {
            val errorManga = SManga.create().apply {
                url = "/novel/"
                title = "❗오류: " + bodyString.take(150)
                thumbnail_url = ""
            }
            MangasPage(listOf(errorManga), false)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("application/json")) {
            return latestUpdatesParse(response)
        }
        return popularMangaParse(response)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            document.selectFirst(".view-wrap h1, .novel-title, span.title, h1.hero-v2-title")?.text()?.takeIf { it.isNotBlank() }?.let { title = it }
            document.selectFirst(".view-wrap .text-muted, .author, .writer")?.text()?.replace("글/그림", "")?.takeIf { it.isNotBlank() }?.let { author = it.trim() }
            document.selectFirst(".view-wrap .desc, .summary, .content")?.text()?.takeIf { it.isNotBlank() }?.let { description = it }
            document.selectFirst(".view-wrap .view-img img, .cover img, .thumb img")?.attr("abs:src")?.takeIf { it.isNotBlank() }?.let { thumbnail_url = it }

            val statText = document.select(".view-wrap, .pill-status, .status, .state").text()
            if (statText.contains("연재")) {
                status = SManga.ONGOING
            } else if (statText.contains("완결")) {
                status = SManga.COMPLETED
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        val baseUrl = response.request.url.toString().substringBefore("?")
        val dateFormat = java.text.SimpleDateFormat("yy.MM.dd", java.util.Locale.KOREA)

        fun parsePage(doc: org.jsoup.nodes.Document) {
            val rows = doc.select(".list-body .list-item, .ep-list-v2 .ep-row-v2")
            for (row in rows) {
                val a = row.selectFirst("a[href]") ?: continue
                val href = a.attr("href")

                if (href.contains("?page=") || href.contains("bo_table=") || !href.contains("/novel/")) continue

                var title = a.ownText().trim()
                if (title.isEmpty()) {
                    title = a.text().trim()
                }
                title = title.replace("최신화부터", "").replace("1화부터 보기", "").replace("->", "").trim()
                if (title.isBlank()) continue

                chapters.add(
                    SChapter.create().apply {
                        setUrlWithoutDomain(href)
                        name = title

                        if (row.select("i.fa-lock, img[src*=lock]").isNotEmpty()) {
                            scanlator = "🔒"
                        }

                        val dateText = row.selectFirst(".wr-date, .date, .time")?.text() ?: ""
                        val dateMatch = Regex("""(\d{2})\.\s*(\d{2})\.\s*(\d{2})""").find(dateText)
                        if (dateMatch != null) {
                            try {
                                val (y, m, d) = dateMatch.destructured
                                date_upload = dateFormat.parse("$y.$m.$d")?.time ?: 0L
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    },
                )
            }
        }

        parsePage(document)

        var maxPage = 1
        val pageLinks = document.select(".pagination a, .pg a")
        for (a in pageLinks) {
            val pMatch = Regex("""page=(\d+)""").find(a.attr("href"))
            if (pMatch != null) {
                val p = pMatch.groupValues[1].toInt()
                if (p > maxPage) maxPage = p
            }
        }

        if (maxPage > 1) {
            val limit = minOf(maxPage, 50)
            for (p in 2..limit) {
                try {
                    val nextDoc = client.newCall(GET("$baseUrl?page=$p", headers)).execute().asJsoup()
                    parsePage(nextDoc)
                } catch (e: Exception) {
                    break
                }
            }
        }

        if (chapters.isNotEmpty()) return chapters.distinctBy { it.url }.reversed()

        throw Exception("회차 목록을 불러올 수 없습니다. WebView로 확인해주세요.")
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(rootUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        throw Exception("❗ 미혼 앱은 그림 전용입니다.\n우측 상단 🌐지구본 모양(WebView) 버튼을 누르면 해당 회차 글로 바로 연결됩니다!")
    }

    override fun getFilterList() = FilterList(
        NvSortFilter(),
        NvStatusFilter(),
        NvGenreFilter(),
        Filter.Header(""),
    )
}

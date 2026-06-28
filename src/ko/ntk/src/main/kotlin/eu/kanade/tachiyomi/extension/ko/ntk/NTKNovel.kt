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
import java.text.SimpleDateFormat
import java.util.Locale

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
                title = "❗오류 원인: " + bodyString.take(150)
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
            document.selectFirst("h1, .hero-v2-title, .novel-title, .title")?.text()?.takeIf { it.isNotBlank() }?.let { title = it }
            document.selectFirst(".hero-v2-author a, .author, .writer")?.text()?.takeIf { it.isNotBlank() }?.let { author = it }
            document.selectFirst(".hero-v2-desc, .desc, .summary, .content")?.text()?.takeIf { it.isNotBlank() }?.let { description = it }
            document.selectFirst(".hero-v2-thumb img, .thumb img, .cover img")?.attr("abs:src")?.takeIf { it.isNotBlank() }?.let { thumbnail_url = it }

            val statText = document.select(".pill-status, .status, .state").text()
            if (statText.contains("연재")) {
                status = SManga.ONGOING
            } else if (statText.contains("완결")) {
                status = SManga.COMPLETED
            }
        }
    }

    // --- 1. 페이지 순회(페이징) 및 날짜 완벽 파싱 ---
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        val baseUrl = response.request.url.toString().substringBefore("?")
        val dateFormat = SimpleDateFormat("yy.MM.dd", Locale.KOREA)

        fun extractChapters(doc: org.jsoup.nodes.Document) {
            val rows = doc.select("li.ep-row-v2, li.list-item, div.ep-row, div.list-item, tr.list-item, li.novel-item")
            for (row in rows) {
                val a = row.selectFirst("a[href]") ?: continue
                val href = a.attr("href")

                if (href.isBlank() || href.contains("?page=") || href.contains("/author/") || href.contains("/tag/")) continue

                val titleElem = row.selectFirst("strong, .title, .item-title, .ep-row-v2-title")
                val title = (titleElem?.text() ?: a.text()).trim()
                if (title.isBlank()) continue

                chapters.add(
                    SChapter.create().apply {
                        setUrlWithoutDomain(href)
                        name = title
                        if (row.select("i.fa-lock, img[src*=lock], .badge:contains(P)").isNotEmpty()) {
                            scanlator = "🔒"
                        }

                        // 날짜 포맷 (22. 04. 16. 형태) 찾아내기
                        val dateText = row.selectFirst(".date, .time, .ep-row-v2-date, .text-right, span")?.text() ?: row.text()
                        val dateMatch = Regex("""(\d{2})\.\s?(\d{2})\.\s?(\d{2})""").find(dateText)
                        if (dateMatch != null) {
                            try {
                                val (y, m, d) = dateMatch.destructured
                                date_upload = dateFormat.parse("$y.$m.$d")?.time ?: 0L
                            } catch (e: Exception) {}
                        }
                    },
                )
            }
        }

        // 1페이지 추출
        extractChapters(document)

        // 누락된 회차(2, 3페이지...) 끝까지 수집
        val maxPage = document.select(".pagination a[href*=?page=], .pg_wrap a[href*=?page=]")
            .mapNotNull { it.attr("href").substringAfter("?page=").substringBefore("&").toIntOrNull() }
            .maxOrNull() ?: 1

        if (maxPage > 1) {
            for (p in 2..maxPage) {
                try {
                    val nextResp = client.newCall(GET("$baseUrl?page=$p", headers)).execute()
                    extractChapters(nextResp.asJsoup())
                } catch (e: Exception) {
                    break
                }
            }
        }

        if (chapters.isNotEmpty()) return chapters.distinctBy { it.url }.reversed()

        throw Exception("회차 목록이 숨겨져 있습니다. 우측 상단 WebView(지구본)를 열어주세요.")
    }

    // --- 2. 무한 로딩(타임아웃) 제거 및 안내창 띄우기 ---
    override fun pageListRequest(chapter: SChapter): Request {
        // 이미지를 기다리는 대기열 기능을 강제로 꺼서 30초 로딩 에러를 없앱니다.
        return GET(rootUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        // 소설 텍스트를 읽을 수 없음을 즉시 알립니다.
        throw Exception("❗ 미혼(Mihon) 앱은 그림 전용이라 '소설 글자'를 화면에 띄울 수 없습니다.\n\n소설을 읽으시려면 화면 우측 상단의 [🌐지구본 모양 버튼(WebView)]을 눌러주세요!")
    }

    override fun getFilterList() = FilterList(
        NvSortFilter(),
        NvStatusFilter(),
        NvGenreFilter(),
        Filter.Header(""),
    )
}

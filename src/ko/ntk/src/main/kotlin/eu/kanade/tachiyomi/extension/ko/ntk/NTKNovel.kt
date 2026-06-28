package eu.kanade.tachiyomi.extension.ko.ntk

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
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

    // --- 소설 전용 게시판 탐지 기능 추가 ---

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            // 소설 페이지에서 내용이 발견될 때만 입력합니다. (못 찾으면 기존 정보를 보존합니다!)
            document.selectFirst("h1, .hero-v2-title, .novel-title, .title")?.text()?.takeIf { it.isNotBlank() }?.let { title = it }
            document.selectFirst(".hero-v2-author a, .author, .writer")?.text()?.takeIf { it.isNotBlank() }?.let { author = it }
            document.selectFirst(".hero-v2-desc, .desc, .summary, .content")?.text()?.takeIf { it.isNotBlank() }?.let { description = it }
            document.selectFirst(".hero-v2-thumb img, .thumb img, .cover img")?.attr("abs:src")?.takeIf { it.isNotBlank() }?.let { thumbnail_url = it }
            
            val statText = document.select(".pill-status, .status, .state").text()
            if (statText.contains("연재")) status = SManga.ONGOING
            else if (statText.contains("완결")) status = SManga.COMPLETED
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        // 1. 소설 게시판에서 자주 쓰이는 회차 목록 디자인을 광범위하게 수집합니다.
        val rows = document.select("li.ep-row-v2, li.list-item, div.ep-row, div.list-item, tr.list-item, li.novel-item")
        
        for (row in rows) {
            val a = row.selectFirst("a[href]") ?: continue
            val href = a.attr("href")
            
            // 페이지 이동 버튼이나 잡다한 링크는 제외
            if (href.isBlank() || href.contains("?page=") || href.contains("/author/") || href.contains("/tag/")) continue
            
            val titleElem = row.selectFirst("strong, .title, .item-title, .ep-row-v2-title")
            val title = (titleElem?.text() ?: a.text()).trim()
            if (title.isBlank()) continue

            chapters.add(SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = title
                if (row.select("i.fa-lock, img[src*=lock], .badge:contains(P)").isNotEmpty()) {
                    scanlator = "🔒" // 잠긴 화 표시
                }
            })
        }

        if (chapters.isNotEmpty()) return chapters.distinctBy { it.url }.reversed()

        // 2. 클래스 이름이 아예 다를 경우를 대비해, 회차 뷰어 링크 모양만 보고 무식하게 다 잡아냅니다.
        val allLinks = document.select("a[href]")
        for (a in allLinks) {
            val href = a.attr("href")
            if (href.matches(Regex(".*(/novel/viewer/\\d+|/novel/ep/\\d+|/novel/\\d+/\\d+|/novel/\\d+\\?book_def).*"))) {
                val title = a.text().trim()
                if (title.isNotBlank() && !title.contains("다음화") && !title.contains("이전화") && !title.contains("목록")) {
                    chapters.add(SChapter.create().apply {
                        setUrlWithoutDomain(href)
                        name = title
                    })
                }
            }
        }

        if (chapters.isNotEmpty()) return chapters.distinctBy { it.url }.reversed()

        // 3. 위 방법으로도 안 나오면, 에러 메시지를 띄웁니다.
        throw Exception("회차 목록이 숨겨져 있습니다. 우측 상단 WebView(지구본)를 열어서 사람 인증(캡차)을 풀어주세요.")
    }

    override fun getFilterList() = FilterList(
        NvSortFilter(),
        NvStatusFilter(),
        NvGenreFilter(),
        Filter.Header(""),
    )
}

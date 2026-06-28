package eu.kanade.tachiyomi.extension.ko.ntk

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class NTKNovel : NTKBase("NTK Novel", "novel") {

    @Serializable
    private data class SafeWorksResponse(
        // 만화의 works 대신 소설 전용인 novels 상자로 받습니다!
        val novels: List<SafeWork> = emptyList(), 
        val hasMore: Boolean = false,
    )

    @Serializable
    private data class SafeWork(
        // 소설 서버는 번호를 id라고 부릅니다!
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
            
            // 이제 novels 상자 안에서 데이터를 꺼내옵니다.
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

    override fun getFilterList() = FilterList(
        NvSortFilter(),
        NvStatusFilter(),
        NvGenreFilter(),
        Filter.Header(""),
    )
}

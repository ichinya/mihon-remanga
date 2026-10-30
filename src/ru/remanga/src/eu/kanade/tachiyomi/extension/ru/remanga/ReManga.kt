package eu.kanade.tachiyomi.extension.ru.remanga

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ReManga :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val showLocked get() = preferences.getBoolean(SHOW_LOCKED_PREF, true)

    private val showFreeDate get() = preferences.getBoolean(SHOW_FREE_DATE_PREF, true)

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3, 1.seconds)
        .addInterceptor(DiagnosticsInterceptor)
        .addInterceptor(ImageCdnFallbackInterceptor)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_LOCKED_PREF
            title = "Show locked chapters"
            summary = "Show paid chapters that require a purchase or premium subscription"
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_FREE_DATE_PREF
            title = "Show free publication date"
            summary = "Show the date when locked chapters become free to read"
            setDefaultValue(true)
        }.also(screen::addPreference)

        addDiagnosticsPreferences(screen)
    }

    // The keiyoushi stubs only expose DialogPreference subclasses with a Context ctor, and tapping
    // any of them opens its dialog (crashing if the row has no key). Plain Preference — no dialog,
    // pure click handling — is only present in the app at runtime, so build it reflectively.
    private fun addDiagnosticsPreferences(screen: PreferenceScreen) {
        val context = screen.context

        fun actionPreference(key: String, title: String, summary: String, action: () -> Unit) {
            val preference = runCatching {
                Preference::class.java.getConstructor(Context::class.java).newInstance(context) as Preference
            }.getOrNull() ?: return
            preference.apply {
                this.key = key
                this.title = title
                this.summary = summary
                setOnPreferenceClickListener {
                    action()
                    true
                }
            }
            screen.addPreference(preference)
        }

        actionPreference(
            key = "diagnostics_last_error",
            title = "Последняя ошибка",
            summary = Diagnostics.lastSummary() ?: "ошибок не зафиксировано",
            action = {},
        )
        actionPreference(
            key = "diagnostics_share_log",
            title = "Лог диагностики — поделиться",
            summary = "Файл: ${Diagnostics.logPath()}",
            action = { shareLog(context) },
        )
        actionPreference(
            key = "diagnostics_clear_log",
            title = "Очистить лог диагностики",
            summary = "Удаляет все сохранённые записи",
            action = {
                Diagnostics.clear()
                Toast.makeText(context, "Лог очищен", Toast.LENGTH_SHORT).show()
            },
        )
    }

    private fun shareLog(context: Context) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Remanga diagnostics log")
            putExtra(Intent.EXTRA_TEXT, Diagnostics.readLog())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Лог диагностики Remanga"))
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchCatalogList(page) {
        addQueryParameter("ordering", "-views")
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchCatalogList(page) {
        addQueryParameter("ordering", "-chapter_date")
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = fetchCatalogList(page) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) addQueryParameter("query", trimmed)

        filters.firstInstanceOrNull<SortFilter>()?.selected?.takeIf { it.isNotEmpty() }
            ?.let { addQueryParameter("ordering", it) }
        filters.firstInstanceOrNull<TypeFilter>()?.selected?.takeIf { it.isNotEmpty() }
            ?.let { addQueryParameter("type", it) }
        filters.firstInstanceOrNull<AgeRatingFilter>()?.selected?.takeIf { it.isNotEmpty() }
            ?.let { addQueryParameter("age_limit", it) }
    }

    override fun getFilterList(data: JsonElement?): FilterList = filterList()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val title = client.get(titleApiUrl(manga.url)).parseAs<TitleDetailDto>().content
        val updatedManga = if (fetchDetails) title.toSManga(manga, baseUrl) else manga
        val updatedChapters = if (fetchChapters) fetchChapters(title, manga.url) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() !in setOf("titles", "manga")) return null
        val dir = url.pathSegments.lastOrNull() ?: return null
        val manga = SManga.create().apply { this.url = dir }
        return client.get(titleApiUrl(dir)).parseAs<TitleDetailDto>().content.toSManga(manga, baseUrl)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = "$baseUrl${chapter.url}".toHttpUrlOrNull()?.queryParameter("chapter")
            ?: return emptyList()
        val response = client.get(chapterApiUrl(chapterId), ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }
        return response.use { it.parseAs<ChapterDetailDto>() }
            .content
            .pages
            .flatten()
            .sortedBy { it.id }
            .mapIndexed { index, page -> Page(index, imageUrl = page.link.toAbsoluteUrl()) }
    }

    private suspend fun fetchCatalogList(page: Int, params: HttpUrl.Builder.() -> Unit): MangasPage {
        val dto = client.get(
            "$API_URL/search/catalog/".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("count", PAGE_SIZE.toString())
                .apply(params)
                .build(),
        ).parseAs<CatalogDto>()
        val mangas = dto.content.mapNotNull { it.toSManga(baseUrl) }
        return MangasPage(mangas, dto.props.hasNextPage())
    }

    private suspend fun fetchChapters(title: TitleContentDto, dir: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        for (branch in title.branches) {
            var page = 1
            while (true) {
                val items = client.get(chaptersApiUrl(branch.id, page)).parseAs<ChaptersDto>().content
                if (items.isEmpty()) break
                chapters += items.mapNotNull { item ->
                    if (item.isLocked && !showLocked) return@mapNotNull null
                    item.toSChapter(dir, baseUrl, showFreeDate, branch.publisherName())
                }
                if (items.size < CHAPTERS_PER_PAGE) break
                page++
            }
        }
        return chapters
    }

    private fun titleApiUrl(dir: String): HttpUrl = "$API_URL/titles/".toHttpUrl().newBuilder().addPathSegment(dir).build()

    private fun chaptersApiUrl(branchId: Long, page: Int): HttpUrl = "$API_URL/titles/chapters/".toHttpUrl().newBuilder()
        .addQueryParameter("branch_id", branchId.toString())
        .addQueryParameter("ordering", "-index")
        .addQueryParameter("page", page.toString())
        .addQueryParameter("count", CHAPTERS_PER_PAGE.toString())
        .addQueryParameter("user_data", "1")
        .build()

    private fun chapterApiUrl(chapterId: String): String = "$API_URL/titles/chapters/$chapterId/"

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("http://") || startsWith("https://") -> this
        startsWith("/") -> baseUrl + this
        else -> "$baseUrl/$this"
    }

    private companion object {
        const val API_URL = "https://api.remanga.org/api"
        const val PAGE_SIZE = 30
        const val CHAPTERS_PER_PAGE = 100
        const val SHOW_LOCKED_PREF = "show_locked_chapters"
        const val SHOW_FREE_DATE_PREF = "show_free_publication_date"
    }
}

/**
 * Remanga's image CDN (Cloudflare) intermittently blocks readers with 403/429; the same images
 * are served from the DDoS-Guard mirror.
 */
private object ImageCdnFallbackInterceptor : Interceptor {
    private const val PRIMARY_HOST = "img.reimg.org"
    private const val FALLBACK_HOST = "img-reserve.reimg2.org"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if ((response.code == 403 || response.code == 429) && request.url.host == PRIMARY_HOST) {
            response.close()
            val fallbackUrl = request.url.newBuilder().host(FALLBACK_HOST).build()
            return chain.proceed(request.newBuilder().url(fallbackUrl).build())
        }
        return response
    }
}

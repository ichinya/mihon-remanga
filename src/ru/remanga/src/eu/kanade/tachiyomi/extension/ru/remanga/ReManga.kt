package eu.kanade.tachiyomi.extension.ru.remanga

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.preference.EditTextPreference
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
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ReManga :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val showLocked get() = preferences.getBoolean(SHOW_LOCKED_PREF, true)

    private val showFreeDate get() = preferences.getBoolean(SHOW_FREE_DATE_PREF, true)

    private val useExManga get() = preferences.getBoolean(EX_PAID_PREF, true)

    private val exMangaDomain: String
        get() = preferences.getString(EX_DOMAIN_PREF, DEFAULT_EX_DOMAIN)?.trim()?.trimEnd('/')
            ?: DEFAULT_EX_DOMAIN

    // The magic plain UA is what the ExManga backend expects; the app's browser UA gets rejected.
    private fun exHeaders(): okhttp3.Headers = okhttp3.Headers.Builder()
        .set("User-Agent", "Tachiyomi")
        .set("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .set("Referer", "$baseUrl/")
        .apply {
            preferences.getString(EX_TOKEN_PREF, null)?.takeIf { it.isNotBlank() }?.let {
                set("Authorization", "Bearer $it")
            }
        }
        .build()

    /**
     * Some ExManga chapters require an account. Logs in with the stored email/password once and
     * caches the token; returns false when credentials are missing or the login is rejected.
     */
    private suspend fun ensureExToken(): Boolean {
        if (!preferences.getString(EX_TOKEN_PREF, null).isNullOrBlank()) return true
        val email = preferences.getString(EX_EMAIL_PREF, null)?.trim().orEmpty()
        val password = preferences.getString(EX_PASSWORD_PREF, null).orEmpty()
        if (email.isEmpty() || password.isEmpty()) {
            Diagnostics.info("exmanga: 401 требует аккаунт, но email/пароль не заданы")
            return false
        }
        return runCatching {
            val response = client.post(
                "$exMangaDomain/auth/login",
                exHeaders(),
                ExLoginDto(email, password).toJsonRequestBody(),
            )
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Diagnostics.info("exmanga: login failed HTTP ${resp.code}")
                    false
                } else {
                    val data = resp.parseAs<ExWrapperDto<JsonElement>>().data
                    val token = when (data) {
                        is JsonPrimitive -> data.content
                        is JsonObject ->
                            (data["token"] ?: data["accessToken"] ?: data["access_token"] ?: data["jwt"])
                                ?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                    if (token.isNullOrBlank()) {
                        Diagnostics.info("exmanga: login response has no token")
                        false
                    } else {
                        preferences.edit().putString(EX_TOKEN_PREF, token).apply()
                        Diagnostics.info("exmanga: login OK, token saved")
                        true
                    }
                }
            }
        }.getOrDefault(false)
    }

    /** Library entries migrated from the old extension store "/api/titles/{id-or-dir}/" — extract the key. */
    private fun String.toMangaDir(): String = substringAfterLast("/titles/", this)
        .substringBefore("?")
        .trim('/')
        .substringBefore("/")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3, 1.seconds)
        .addGlobalSignR46TrustAnchor()
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

        SwitchPreferenceCompat(screen.context).apply {
            key = EX_PAID_PREF
            title = "Читать платные главы через ExManga"
            summary = "Платные главы, имеющиеся на сестринском сайте ExManga, открываются оттуда"
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = EX_DOMAIN_PREF
            title = "API-домен ExManga"
            summary = "По умолчанию $DEFAULT_EX_DOMAIN"
            setDefaultValue(DEFAULT_EX_DOMAIN)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = EX_EMAIL_PREF
            title = "ExManga: email аккаунта"
            summary = "Нужен для платных глав, закрытых аккаунтом"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().remove(EX_TOKEN_PREF).apply()
                newValue is String
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = EX_PASSWORD_PREF
            title = "ExManga: пароль аккаунта"
            summary = if (preferences.getString(EX_TOKEN_PREF, null) != null) "вход выполнен" else "не задан"
            setDefaultValue("")
            setOnBindEditTextListener { it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().remove(EX_TOKEN_PREF).apply()
                newValue is String
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = EX_NAME_PREF
            title = "ExManga: имя для регистрации"
            summary = "До 12 символов — только если аккаунта ещё нет"
            setDefaultValue("")
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
        actionPreference(
            key = "exmanga_register",
            title = "Зарегистрироваться в ExManga",
            summary = "Создаёт аккаунт по имени/email/паролю выше и входит в него",
        ) { registerExAccount(context) }
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

    /**
     * Creates an ExManga account from the name/email/password preferences. Registration is
     * normally done in the browser extension's popup; phone users don't have that, so it is
     * replicated here. The API answers with a JWT in `data` on success, or `success:false`
     * with the reason in `data`.
     */
    private fun registerExAccount(context: Context) {
        val name = preferences.getString(EX_NAME_PREF, null)?.trim().orEmpty()
        val email = preferences.getString(EX_EMAIL_PREF, null)?.trim().orEmpty()
        val password = preferences.getString(EX_PASSWORD_PREF, null).orEmpty()
        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
            Toast.makeText(context, "Заполните имя, email и пароль выше", Toast.LENGTH_LONG).show()
            return
        }
        if (name.length > 12 || password.length < 6) {
            Toast.makeText(context, "Имя — до 12 символов, пароль — от 6 символов", Toast.LENGTH_LONG).show()
            return
        }
        preferences.edit().remove(EX_TOKEN_PREF).apply()
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                client.post("$exMangaDomain/auth/register", exHeaders(), ExRegisterDto(name, email, password).toJsonRequestBody())
                    .use { it.parseAs<ExAuthResponseDto>() }
            }
            val (ok, message) = result.fold(
                onSuccess = { resp ->
                    val token = (resp.data as? JsonPrimitive)?.content
                    if (resp.success && !token.isNullOrBlank()) {
                        preferences.edit().putString(EX_TOKEN_PREF, token).apply()
                        true to "Аккаунт создан, вход выполнен"
                    } else {
                        false to "Ошибка: ${(resp.data as? JsonPrimitive)?.content ?: "ответ сервера не распознан"}"
                    }
                },
                onFailure = { false to "Ошибка сети: ${it.message?.take(80)}" },
            )
            Diagnostics.info("exmanga: register ${if (ok) "OK" else "failed: $message"}")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
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
        val dir = manga.url.toMangaDir()
        val title = client.get(titleApiUrl(dir)).parseAs<TitleDetailDto>().content
        val updatedManga = if (fetchDetails) title.toSManga(manga, baseUrl) else manga
        val updatedChapters = if (fetchChapters) fetchChapters(title, dir) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter { it.isNotBlank() }
        // Accepts current (/titles/{dir}, /manga/{dir}) and legacy (/api/titles/{id-or-dir}/) paths.
        if ("titles" !in segments && "manga" !in segments) return null
        val dir = segments.lastOrNull() ?: return null
        val manga = SManga.create().apply { this.url = dir }
        return client.get(titleApiUrl(dir)).parseAs<TitleDetailDto>().content.toSManga(manga, baseUrl)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.scanlator == "exmanga") return fetchExPages(chapter)
        val chapterId = "$baseUrl${chapter.url}".toHttpUrlOrNull()?.queryParameter("chapter")
            ?: return emptyList()
        val response = client.get(chapterApiUrl(chapterId), ensureSuccess = false)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code == 401 || code == 403) {
                throw Exception("Глава платная (покупка на remanga) или требует входа на сайт; главы с ExManga читаются через ExManga")
            }
            return emptyList()
        }
        return response.use { it.parseAs<ChapterDetailDto>() }
            .content
            .pages
            .flatten()
            .sortedBy { it.id }
            .mapIndexed { index, page -> Page(index, imageUrl = page.link.toAbsoluteUrl()) }
    }

    private suspend fun fetchExPages(chapter: SChapter): List<Page> {
        val exId = "$exMangaDomain${chapter.url}".toHttpUrlOrNull()?.queryParameter("id")
            ?: return emptyList()
        var response = client.get("$exMangaDomain/chapter?id=$exId", exHeaders(), ensureSuccess = false)
        // Some chapters are account-gated: log in with the stored credentials and retry once.
        if (response.code == 401 && ensureExToken()) {
            response.close()
            response = client.get("$exMangaDomain/chapter?id=$exId", exHeaders(), ensureSuccess = false)
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code == 401) {
                throw Exception("Глава доступна только с аккаунтом ExManga — укажите email/пароль в настройках источника ⚙️")
            }
            return emptyList()
        }
        return response.use { it.parseAs<ExWrapperDto<List<List<ExPageDto>>>>() }
            .data
            .flatten()
            .filter { it.height > 10 }
            .mapIndexed { index, page ->
                val link = if (page.link.startsWith("http")) page.link else "$exMangaDomain/${page.link.trimStart('/')}"
                // "#ex" marks ExManga images for imageRequest(); fragments are never sent to servers.
                Page(index, imageUrl = "$link#ex")
            }
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
        val exDomain = if (useExManga && title.id != 0L) exMangaDomain else null
        val exChapters = if (exDomain != null) {
            runCatching {
                val response = client.get("$exDomain/manga/history?id=${title.id}", exHeaders(), ensureSuccess = false)
                response.use { response ->
                    if (response.isSuccessful) {
                        response.parseAs<ExWrapperDto<List<ExChapterDto>>>().data
                    } else {
                        emptyList()
                    }
                }
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        val dateSamples = mutableListOf<String>()
        val chapters = mutableListOf<SChapter>()
        for (branch in title.branches) {
            var page = 1
            while (true) {
                val items = client.get(chaptersApiUrl(branch.id, page)).parseAs<ChaptersDto>().content
                if (items.isEmpty()) break
                chapters += items.mapNotNull { item ->
                    if (item.isLocked && !showLocked) return@mapNotNull null
                    val chapter = item.toSChapter(dir, baseUrl, showFreeDate, branch.publisherName(), exDomain, exChapters)
                    if (dateSamples.size < 3 && item.rawUploadDate != null) {
                        dateSamples += "raw=${item.rawUploadDate} parsed=${chapter.date_upload}"
                    }
                    chapter
                }
                if (items.size < CHAPTERS_PER_PAGE) break
                page++
            }
        }
        if (dateSamples.isNotEmpty()) Diagnostics.info("dates: ${dateSamples.joinToString(" | ")}")
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
        const val EX_PAID_PREF = "read_paid_via_exmanga"
        const val EX_DOMAIN_PREF = "exmanga_domain"
        const val DEFAULT_EX_DOMAIN = "https://api.exmanga.org"
        const val EX_EMAIL_PREF = "exmanga_email"
        const val EX_PASSWORD_PREF = "exmanga_password"
        const val EX_TOKEN_PREF = "exmanga_token"
        const val EX_NAME_PREF = "exmanga_name"
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: return super.imageRequest(page)
        if (imageUrl.endsWith("#ex")) {
            // ExManga pages: the magic plain UA is what their server expects.
            val url = imageUrl.removeSuffix("#ex")
            return Request.Builder().url(url).headers(exHeaders()).build()
        }
        return super.imageRequest(page)
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

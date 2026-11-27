package eu.kanade.tachiyomi.extension.ru.remanga

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.DEFAULT_CACHE_CONTROL
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
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

    private sealed interface ExLogin {
        data object Cached : ExLogin
        data object Authenticated : ExLogin
        class Failed(val reason: String, val readerMessage: String = reason) : ExLogin
    }

    /**
     * Some ExManga chapters require an account. Logs in with the stored email/password once and
     * caches the token. [retryOnAuthFailure] clears a rejected token and logs in again.
     */
    private suspend fun ensureExToken(retryOnAuthFailure: Boolean = false, authRequest: ExChapterAuthRequest? = null): ExLogin {
        if (retryOnAuthFailure) {
            preferences.edit().remove(EX_TOKEN_PREF).apply()
        } else if (!preferences.getString(EX_TOKEN_PREF, null).isNullOrBlank()) {
            return ExLogin.Cached
        }
        val email = preferences.getString(EX_EMAIL_PREF, null)?.trim().orEmpty()
        val password = preferences.getString(EX_PASSWORD_PREF, null).orEmpty()
        if (email.isEmpty() || password.isEmpty()) {
            val missing = listOfNotNull("email".takeIf { email.isEmpty() }, "пароль".takeIf { password.isEmpty() })
            val message = "Для входа в ExManga заполните ${missing.joinToString(" и ")} в настройках источника."
            return ExLogin.Failed(message)
        }
        val trace = RequestTrace()
        return try {
            val request = Request.Builder().url("$exMangaDomain/auth/login").headers(exHeaders())
                .post(ExLoginDto(email, password).toJsonRequestBody())
                .tag(ExChapterAuthRequest::class.java, authRequest)
                .tag(RequestTrace::class.java, trace)
                .build()
            client.newCall(request).await().use { resp ->
                val failureCode = resp.authFailureCode()
                val status = "POST /auth/login → ${resp.authStatus(failureCode)}"
                if (!resp.isSuccessful) {
                    val message = loginRejection(failureCode, "Не удалось войти в ExManga (HTTP ${resp.code}).")
                    return ExLogin.Failed(status, message)
                }
                val body = try {
                    resp.parseAs<ExAuthResponseDto>()
                } catch (_: SerializationException) {
                    return ExLogin.Failed("$status, ответ не соответствует формату входа.", "Не удалось прочитать ответ ExManga на запрос входа.")
                }
                if (body.success == false) {
                    val reason = authErrorCode(body.data) ?: "success=false"
                    val message = loginRejection(reason, "ExManga отклонила запрос входа.")
                    return ExLogin.Failed("$status, сервер отклонил вход ($reason).", message)
                }
                val value = when (val data = body.data) {
                    is JsonObject -> data["token"] ?: data["accessToken"] ?: data["access_token"] ?: data["jwt"]
                    else -> data
                }
                val token = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                if (token.isNullOrBlank()) return ExLogin.Failed("$status, в ответе нет токена.", "В ответе ExManga нет токена для входа.")
                preferences.edit().putString(EX_TOKEN_PREF, token).apply()
                ExLogin.Authenticated
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            if (trace.isCanceled(e)) throw e
            ExLogin.Failed("POST /auth/login: ${e.javaClass.simpleName}. ${trace.describe(e)}", "Не удалось завершить вход в ExManga из-за ошибки сети.")
        } catch (_: IllegalArgumentException) {
            ExLogin.Failed("Не удалось сформировать запрос входа в ExManga.")
        }
    }

    private fun loginRejection(reason: String?, fallback: String): String = if (reason == "invalid_credentials") {
        "ExManga отклонила данные для входа. Проверьте email и пароль в настройках источника."
    } else {
        fallback
    }

    /** Extracts the human-readable reason from an ExManga error body ("data" field), if present. */
    private fun exReason(resp: Response): String = runCatching {
        val data = resp.parseAs<ExAuthResponseDto>().data
        (data as? JsonPrimitive)?.content ?: "HTTP ${resp.code}"
    }.getOrDefault("HTTP ${resp.code}")

    /** Library entries migrated from the old extension store "/api/titles/{id-or-dir}/" — extract the key. */
    private fun String.toMangaDir(): String = substringAfterLast("/titles/", this)
        .substringBefore("?")
        .trim('/')
        .substringBefore("/")

    /** Remembers distinct titles touched in this installation (usage analytics). */
    private fun trackManga(dir: String) {
        val dirs = preferences.getStringSet(STAT_MANGA_PREF, emptySet())?.toMutableSet() ?: return
        if (dirs.add(dir)) {
            preferences.edit().putStringSet(STAT_MANGA_PREF, dirs).apply()
        }
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3, 1.seconds)
        .addGlobalSignR46TrustAnchor()
        .addInterceptor(DiagnosticsInterceptor)
        .addNetworkInterceptor(RequestTrace.networkInterceptor)
        .addInterceptor(ImageCdnFallbackInterceptor)
        .addInterceptor(HostBackoffInterceptor())
        .also {
            Diagnostics.autoUploadEnabled = preferences.getBoolean(AUTO_UPLOAD_PREF, false)
            // Пинг аналитики при первом обращении к источнику после запуска приложения.
            CoroutineScope(Dispatchers.IO).launch {
                Diagnostics.pingAsync(
                    mangas = preferences.getStringSet(STAT_MANGA_PREF, emptySet())?.size ?: 0,
                    chapters = preferences.getInt(STAT_CHAPTERS_PREF, 0),
                )
            }
        }

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

        SwitchPreferenceCompat(screen.context).apply {
            key = AUTO_UPLOAD_PREF
            title = "Автоотправка логов на сервер"
            summary = "При ошибке лог сам уходит на logging.tw1.ru (не чаще раза в 5 минут)"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                Diagnostics.autoUploadEnabled = newValue as Boolean
                true
            }
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
            summary = "Папка: ${Diagnostics.logsDir()}",
            action = { shareLog(context) },
        )
        actionPreference(
            key = "diagnostics_upload",
            title = "Отправить лог на сервер",
            summary = "https://logging.tw1.ru/ — сразу, без ожидания",
        ) {
            Toast.makeText(context, "Отправка…", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                val (ok, msg) = Diagnostics.uploadNow()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
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
            putExtra(Intent.EXTRA_TEXT, Diagnostics.exportTxt())
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
            val (ok, message) = runCatching {
                client.post(
                    "$exMangaDomain/auth/register",
                    exHeaders(),
                    ExRegisterDto(name, email, password).toJsonRequestBody(),
                    ensureSuccess = false,
                ).use { resp ->
                    if (!resp.isSuccessful) {
                        false to exReason(resp)
                    } else {
                        val body = resp.parseAs<ExAuthResponseDto>()
                        val token = (body.data as? JsonPrimitive)?.content
                        if (body.success == true && !token.isNullOrBlank()) {
                            preferences.edit().putString(EX_TOKEN_PREF, token).apply()
                            true to "Аккаунт создан, вход выполнен"
                        } else {
                            false to "Ошибка: ${(body.data as? JsonPrimitive)?.content ?: "ответ сервера не распознан"}"
                        }
                    }
                }
            }.getOrElse { e -> false to "Ошибка сети: ${e.message?.take(80)}" }
            if (!ok) error("exmanga: register failed: $message")
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
    ): MangasPage {
        val trimmed = query.trim()
        // The v2 catalog ignores `query` — text search lives on its own endpoint and ignores filters.
        if (trimmed.isNotEmpty()) return fetchSearchList(page, trimmed)
        return fetchCatalogList(page) {
            filters.firstInstanceOrNull<SortFilter>()?.selected?.takeIf { it.isNotEmpty() }
                ?.let { addQueryParameter("ordering", it) }
            filters.firstInstanceOrNull<TypeFilter>()?.selected?.takeIf { it.isNotEmpty() }
                ?.let { addQueryParameter("type", it) }
            filters.firstInstanceOrNull<AgeRatingFilter>()?.selected?.takeIf { it.isNotEmpty() }
                ?.let { addQueryParameter("age_limit", it) }
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = filterList()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dir = manga.url.toMangaDir()
        val title = fetchTitle(dir)
        trackManga(dir)
        val updatedManga = if (fetchDetails) title.toSManga(manga, baseUrl) else manga
        Diagnostics.ctxManga = updatedManga.title
        Diagnostics.ctxChapter = null
        val updatedChapters = if (fetchChapters) fetchChapters(title, dir) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchTitle(dir: String): TitleContentDto {
        val response = client.get(titleApiUrl(dir), ensureSuccess = false)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code == 404) {
                throw Exception("ReManga не нашла тайтл по сохранённому адресу. Поищите его по названию. Если найдёте новую карточку, перенесите прогресс через миграцию в Mihon.")
            }
            throw Exception("Ошибка загрузки тайтла: HTTP $code")
        }
        return response.use { it.parseAs<TitleContentDto>() }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter { it.isNotBlank() }
        // Accepts current (/titles/{dir}, /manga/{dir}) and legacy (/api/titles/{id-or-dir}/) paths.
        if ("titles" !in segments && "manga" !in segments) return null
        val dir = segments.lastOrNull() ?: return null
        val manga = SManga.create().apply { this.url = dir }
        val title = fetchTitle(dir)
        if (!title.hasData) return null
        return title.toSManga(manga, baseUrl)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        Diagnostics.ctxChapter = chapter.name
        preferences.edit().putInt(STAT_CHAPTERS_PREF, preferences.getInt(STAT_CHAPTERS_PREF, 0) + 1).apply()
        if (chapter.scanlator == "exmanga") return fetchExPages(chapter)
        // url formats: "/manga/{titleId}ch{chapterId}" (ours + old extension) or "/titles/{dir}?chapter={id}"
        val chapterId = if ("?" in chapter.url) {
            "$baseUrl${chapter.url}".toHttpUrlOrNull()?.queryParameter("chapter")
        } else {
            chapterChapterId(chapter.url)
        } ?: return emptyList()
        val response = client.get(chapterApiUrl(chapterId), ensureSuccess = false)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code == 401 || code == 403) {
                throw Exception("Глава платная (покупка на remanga) или требует входа на сайт; главы с ExManga читаются через ExManga")
            }
            return emptyList()
        }
        val detail = response.use { it.parseAs<ChapterDetailDto>() }
        val serverBase = (detail.server?.link ?: "$baseUrl/").trimEnd('/')
        val pages = detail.pages
            .flatten()
            .sortedBy { it.id }
            .mapIndexed { index, page ->
                val link = page.link.takeIf { it.startsWith("http") }
                    ?: (serverBase + "/" + page.link.trimStart('/'))
                Page(index, imageUrl = link)
            }
        if (pages.isEmpty()) {
            throw Exception("Содержимое главы пока недоступно на сервере remanga")
        }
        return pages
    }

    private suspend fun fetchExPages(chapter: SChapter): List<Page> {
        val exId = "$exMangaDomain${chapter.url}".toHttpUrlOrNull()?.queryParameter("id")
            ?: return emptyList()
        val url = "$exMangaDomain/chapter".toHttpUrl().newBuilder().addQueryParameter("id", exId).build()
        val response = requestExChapter(url, chapter.name)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            when (code) {
                404 -> throw Exception("Главы пока нет в пуле ExManga — зайдите позже")
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
            .also { pages ->
                // ExManga returns named placeholder images when the chapter's content isn't in the
                // community pool yet — remember the chapter so the list can mark it, and fail with
                // a readable message instead of a 404 storm. The mark clears once real pages arrive.
                val isPlaceholder = pages.isNotEmpty() &&
                    pages.all { it.imageUrl?.contains("/tachiyomi", ignoreCase = true) == true }
                val stored = placeholderIds()
                if (isPlaceholder) {
                    if (stored.add(exId)) {
                        preferences.edit().putStringSet(EX_PLACEHOLDER_PREF, stored).apply()
                    }
                    throw Exception("Содержимое главы пока не загружено в пул ExManga — зайдите позже")
                }
                if (stored.remove(exId)) {
                    preferences.edit().putStringSet(EX_PLACEHOLDER_PREF, stored).apply()
                }
            }
    }

    private suspend fun requestExChapter(url: HttpUrl, chapter: String): Response {
        val manga = Diagnostics.ctxManga.orEmpty()
        fun request(step: ExChapterAuthRequest, trace: RequestTrace? = null) = Request.Builder().url(url).headers(exHeaders())
            .cacheControl(DEFAULT_CACHE_CONTROL)
            .tag(ExChapterAuthRequest::class.java, step)
            .tag(RequestTrace::class.java, trace)
            .build()

        val first = client.newCall(request(ExChapterAuthRequest.INITIAL)).await()
        if (first.code != 401) return first
        val rejectedAuth = first.request.header("Authorization")
        val firstStatus = first.use { it.authStatus() }
        val tokenState = if (rejectedAuth == null) "без токена" else "с токеном"
        fun report(reason: String) {
            Diagnostics.error("GET /chapter $tokenState → $firstStatus. $reason", "exmanga", manga, chapter)
        }
        val refresh = rejectedAuth != null && rejectedAuth == exHeaders()["Authorization"]
        val loginStep = when (val login = ensureExToken(refresh, ExChapterAuthRequest.RECOVERY)) {
            ExLogin.Cached -> "Использован сохранённый токен."
            ExLogin.Authenticated -> "Вход выполнен, получен токен."
            is ExLogin.Failed -> {
                report(login.reason)
                throw Exception(login.readerMessage)
            }
        }

        val trace = RequestTrace()
        val retry = request(ExChapterAuthRequest.RECOVERY, trace)
        val retryStep = "$loginStep Повтор GET /chapter"
        val response = try {
            client.newCall(retry).await()
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            if (!trace.isCanceled(e)) report("$retryStep: ${e.javaClass.simpleName}. ${trace.describe(e)}")
            throw e
        }
        if (!response.isSuccessful) {
            val retryToken = if (response.request.header("Authorization") == null) "без токена" else "с токеном"
            report("$retryStep $retryToken → ${response.authStatus()}.")
        }
        if (response.code == 401) {
            response.close()
            throw Exception("$loginStep ExManga отказала в доступе к главе (HTTP 401).")
        }
        return response
    }

    private fun Response.authStatus(reason: String? = authFailureCode()): String = if (reason == null) "HTTP $code" else "HTTP $code ($reason)"

    private fun Response.authFailureCode(): String? {
        if (isSuccessful) return null
        val bearer = challenges().firstOrNull { it.scheme.equals("Bearer", ignoreCase = true) }?.authParams?.get("error")
        return authErrorCode(bearer?.let(::JsonPrimitive)) ?: if (body.contentType()?.subtype?.endsWith("json") == true) {
            runCatching { peekBody(4096).use { authErrorCode(it.string().parseAs<JsonObject>()) } }.getOrNull()
        } else {
            null
        }
    }

    // A server message can echo the reader's credentials. Only emit known categories.
    private fun authErrorCode(data: JsonElement?): String? {
        val values = if (data is JsonObject) listOf(data["error"], data["code"], data["data"], data["message"]) else listOf(data)
        return values.firstNotNullOfOrNull { value ->
            when ((value as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()) {
                "invalid_token", "invalid token" -> "invalid_token"
                "token_expired", "token expired" -> "token_expired"
                "insufficient_scope" -> "insufficient_scope"
                "invalid_request" -> "invalid_request"
                "invalid_credentials", "invalid credentials", "неверный логин или пароль" -> "invalid_credentials"
                else -> null
            }
        }
    }

    private fun placeholderIds(): MutableSet<String> = preferences.getStringSet(EX_PLACEHOLDER_PREF, emptySet())?.toMutableSet() ?: mutableSetOf()

    private suspend fun fetchCatalogList(page: Int, params: HttpUrl.Builder.() -> Unit): MangasPage {
        val dto = client.get(
            "$API_URL/v2/search/catalog/".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("count", PAGE_SIZE.toString())
                .apply(params)
                .build(),
        ).parseAs<CatalogDto>()
        val mangas = dto.results.mapNotNull { it.toSManga(baseUrl) }
        return MangasPage(mangas, dto.hasNextPage())
    }

    private suspend fun fetchSearchList(page: Int, query: String): MangasPage {
        val dto = client.get(
            "$API_URL/v2/search/".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("count", PAGE_SIZE.toString())
                .build(),
        ).parseAs<SearchDto>()
        val mangas = dto.results.mapNotNull { it.toSManga(baseUrl) }
        return MangasPage(mangas, dto.hasNextPage())
    }

    private suspend fun fetchChapters(title: TitleContentDto, dir: String): List<SChapter> {
        val exDomain = if (useExManga && title.id != 0L) exMangaDomain else null
        val exChapters = if (exDomain != null) {
            // The history endpoint requires an ExManga account; log in with the stored
            // credentials before requesting it. Without them the chapter list still loads,
            // paid chapters just stay locked.
            ensureExToken()
            runCatching {
                var response = client.get("$exDomain/manga/history?id=${title.id}", exHeaders(), ensureSuccess = false)
                // A cached-but-expired token also yields 401: drop it, log in again, retry once.
                if (response.code == 401 && !preferences.getString(EX_TOKEN_PREF, null).isNullOrBlank() && ensureExToken(retryOnAuthFailure = true) !is ExLogin.Failed) {
                    response.close()
                    response = client.get("$exDomain/manga/history?id=${title.id}", exHeaders(), ensureSuccess = false)
                }
                response.use { response ->
                    when {
                        response.code == 401 -> emptyList()
                        response.isSuccessful ->
                            response.parseAs<ExWrapperDto<List<ExChapterDto>>>().data
                        else -> emptyList()
                    }
                }
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        val chapters = mutableListOf<SChapter>()
        for (branch in title.branches) {
            var page = 1
            while (true) {
                val dto = client.get(chaptersApiUrl(branch.id, page)).parseAs<ChaptersDto>()
                val items = dto.results
                if (items.isEmpty()) break
                chapters += items.mapNotNull { item ->
                    if (item.isLocked && !showLocked) return@mapNotNull null
                    item.toSChapter(
                        dir,
                        title.id,
                        baseUrl,
                        showFreeDate,
                        branch.publisherName(),
                        exDomain,
                        exChapters,
                        placeholderIds(),
                    )
                }
                if (!dto.hasNextPage()) break
                page++
            }
        }
        return chapters
    }

    /** Extracts the trailing chapter id from "/manga/{titleId}ch{chapterId}". */
    private fun chapterChapterId(url: String): String? {
        val m = Regex("ch(\\d+)$").find(url) ?: return null
        return m.groupValues[1]
    }

    private fun titleApiUrl(dir: String): HttpUrl = "$API_URL/v2/titles/".toHttpUrl().newBuilder().addPathSegment(dir).build()

    private fun chaptersApiUrl(branchId: Long, page: Int): HttpUrl = "$API_URL/v2/titles/chapters/".toHttpUrl().newBuilder()
        .addQueryParameter("branch_id", branchId.toString())
        .addQueryParameter("ordering", "-index")
        .addQueryParameter("page", page.toString())
        .addQueryParameter("count", CHAPTERS_PER_PAGE.toString())
        .build()

    private fun chapterApiUrl(chapterId: String): String = "$API_URL/v2/titles/chapters/$chapterId/"

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
        const val AUTO_UPLOAD_PREF = "auto_upload_logs"
        const val EX_PAID_PREF = "read_paid_via_exmanga"
        const val EX_DOMAIN_PREF = "exmanga_domain"
        const val DEFAULT_EX_DOMAIN = "https://api.exmanga.org"
        const val EX_EMAIL_PREF = "exmanga_email"
        const val EX_PASSWORD_PREF = "exmanga_password"
        const val EX_TOKEN_PREF = "exmanga_token"
        const val EX_NAME_PREF = "exmanga_name"
        const val EX_PLACEHOLDER_PREF = "exmanga_placeholders"
        const val STAT_MANGA_PREF = "stat_manga_dirs"
        const val STAT_CHAPTERS_PREF = "stat_chapter_lists"
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

package eu.kanade.tachiyomi.extension.ru.remanga

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParseDateTime
import keiyoushi.utils.tryParseZonedDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// v2 API pagination: `next` is a URL string in search/catalog, a page number in chapter lists,
// and null on the last page — all three arrive as JsonPrimitive (or JsonNull).
@Serializable
class CatalogDto(
    val next: JsonPrimitive? = null,
    val results: List<CatalogItemDto> = emptyList(),
) {
    fun hasNextPage(): Boolean = hasNext(next)
}

internal fun hasNext(next: JsonPrimitive?): Boolean {
    next ?: return false
    next.longOrNull?.let { return it > 0 }
    val s = next.contentOrNull ?: return false
    return s.isNotEmpty() && s != "null"
}

@Serializable
class CatalogItemDto(
    private val dir: String,
    @SerialName("main_name") private val mainName: String? = null,
    @SerialName("secondary_name") private val secondaryName: String? = null,
    private val cover: CoverDto? = null,
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = dir
        title = mainName ?: secondaryName ?: dir
        thumbnail_url = cover?.high?.takeIf { it.isNotEmpty() }?.let { "$baseUrl$it" }
    }
}

@Serializable
class CoverDto(
    val high: String? = null,
)

// v2 title details are a flat object resolved by DIR (numeric ids are not accepted).
@Serializable
class TitleContentDto(
    val id: Long = 0L,
    @SerialName("main_name") private val mainName: String? = null,
    @SerialName("secondary_name") private val secondaryName: String? = null,
    private val description: String? = null,
    private val status: StatusDto? = null,
    private val cover: CoverDto? = null,
    private val img: CoverDto? = null,
    private val genres: List<NameDto> = emptyList(),
    private val categories: List<NameDto> = emptyList(),
    // v2 sends a flat array of {id,name,cover}; some responses may still use the v1 role map
    // {"1":[…],"2":[…]} — accept both.
    private val creators: JsonElement? = null,
    val branches: List<BranchDto> = emptyList(),
) {
    internal val hasData: Boolean
        get() = mainName != null || branches.isNotEmpty()

    fun toSManga(manga: SManga, baseUrl: String): SManga = manga.apply {
        title = mainName ?: secondaryName ?: this.title
        thumbnail_url = (cover ?: img)?.high?.takeIf { it.isNotEmpty() }?.let { "$baseUrl$it" } ?: thumbnail_url
        description = this@TitleContentDto.description?.let { Jsoup.parseBodyFragment(it, baseUrl).text() }
        genre = (genres.mapNotNull { it.name } + categories.mapNotNull { it.name })
            .distinct()
            .joinToString()
        val (authorNames, artistNames) = extractCreators(creators)
        author = authorNames
        artist = artistNames.ifEmpty { authorNames }
        status = parseStatus(this@TitleContentDto.status?.id)
    }
}

private fun extractCreators(el: JsonElement?): Pair<String, String> {
    when (el) {
        is JsonArray -> {
            val names = el.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                .filter { it.isNotBlank() }
                .distinct()
            return names.joinToString() to ""
        }
        is JsonObject -> {
            fun role(key: String): List<String> = el[key]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                .orEmpty()
                .filter { it.isNotBlank() }
                .distinct()
            return role("1").joinToString() to role("2").joinToString()
        }
        else -> return "" to ""
    }
}

@Serializable
class StatusDto(
    val id: Int,
)

@Serializable
class NameDto(
    val name: String? = null,
)

@Serializable
class PublisherDto(
    val name: String? = null,
)

@Serializable
class BranchDto(
    val id: Long,
    private val publishers: List<PublisherDto> = emptyList(),
) {
    fun publisherName(): String? = publishers.firstOrNull()?.name
}

@Serializable
class ChaptersDto(
    val next: JsonPrimitive? = null,
    val results: List<ChapterItemDto> = emptyList(),
) {
    fun hasNextPage(): Boolean = hasNext(next)
}

@Serializable
class ChapterItemDto(
    private val id: Long,
    private val tome: Int? = null,
    private val chapter: String? = null,
    private val name: String? = null,
    private val publishers: List<PublisherDto> = emptyList(),
    @SerialName("is_paid") private val isPaid: Boolean = false,
    @SerialName("is_bought") private val isBought: Boolean = false,
    @SerialName("upload_date") private val uploadDate: String? = null,
    @SerialName("pub_date") private val pubDate: String? = null,
) {
    val isLocked: Boolean
        get() = isPaid && !isBought

    internal val rawUploadDate: String?
        get() = uploadDate

    fun toSChapter(
        dir: String,
        baseUrl: String,
        showFreeDate: Boolean,
        branchPublisher: String?,
        exDomain: String?,
        exChapters: List<ExChapterDto>,
    ): SChapter = SChapter.create().apply {
        // A paid chapter with the same chapter on ExManga is readable there; a paid chapter
        // without one stays locked (remanga only, requires purchase).
        val exMatch = exDomain?.let {
            exChapters.firstOrNull { ex -> ex.id == id || (ex.tome == tome && ex.chapter == chapter) }
        }
        val readableViaEx = exMatch != null

        val chapterTitle = buildString {
            if (tome != null) {
                append("Том ").append(tome).append(". ")
            }
            append("Глава ").append(chapter ?: id.toString())
            this@ChapterItemDto.name?.takeIf { it.isNotEmpty() }?.let { append(" ").append(it) }
            if (showFreeDate && isLocked) {
                formatFreeDate(pubDate)?.let { append(" (free ").append(it).append(")") }
            }
        }
        name = if (isLocked && !readableViaEx) "$NO_EX_ICON$chapterTitle" else chapterTitle
        chapter_number = chapter?.toFloatOrNull() ?: 0f
        scanlator = if (readableViaEx) {
            "exmanga"
        } else {
            publishers.firstOrNull()?.name ?: branchPublisher
        }
        url = if (readableViaEx) {
            "/chapter?id=${exMatch!!.id}"
        } else {
            "$baseUrl/titles/".toHttpUrl()
                .newBuilder()
                .addPathSegment(dir)
                .addQueryParameter("chapter", id.toString())
                .build()
                .let { it.encodedPath + it.encodedQuery?.let { q -> "?$q" }.orEmpty() }
        }
        date_upload = parseChapterDate(uploadDate)
    }
}

// ExManga (api.exmanga.org) mirrors the paid chapters of remanga titles; it keys history by the
// remanga title's numeric id and reuses remanga chapter ids (matching falls back to volume+number).
// Some chapters require an ExManga account: POST /auth/login {email,password} -> token.
@Serializable
class ExWrapperDto<T>(
    val data: T,
)

@Serializable
class ExLoginDto(
    val email: String,
    val password: String,
)

@Serializable
class ExRegisterDto(
    val name: String,
    val email: String,
    val password: String,
)

@Serializable
class ExAuthResponseDto(
    val success: Boolean = false,
    val data: JsonElement? = null,
)

@Serializable
class ExChapterDto(
    val id: Long,
    val tome: Int = 0,
    val chapter: String = "",
)

@Serializable
class ExPageDto(
    val height: Int = 0,
    val link: String,
)

private val offsetDateFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME

// ISO_LOCAL_DATE_TIME accepts the API's fractional seconds (e.g. 2026-10-19T09:01:56.211560).
private val localDateFormat = DateTimeFormatter.ISO_LOCAL_DATE_TIME

internal fun parseChapterDate(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    return offsetDateFormat.tryParseZonedDateTime(raw)
        .takeIf { it != 0L }
        ?: localDateFormat.tryParseDateTime(raw)
}

@Serializable
class ChapterDetailDto(
    val server: ServerDto? = null,
    val pages: List<List<PageDto>> = emptyList(),
)

@Serializable
class ServerDto(
    val link: String? = null,
    @SerialName("fallback_link") val fallbackLink: String? = null,
)

@Serializable
class PageDto(
    val id: Int,
    val link: String,
)

private fun parseStatus(id: Int?): Int = when (id) {
    1 -> SManga.COMPLETED
    2 -> SManga.ONGOING
    3 -> SManga.ON_HIATUS
    4, 5 -> SManga.CANCELLED
    6 -> SManga.LICENSED
    else -> SManga.UNKNOWN
}

private fun formatFreeDate(raw: String?): String? {
    val millis = parseChapterDate(raw)
    if (millis == 0L) return null
    return java.time.Instant.ofEpochMilli(millis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .format(displayDateFormat)
}

private val displayDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

// ⏳ marks paid chapters not yet uploaded to the ExManga pool — they may appear there later.
private const val NO_EX_ICON = "\u23F3 "

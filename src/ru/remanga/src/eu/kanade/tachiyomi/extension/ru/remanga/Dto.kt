package eu.kanade.tachiyomi.extension.ru.remanga

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParseDateTime
import keiyoushi.utils.tryParseZonedDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
class CatalogDto(
    val content: List<CatalogItemDto> = emptyList(),
    val props: CatalogPropsDto = CatalogPropsDto(),
)

@Serializable
class CatalogPropsDto(
    private val page: Int = 0,
    @SerialName("total_pages") private val totalPages: Int = 0,
) {
    fun hasNextPage(): Boolean = page < totalPages
}

@Serializable
class CatalogItemDto(
    private val dir: String,
    @SerialName("rus_name") private val rusName: String? = null,
    @SerialName("en_name") private val enName: String? = null,
    @SerialName("main_name") private val mainName: String? = null,
    private val img: CoverDto? = null,
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = dir
        title = rusName ?: enName ?: mainName ?: dir
        thumbnail_url = img?.high?.takeIf { it.isNotEmpty() }?.let { "$baseUrl$it" }
    }
}

@Serializable
class CoverDto(
    val high: String? = null,
)

@Serializable
class TitleDetailDto(
    val content: TitleContentDto,
)

@Serializable
class TitleContentDto(
    val id: Long = 0L,
    @SerialName("rus_name") private val rusName: String? = null,
    @SerialName("en_name") private val enName: String? = null,
    @SerialName("main_name") private val mainName: String? = null,
    private val description: String? = null,
    private val status: StatusDto? = null,
    private val img: CoverDto? = null,
    private val genres: List<NameDto> = emptyList(),
    private val categories: List<NameDto> = emptyList(),
    private val creators: Map<String, List<CreatorDto>> = emptyMap(),
    val branches: List<BranchDto> = emptyList(),
) {
    fun toSManga(manga: SManga, baseUrl: String): SManga = manga.apply {
        title = rusName ?: enName ?: mainName ?: this.title
        thumbnail_url = img?.high?.takeIf { it.isNotEmpty() }?.let { "$baseUrl$it" } ?: thumbnail_url
        description = this@TitleContentDto.description?.let { Jsoup.parseBodyFragment(it, baseUrl).text() }
        genre = (genres.mapNotNull { it.name } + categories.mapNotNull { it.name })
            .distinct()
            .joinToString()
        author = creators["1"].orEmpty().mapNotNull { it.name }.distinct().joinToString()
        artist = creators["2"].orEmpty().mapNotNull { it.name }.distinct().joinToString()
        status = parseStatus(this@TitleContentDto.status?.id)
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
class CreatorDto(
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
    val content: List<ChapterItemDto> = emptyList(),
)

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
        name = if (isLocked && !readableViaEx) "$LOCK_ICON$chapterTitle" else chapterTitle
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
    val content: ChapterDetailContentDto,
)

@Serializable
class ChapterDetailContentDto(
    val pages: List<List<PageDto>> = emptyList(),
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

private const val LOCK_ICON = "\uD83D\uDD12 "

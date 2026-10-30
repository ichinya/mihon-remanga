package eu.kanade.tachiyomi.extension.ru.remanga

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal open class OptionFilter(name: String, private val options: List<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected: String get() = options[state].second
}

internal class SortFilter :
    OptionFilter(
        "Сортировка",
        listOf(
            "По популярности" to "-views",
            "По рейтингу" to "-rating",
            "По дате обновления" to "-chapter_date",
            "По новизне" to "-id",
        ),
    )

internal class TypeFilter :
    OptionFilter(
        "Тип произведения",
        listOf(
            "Любой" to "",
            "Манга" to "Манга",
            "Манхва" to "Манхва",
            "Маньхуа" to "Маньхуа",
            "Рукомикс" to "Рукомикс",
            "Комикс" to "Комикс",
        ),
    )

internal class AgeRatingFilter :
    OptionFilter(
        "Возрастной рейтинг",
        listOf(
            "Любой" to "",
            "Для всех" to "0",
            "От 16 лет" to "1",
            "От 18 лет" to "2",
        ),
    )

internal fun filterList(): FilterList = FilterList(
    SortFilter(),
    TypeFilter(),
    AgeRatingFilter(),
)

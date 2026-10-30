import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Remanga"
    versionCode = 117
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "Remanga"
        lang = "ru"
        baseUrl = "https://remanga.org"
        // Keeps the ID used by the previously published Tachiyomi/Mihon Remanga extensions,
        // so existing libraries migrate as a regular update.
        id = 8983242087533137528L
    }

    deeplink {
        path("/titles/..*")
        path("/manga/..*")
    }
}

android {
    defaultConfig {
        // "1.6." + 117 folds to "1.6.117" at compile time; follows the CI-bumped versionCode.
        buildConfigField("String", "EXT_VERSION", "\"1.6.\" + ${keiyoushi.versionCode.get()}")
    }
}

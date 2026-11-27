import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Remanga"
    versionCode = 178
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
        // Use the final version code, including the CI run number in release builds.
        buildConfigField("String", "EXT_VERSION", "\"1.6.\" + ${keiyoushi.versionCode.get()}")
    }
}

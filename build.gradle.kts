plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Embeds CPython in the app process — the only practical way to ship a Python
    // runtime on Android 10+, where executing bundled binaries from app data is blocked.
    id("com.chaquo.python") version "17.0.0" apply false
}

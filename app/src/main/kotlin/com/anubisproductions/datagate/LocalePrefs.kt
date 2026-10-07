package com.anubisproductions.datagate

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The in-app language choice.
 *
 * The app follows the phone's language by default, which is the behaviour a user expects and
 * what the twelve `values-xx` folders exist for. This adds an override, for two reasons that
 * came out of the closed test: a tester cannot review the Arabic or Urdu without changing
 * their entire phone, and the one Arabic-speaking tester found a real word-order defect
 * (`FINDINGS.md` F1) that nobody else could see.
 *
 * Two mechanisms, because Android only grew a real one in API 33:
 *
 * - **API 33+** uses the framework's per-app locale. Android persists it, shows it under the
 *   app's own entry in system settings, and applies it to every context including the
 *   service. Nothing here needs to wrap anything.
 * - **Below 33** there is no such thing, so the choice is stored in prefs and applied by
 *   hand in `attachBaseContext` on every component that shows text - both activities and the
 *   service, since the ongoing notification is text too.
 *
 * Keeping both behind one object means callers never branch on the version.
 */
object LocalePrefs {

    private const val FILE = "datagate_locale"
    private const val KEY = "tag"

    /** BCP-47 tags matching the `values-xx` folders. Empty string means "follow the phone". */
    val SUPPORTED = listOf("ar", "de", "en", "es", "fr", "hi", "id", "pt-BR", "pt-PT", "ru", "tr", "ur")

    /** What each language calls itself. Never translated - a language list in a language you
     *  cannot read is useless, which is the situation this feature exists to fix. */
    val ENDONYMS = mapOf(
        "ar" to "العربية",
        "de" to "Deutsch",
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "hi" to "हिन्दी",
        "id" to "Bahasa Indonesia",
        "pt-BR" to "Português (Brasil)",
        "pt-PT" to "Português (Portugal)",
        "ru" to "Русский",
        "tr" to "Türkçe",
        "ur" to "اردو",
    )

    /** The tag currently in force, or "" for the phone's own language. */
    fun current(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeIf { !it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()
                ?.let { match(it) }
                ?: ""
        } else {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        }

    /**
     * Apply a choice. Pass "" to hand control back to the phone.
     *
     * On 33+ the framework recreates the activity itself. Below that the caller has to,
     * because nothing else will.
     */
    fun set(ctx: Context, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tag)
        } else {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY, tag).apply()
        }
    }

    /** True when the caller must call `recreate()` itself after [set]. */
    fun needsManualRestart(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /**
     * Wrap a base context in the chosen locale. A no-op on 33+, where the framework has
     * already done it, and on the default setting.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "")
        if (tag.isNullOrEmpty()) return base

        val locale = Locale.forLanguageTag(tag)
        // Also set the process default, so anything formatting a date or a number outside a
        // resource lookup - BidiFormatter included - agrees with the rest of the UI.
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }

    /**
     * Map a system tag onto one of ours. Android may hand back "pt-BR" or plain "pt", and a
     * region we do not ship should still light up the right row rather than none.
     */
    private fun match(tag: String): String? {
        SUPPORTED.firstOrNull { it.equals(tag, ignoreCase = true) }?.let { return it }
        val lang = tag.substringBefore('-')
        return SUPPORTED.firstOrNull { it.substringBefore('-').equals(lang, ignoreCase = true) }
    }
}

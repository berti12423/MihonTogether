package mihon.feature.translate

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Translation settings. Keys are private preferences, so they never go into Mihon backups. */
class TranslationPreferences(private val store: PreferenceStore) {
    val engine = store.getString("translate_engine", ENGINE_ON_DEVICE)
    val geminiKey = store.getString(Preference.privateKey("translate_gemini_key"), "")
    val deeplKey = store.getString(Preference.privateKey("translate_deepl_key"), "")
    val papagoId = store.getString(Preference.privateKey("translate_papago_id"), "")
    val papagoSecret = store.getString(Preference.privateKey("translate_papago_secret"), "")
    val model = store.getString("translate_model", MODELS.first())

    /** My PC (Koharu): the paired PC's address ("IP:port"), its name, and the token it gave this phone. */
    val pcAddress = store.getString("translate_pc_address", "")
    val pcName = store.getString("translate_pc_name", "")
    val pcToken = store.getString(Preference.privateKey("translate_pc_token"), "")

    /** "" means detect. */
    val sourceLanguage = store.getString("translate_source", "")
    val targetLanguage = store.getString("translate_target", "en")
    val translateSfx = store.getBoolean("translate_sfx", false)

    /** Off: the on-device language pack downloads only on Wi-Fi (unless downloaded from the first-run screen). */
    val allowMobileData = store.getBoolean("translate_pack_mobile_data", false)

    /** A title's glossary ("find → replace" pairs applied when pages are drawn). */
    private fun glossaryPreference(mangaId: Long) = store.getString("translate_glossary_$mangaId", "")

    fun glossary(mangaId: Long): Glossary = Glossary.decode(glossaryPreference(mangaId).get())

    fun setGlossary(mangaId: Long, glossary: Glossary) = glossaryPreference(mangaId).set(glossary.cleaned().encode())

    fun settings() = TranslationSettings(
        apiKey = geminiKey.get().trim(),
        model = model.get().takeIf { it in MODELS } ?: MODELS.first(),
        sourceLanguage = sourceLanguage.get().ifBlank { null },
        targetLanguage = targetLanguage.get().ifBlank { "en" },
        translateSfx = translateSfx.get(),
        engine = engine.get().takeIf { it in ENGINES } ?: ENGINE_ON_DEVICE,
        deeplKey = deeplKey.get().trim(),
        papagoId = papagoId.get().trim(),
        papagoSecret = papagoSecret.get().trim(),
        allowMobileData = allowMobileData.get(),
        pcAddress = pcAddress.get().trim(),
        pcName = pcName.get().trim(),
        pcToken = pcToken.get().trim(),
    )

    /** Stores a pairing at once (the PC already knows the token); [save] leaves these fields alone. */
    fun savePc(address: PcAddress, name: String, token: String) {
        pcAddress.set(address.toString())
        pcName.set(name.trim())
        pcToken.set(token.trim())
    }

    fun clearPc() {
        pcToken.set("")
        pcName.set("")
        pcAddress.set("")
    }

    /** Saves the dialog's choices. The PC pairing is stored separately by [savePc] and [clearPc]. */
    fun save(settings: TranslationSettings) {
        engine.set(settings.engine)
        geminiKey.set(settings.apiKey.trim())
        deeplKey.set(settings.deeplKey.trim())
        papagoId.set(settings.papagoId.trim())
        papagoSecret.set(settings.papagoSecret.trim())
        model.set(settings.model)
        sourceLanguage.set(settings.sourceLanguage.orEmpty())
        targetLanguage.set(settings.targetLanguage)
        translateSfx.set(settings.translateSfx)
        allowMobileData.set(settings.allowMobileData)
    }

    companion object {
        /** Default first. 3.8 Flash's free tier allows about 20 requests a day (docs/translation-spike.md). */
        val MODELS = listOf("gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-3.8-flash")
        val SOURCE_LANGUAGES = listOf("", "ko", "ja", "zh")

        /** Free and unlimited: ML Kit OCR + ML Kit translation on the phone. */
        const val ENGINE_ON_DEVICE = "on_device"

        /** ML Kit OCR + DeepL (own key; the free plan allows 500,000 characters a month). */
        const val ENGINE_DEEPL = "deepl"

        /** ML Kit OCR + Papago (own Naver Cloud key; paid per character). */
        const val ENGINE_PAPAGO = "papago"

        /** Gemini vision (own key): best quality, small daily free quota. */
        const val ENGINE_GEMINI = "gemini"

        /** The Mihon Together Translator (Koharu) on the user's own PC, over the home Wi-Fi. */
        const val ENGINE_PC = PcTranslationEngine.ID

        val ENGINES = listOf(ENGINE_ON_DEVICE, ENGINE_DEEPL, ENGINE_PAPAGO, ENGINE_GEMINI, ENGINE_PC)

        /** Engines that keep pages on the user's own devices (phone or paired PC); these may run in incognito. */
        fun isLocal(engine: String): Boolean = engine == ENGINE_ON_DEVICE || engine == ENGINE_PC
    }
}

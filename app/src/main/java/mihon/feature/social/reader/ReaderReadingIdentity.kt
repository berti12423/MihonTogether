package mihon.feature.social.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.feature.social.SocialContent
import mihon.feature.social.SocialIdentity
import mihon.feature.social.SocialPosition
import mihon.feature.social.SocialSource
import tachiyomi.domain.manga.model.Manga

object ReaderReadingIdentity {
    fun chapterKey(content: SocialContent, chapter: ReaderChapter): String? =
        chapterKey(content, chapter.chapter.url, chapter.chapter.scanlator)

    fun chapterKey(content: SocialContent, url: String, scanlator: String?): String? {
        val path = SocialIdentity.publicPath(url) ?: return null
        return SocialIdentity.key("chapter-v1|${content.sourceKey}|${content.editionKey}|$path|${scanlator.orEmpty()}")
    }

    fun reading(manga: Manga, chapter: ReaderChapter, index: Int): LocalReading? {
        val content = SocialIdentity.content(manga) ?: return null
        val key = chapterKey(content, chapter) ?: return null
        val pages = chapter.pages ?: return null
        if (pages.size !in 1..2000 || index !in pages.indices) return null
        return LocalReading(
            content,
            SocialPosition(
                chapterKey = key,
                chapterLabel = chapter.chapter.name.take(100),
                pageIndex = index,
                pageCount = pages.size,
                pageMappingKey = ReadingCompatibility.manualMapping(content, key, pages.size),
            ),
            chapter.chapter.id ?: return null,
            sourceId = manga.source,
            mangaUrl = SocialSource.validUrl(manga.url),
        )
    }

    fun reading(manga: Manga, page: ReaderPage): LocalReading? = reading(manga, page.chapter, page.index)
}

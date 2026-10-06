package eu.kanade.presentation.manga

enum class DownloadAction {
    NEXT_1_CHAPTER,
    NEXT_5_CHAPTERS,
    NEXT_10_CHAPTERS,
    NEXT_25_CHAPTERS,
    UNREAD_CHAPTERS,
    BOOKMARKED_CHAPTERS,
}

enum class EditCoverAction {
    EDIT,
    DELETE,
}

enum class MangaScreenItem {
    INFO_BOX,
    ACTION_ROW,
    DESCRIPTION_WITH_TAG,

    /** Mihon Together: friends who read this title and my rating. */
    FRIENDS,
    CHAPTER_HEADER,
    CHAPTER,
}

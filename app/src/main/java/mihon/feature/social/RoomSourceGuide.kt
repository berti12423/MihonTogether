package mihon.feature.social

/** What a friend joining a room needs to read the host's title (roadmap F9). */
sealed interface RoomSourceGuide {
    /** The host's source is installed here: search the title in it. */
    data class Installed(val sourceId: Long, val name: String) : RoomSourceGuide

    /** One of my extension repos has it: install, then search the title. */
    data class Installable(val pkgName: String, val name: String, val lang: String) : RoomSourceGuide

    /** Installed but not trusted yet (Mihon asks before loading new extensions). */
    data class Untrusted(val pkgName: String, val name: String) : RoomSourceGuide

    /** Not in any of my repos: name it, and say a repo that has it is needed. The app never adds repos itself. */
    data class Missing(val name: String, val lang: String) : RoomSourceGuide

    /** The room doesn't say (an older app hosted it): fall back to a search across sources. */
    data object Unknown : RoomSourceGuide

    companion object {
        /** A repo listing: the extension package and the source IDs it provides. */
        data class Listing(val pkgName: String, val sourceIds: Set<Long>)

        /**
         * Where to look for a friend's title that isn't in my library: their source when I have it installed, else
         * [Unknown] (search all my sources by title). A room names its source; a shared library entry or review only
         * carries the hashed `sourceKey`, so [installed] source IDs (ID → name) are hashed the same way to find it.
         */
        fun locate(content: SocialContent, installed: Map<Long, String>): RoomSourceGuide {
            content.source?.let { source -> if (source.id in installed) return Installed(source.id, source.name) }
            val match = installed.entries.firstOrNull { SocialIdentity.sourceKey(it.key) == content.sourceKey }
            return match?.let { Installed(it.key, it.value) } ?: Unknown
        }

        fun decide(
            source: SocialSource?,
            installedSourceIds: Set<Long>,
            untrustedPackages: Set<String>,
            available: List<Listing>,
        ): RoomSourceGuide {
            source ?: return Unknown
            if (source.id in installedSourceIds) return Installed(source.id, source.name)
            if (source.pkg != null && source.pkg in untrustedPackages) return Untrusted(source.pkg, source.name)
            // Match by source ID first (packages can be renamed), then by package.
            val listing = available.firstOrNull { source.id in it.sourceIds }
                ?: available.firstOrNull { it.pkgName == source.pkg }
            return if (listing !=
                null
            ) {
                Installable(listing.pkgName, source.name, source.lang)
            } else {
                Missing(source.name, source.lang)
            }
        }
    }
}

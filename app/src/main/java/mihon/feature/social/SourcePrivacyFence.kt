package mihon.feature.social

/** Acknowledges only completed server revocations, and forgets sources made public again. */
internal class SourcePrivacyFence(previouslyHandled: Set<String>) {
    var handled: Set<String> = previouslyHandled
        private set

    fun reconcile(currentlyPrivate: Set<String>): Boolean {
        handled = handled.intersect(currentlyPrivate)
        return (currentlyPrivate - handled).isNotEmpty()
    }

    fun acknowledge(currentlyPrivate: Set<String>) {
        handled = currentlyPrivate
    }
}

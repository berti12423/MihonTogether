package mihon.feature.social

/** Retain local source observers while a previously published server snapshot may still exist. */
internal class ProjectionSourceHistory(previouslyObserved: Set<String>) {
    var retained: Set<String> = previouslyObserved.filterTo(mutableSetOf()) { value ->
        value.toLongOrNull()?.let { it > 0 && it.toString() == value } == true
    }
        private set

    fun include(current: Collection<Long>): Set<Long> {
        retained = retained + current.filter { it > 0 }.map { it.toString() }
        return retained.map { it.toLong() }.toSet()
    }
}

package app.torrtv.state

/** Update metadata in place; newly arriving results append without moving a visible selection. */
internal class StableResults<T,K>(private val key:(T)->K) {
    private val order=LinkedHashSet<K>()
    fun update(values:List<T>):List<T> {
        val byKey=values.associateBy(key)
        order.retainAll(byKey.keys);order.addAll(byKey.keys)
        return order.mapNotNull{byKey[it]}
    }
    fun clear()=order.clear()
}

package app.torrtv.state

import org.junit.Assert.*
import org.junit.Test

class StableResultsTest {
    @Test fun newHigherRankedResultsAppendAndExistingMetadataUpdatesInPlace() {
        val order=StableResults<Pair<String,Int>,String>{it.first}
        assertEquals(listOf("a" to 2,"b" to 1),order.update(listOf("a" to 2,"b" to 1)))
        assertEquals(listOf("a" to 2,"b" to 10,"c" to 100),order.update(listOf("c" to 100,"b" to 10,"a" to 2)))
        assertEquals(listOf("b" to 12,"c" to 100),order.update(listOf("c" to 100,"b" to 12)))
        order.clear();assertEquals(listOf("c" to 100,"b" to 12),order.update(listOf("c" to 100,"b" to 12)))
    }
}

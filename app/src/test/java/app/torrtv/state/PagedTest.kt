package app.torrtv.state

import app.torrtv.data.Page
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class PagedTest {
    @Test fun aFailedPageKeepsVisibleItemsAndRetryRequestsTheSamePage()=runBlocking {
        val calls=ArrayList<Int>();var fail=true
        val pages=Paged<Int>(20,{n->calls.add(n)
            if(n==2 && fail){fail=false;throw SocketTimeoutException()}
            Page(if(n==1)listOf(1,2) else listOf(2,3,4),n,2)
        }){it}
        pages.loadNext();pages.loadNext()
        assertEquals(listOf(1,2),pages.items.toList())
        assertTrue(pages.error!!.contains("не отговори навреме"))
        pages.retry()
        assertEquals(listOf(1,2,3,4),pages.items.toList())
        assertEquals(listOf(1,2,2),calls)
        assertTrue(pages.exhausted)
    }
    @Test fun aLargePageCannotExceedTheMemoryLimit()=runBlocking {
        val pages=Paged<Int>(5,{n->Page((1..30).toList(),n,100)}){it}
        pages.loadNext();pages.loadNext()
        assertEquals((1..5).toList(),pages.items.toList())
        assertTrue(pages.exhausted)
    }
    @Test fun anEmptyFilteredBatchStillAllowsTheNextPage()=runBlocking {
        val pages=Paged<Int>(20,{n->Page(if(n==1)emptyList() else listOf(7),n,2)}){it}
        pages.loadNext();assertFalse(pages.exhausted)
        pages.loadNext();assertEquals(listOf(7),pages.items.toList())
    }
}

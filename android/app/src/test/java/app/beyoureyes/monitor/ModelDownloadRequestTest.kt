package app.beyoureyes.monitor

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadRequestTest {
    private fun request() = ModelDownloadRequest("Reader", "Read digits", 12_345, false)

    @Test
    fun `download waits for confirmation and repeated taps only continue once`() = runBlocking {
        val request = request()
        var downloads = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            request.awaitConfirmation()
            downloads++
        }
        assertEquals(0, downloads)
        request.confirm()
        request.confirm()
        job.join()
        assertEquals(1, downloads)
    }

    @Test
    fun `leaving while waiting cannot download even if the old button is invoked`() = runBlocking {
        val request = request()
        var downloads = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            request.awaitConfirmation()
            downloads++
        }
        job.cancelAndJoin()
        request.confirm()
        assertEquals(0, downloads)
    }

    @Test
    fun `consent for one request does not authorize another package or retry`() = runBlocking {
        val first = request()
        first.confirm()
        val second = request()
        var downloads = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            second.awaitConfirmation()
            downloads++
        }
        assertEquals(0, downloads)
        job.cancelAndJoin()
    }
}

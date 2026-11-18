package app.torrtv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException

class UiFailureTest {
    @Test fun commonFailuresExplainAnActionWithoutShowingTransportDetails() {
        assertTrue(UiFailure.message(UnknownHostException("secret.example")).contains("Провери интернет"))
        assertTrue(UiFailure.message(SocketTimeoutException()).contains("не отговори навреме"))
        assertTrue(UiFailure.message(IOException("ENOSPC")).contains("по-малък файл"))
        assertTrue(UiFailure.message("HTTP 401").contains("API ключ"))
        assertTrue(UiFailure.message("HTTP 429").contains("Изчакай"))
        assertTrue(UiFailure.message("decoder failed").contains("друг плеър"))
    }
    @Test fun userVisibleErrorsDoNotExposeAddressesOrAccessTokens() {
        val text=UiFailure.message("Грешка https://host.example/api?token=secret password=pass Bearer secret123")
        for(secret in listOf("host.example","token=secret","password=pass","secret123"))assertFalse(text.contains(secret))
    }
}

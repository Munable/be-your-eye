package app.beyoureyes.core.data

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class GitHubModelRedirectTest {
    private val source = "https://github.com/Munable/be-your-eye/releases/download/models-v1/catalog.json"
    private val asset = "https://release-assets.githubusercontent.com/github-production-release-asset/1/file?sig=temporary"

    @Test fun `GitHub release asset redirect is narrowly scoped`() {
        assertTrue(trustedModelDownloadLocation(source, source))
        assertTrue(trustedModelDownloadLocation(source, asset))
        for (target in listOf("http://release-assets.githubusercontent.com/file", "https://githubusercontent.com/file",
            "https://release-assets.githubusercontent.com.evil.example/file", "https://evil.example/file",
            "https://user:password@release-assets.githubusercontent.com/file", "https://release-assets.githubusercontent.com:444/file")) {
            assertFalse(target, trustedModelDownloadLocation(source, target))
        }
        for (origin in listOf("https://example.org/catalog.json", "https://github.com/login",
            "https://github.com/Munable/be-your-eye/releases/download/models-v1/catalog.json?redirect=1")) {
            assertFalse(origin, trustedModelDownloadLocation(origin, asset))
        }
    }

    @Test fun `metadata fetch accepts only the expected asset hop and rejects redirects that were not followed`() {
        fun fetch(final: String, status: Int) = SignedMetadataHttpClient(FixedHttpsTransport {
            FixedHttpsResponse(status, final, 2, null, ByteArrayInputStream("{}".toByteArray()))
        }).fetchCatalog(source)
        assertTrue(fetch(asset, 200) is MetadataFetchResult.Fetched)
        assertEquals(MetadataFetchResult.Rejected(MetadataFetchFailure.REDIRECT_OR_URL_DRIFT), fetch("https://evil.example/file", 200))
        assertEquals(MetadataFetchResult.Rejected(MetadataFetchFailure.REDIRECT_OR_URL_DRIFT), fetch(source, 302))
    }
}

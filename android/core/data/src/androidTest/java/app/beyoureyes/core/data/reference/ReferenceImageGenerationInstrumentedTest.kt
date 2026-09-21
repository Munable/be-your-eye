package app.beyoureyes.core.data.reference

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.core.domain.ReferenceMaterial
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceImageGenerationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var repository: ReferenceImageRepository
    private lateinit var sources: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = ReferenceImageRepository(context)
        repository.delete(MONITOR_ID)
        sources = File(context.cacheDir, "reference-generation-test").also {
            it.deleteRecursively()
            assertTrue(it.mkdirs())
        }
    }

    @After
    fun tearDown() {
        repository.delete(MONITOR_ID)
        sources.deleteRecursively()
    }

    @Test
    fun committedGenerationCanPruneOldAndReconcileUncommittedGeneration() {
        val first = repository.stagePersistentSet(MONITOR_ID, materials("first"))
        val second = repository.stagePersistentSet(MONITOR_ID, materials("second"))

        repository.prune(MONITOR_ID, second.references)

        assertFalse(first.directory.exists())
        assertTrue(second.directory.isDirectory)
        assertEquals(3, repository.load(MONITOR_ID, second.references).size)

        val uncommitted = repository.stagePersistentSet(MONITOR_ID, materials("orphan"))
        repository.reconcile(mapOf(MONITOR_ID to second.references))

        assertFalse(uncommitted.directory.exists())
        assertTrue(second.directory.isDirectory)
        assertEquals(3, repository.load(MONITOR_ID, second.references).size)
    }

    @Test
    fun draftSessionRestoresOnlyControlledRegularFiles() {
        val session = ReferenceDraftStore.newSession(context)
        val sessionId = ReferenceDraftStore.sessionId(context, session)
        val material = draftMaterial(session, "restorable")
        ReferenceDraftStore.persistSession(context, session, "恢复草稿", listOf(material))

        val restored = requireNotNull(
            ReferenceDraftStore.restoreSession(context, sessionId),
        )

        assertEquals(session.canonicalFile, restored.first)
        assertEquals("恢复草稿", restored.second)
        assertEquals(1, restored.third.size)
        assertTrue(File(requireNotNull(Uri.parse(restored.third.single().sourceUri).path)).isFile)
        assertTrue(
            File(
                requireNotNull(
                    Uri.parse(requireNotNull(restored.third.single().thumbnailUri)).path,
                ),
            ).isFile,
        )
        ReferenceDraftStore.discardSession(context, sessionId)
        assertFalse(session.exists())
    }

    @Test
    fun committedDraftsRemoveTheirCompleteOwningSession() {
        val session = ReferenceDraftStore.newSession(context)
        val material = draftMaterial(session, "committed")
        ReferenceDraftStore.persistSession(context, session, "已提交", listOf(material))
        File(session, "unreferenced.tmp").writeText("obsolete")

        ReferenceDraftStore.consumeDraftFiles(context, listOf(material))

        assertFalse(session.exists())
    }

    @Test
    fun draftSessionRejectsTraversalMissingAndCorruptMetadata() {
        assertNull(ReferenceDraftStore.restoreSession(context, "../outside"))

        val missingSession = ReferenceDraftStore.newSession(context)
        val missingId = ReferenceDraftStore.sessionId(context, missingSession)
        assertNull(ReferenceDraftStore.restoreSession(context, missingId))
        assertFalse(missingSession.exists())

        val corruptSession = ReferenceDraftStore.newSession(context)
        val corruptId = ReferenceDraftStore.sessionId(context, corruptSession)
        ReferenceDraftStore.persistSession(
            context,
            corruptSession,
            "will-corrupt",
            listOf(draftMaterial(corruptSession, "corrupt")),
        )
        File(corruptSession, ".draft.json").writeText("{broken")
        assertNull(ReferenceDraftStore.restoreSession(context, corruptId))
        assertFalse(corruptSession.exists())
    }

    @Test
    fun providerReportsTheStoredBoundedDimensionsThatMatchItsRgbBytes() {
        val directory = File(context.filesDir, "reference-provider-test").also {
            it.deleteRecursively()
            assertTrue(it.mkdirs())
        }
        val source = File(directory, "large.png")
        val bitmap = Bitmap.createBitmap(1_024, 768, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff315d8a.toInt())
            source.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        val record = PrivateReferenceRecord(
            localAssetId = "large-reference",
            relativePath = source.relativeTo(context.filesDir).path,
            contentSha256 = sha256(source),
            storedWidth = 1_024,
            storedHeight = 768,
        )
        val provider = PrivateReferenceImageProvider(context.filesDir, listOf(record))

        val metadata = provider.metadataFor(record, "reference_1")
        val asset = requireNotNull(provider.load(metadata))

        assertTrue(asset.width <= 1_024)
        assertTrue(asset.height <= 1_024)
        assertEquals(record.storedWidth, asset.width)
        assertEquals(asset.width, metadata.width)
        assertEquals(asset.height, metadata.height)
        assertEquals(asset.width * asset.height * 3, asset.rgb888.size)
        directory.deleteRecursively()
    }

    private fun materials(prefix: String): List<ReferenceMaterial> = listOf(
        0L,
        -1L,
        0x5555555555555555L,
    ).mapIndexed { index, differenceHash ->
        val source = File(sources, "$prefix-$index.img").apply {
            writeBytes("$prefix-reference-$index".encodeToByteArray())
        }
        val thumbnail = File(sources, "$prefix-$index.thumb.jpg").apply {
            writeBytes("$prefix-thumbnail-$index".encodeToByteArray())
        }
        ReferenceMaterial(
            sourceUri = Uri.fromFile(source).toString(),
            thumbnailUri = Uri.fromFile(thumbnail).toString(),
            exactSha256 = sha256(source),
            differenceHash = differenceHash,
            meanRed = index * 50,
            meanGreen = index * 50,
            meanBlue = index * 50,
            width = 100,
            height = 100,
        )
    }

    private fun draftMaterial(session: File, label: String): ReferenceMaterial {
        val sourceBytes = "$label-reference".encodeToByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(sourceBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val token = "$sha-${UUID.randomUUID()}"
        File(session, "$token.img").writeBytes(sourceBytes)
        File(session, "$token.thumb.jpg").writeBytes("thumbnail".encodeToByteArray())
        return ReferenceMaterial(
            sourceUri = Uri.fromFile(File(session, "$token.img")).toString(),
            thumbnailUri = Uri.fromFile(File(session, "$token.thumb.jpg")).toString(),
            exactSha256 = sha,
            differenceHash = label.hashCode().toLong(),
            meanRed = 50,
            meanGreen = 60,
            meanBlue = 70,
            width = 100,
            height = 100,
        )
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MONITOR_ID = "01900000-0000-7000-8000-000000000099"
    }
}

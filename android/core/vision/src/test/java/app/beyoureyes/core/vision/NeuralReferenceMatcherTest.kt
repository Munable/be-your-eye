package app.beyoureyes.core.vision

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NeuralReferenceMatcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `persistent cache binds artifact reference and preprocess then deletion invalidates`() {
        val directory = temporaryFolder.newFolder("cache")
        val key = ReferenceEmbeddingCacheKey("a".repeat(64), "b".repeat(64), "resize_v1")
        val value = floatArrayOf(0.6f, 0.8f)
        PersistentReferenceEmbeddingCache(directory).store(key, value)

        assertArrayEquals(
            value,
            checkNotNull(PersistentReferenceEmbeddingCache(directory).load(key, 2)),
            0f,
        )
        assertNull(
            PersistentReferenceEmbeddingCache(directory).load(
                key.copy(artifactSha256 = "c".repeat(64)),
                2,
            ),
        )
        assertNull(
            PersistentReferenceEmbeddingCache(directory).load(
                key.copy(preprocessId = "resize_v2"),
                2,
            ),
        )

        PersistentReferenceEmbeddingCache(directory)
            .invalidateReferences(setOf(key.referenceSha256))
        assertNull(PersistentReferenceEmbeddingCache(directory).load(key, 2))
    }

    @Test
    fun `reference prototype uses cache across package ids with the same artifact contract`() {
        val primary = temporaryFolder.newFile("encoder.tflite").apply { writeText("encoder-one") }
        val head = headFile()
        val assets = referenceAssets()
        val provider = ReferenceImageProvider { metadata ->
            assets.singleOrNull { it.localAssetId == metadata.localAssetId }
        }
        val target = target(assets)
        val cache = PersistentReferenceEmbeddingCache(temporaryFolder.newFolder("prototype-cache"))
        var encodes = 0
        val runnerFactory = ReferenceEmbeddingRunnerFactory {
            object : ReferenceEmbeddingRunner {
                override fun embed(asset: ReferenceImageAsset): FloatArray {
                    encodes++
                    return floatArrayOf(1f, 0f, 0f)
                }

                override fun close() = Unit
            }
        }
        val firstManifest = manifest("reference_first_v1", primary, head, featureDimension = 3)
        val firstContext = runtimeContext(firstManifest, target, primary, head)
        val first = ReferencePrototypeExternalTensorProvider(
            firstContext,
            provider,
            cache,
            runnerFactory = runnerFactory,
        )
        val required = firstManifest.inputs.filter { it.role == InputRole.REFERENCE_FEATURES }
        val firstValues = floats(
            first.provide(
                ExternalTensorContext(firstManifest, required, target, frame().let {
                    UprightRgbFrameNormalizer.normalize(it)
                }),
            ).values.single().bytes,
        )
        assertEquals(3, encodes)
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f),
            firstValues,
            1e-6f,
        )

        val thirdManifest = firstManifest.copy(packageId = "reference_third_v3")
        ReferencePrototypeExternalTensorProvider(
            runtimeContext(thirdManifest, target, primary, head),
            provider,
            cache,
            runnerFactory = runnerFactory,
        )
        assertEquals("same artifact and contract must be a cache hit", 3, encodes)

        val secondArtifact = temporaryFolder.newFile("encoder-two.tflite").apply {
            writeText("encoder-two")
        }
        val changedArtifactManifest = manifest(
            "reference_fourth_v4",
            secondArtifact,
            head,
            featureDimension = 3,
        )
        ReferencePrototypeExternalTensorProvider(
            runtimeContext(changedArtifactManifest, target, secondArtifact, head),
            provider,
            cache,
            runnerFactory = runnerFactory,
        )
        assertEquals("new artifact hash must re-encode without source dispatch changes", 6, encodes)
    }

    @Test
    fun `all seven imported references participate in prototype construction`() {
        val primary = temporaryFolder.newFile("seven-reference-encoder.tflite").apply {
            writeText("seven-reference-encoder")
        }
        val head = headFile()
        val assets = (1..7).map { index ->
            referenceAsset(
                index,
                red = (index * 31) % 240,
                green = (index * 67) % 240,
                blue = (index * 101) % 240,
            )
        }
        val provider = ReferenceImageProvider { metadata ->
            assets.singleOrNull { it.localAssetId == metadata.localAssetId }
        }
        val manifest = manifest(
            "seven_reference_provider_v1",
            primary,
            head,
            featureDimension = 3,
        )
        var encodes = 0
        val runnerFactory = ReferenceEmbeddingRunnerFactory {
            object : ReferenceEmbeddingRunner {
                override fun embed(asset: ReferenceImageAsset): FloatArray {
                    encodes++
                    return floatArrayOf(1f, 0f, 0f)
                }

                override fun close() = Unit
            }
        }

        ReferencePrototypeExternalTensorProvider(
            runtimeContext(manifest, target(assets), primary, head),
            provider,
            PersistentReferenceEmbeddingCache(temporaryFolder.newFolder("seven-reference-cache")),
            runnerFactory = runnerFactory,
        )

        assertEquals(7, encodes)
    }

    @Test
    fun `candidate prototype cosine head can select a non first candidate`() {
        val head = headFile()
        val manifest = manifest(
            "cosine_fixture_v1",
            temporaryFolder.newFile("cosine-encoder.tflite").apply { writeText("encoder") },
            head,
            featureDimension = 2,
        )
        val headArtifact = manifest.artifacts.single { it.role == "similarity_head" }
        PrototypeCosineArtifactSession(
            ArtifactSessionContext(
                headArtifact,
                head,
                manifest.inputs.filter { it.artifactRole == headArtifact.role },
                manifest.outputs.filter { it.artifactRole == headArtifact.role },
                4,
            ),
        ).use { session ->
            val result = session.run(
                mapOf(
                    "image_features" to floatTensor(
                        "image_features",
                        floatArrayOf(1f, 0f, 0f, 1f, 3f, 4f, -1f, 0f),
                        listOf(4, 2),
                    ),
                    "reference_features" to floatTensor(
                        "reference_features",
                        floatArrayOf(0.6f, 0.8f),
                    ),
                ),
            ).getValue("similarity_scores")
            assertArrayEquals(floatArrayOf(1f), floats(result.bytes), 1e-6f)
        }
    }

    @Test
    fun `candidate crop orders top three by score then index and reserves full frame`() {
        val crop = cropFile()
        val image = InputComponent(
            InputRole.IMAGE,
            "object_crop",
            0,
            "source_rgb",
            TensorDataType.UINT8,
            listOf(1, 2, 2, 3),
            InputQuantization(QuantizationMode.PER_TENSOR, 1.0 / 127.5, 128),
            TensorLayout.NHWC,
            ColorSpace.RGB,
        )
        val inputs = listOf(
            image,
            floatInput(InputRole.DETECTION_BOXES, 1, "boxes", listOf(1, 4, 4)),
            floatInput(InputRole.DETECTION_CLASSES, 2, "classes", listOf(1, 4)),
            floatInput(InputRole.DETECTION_SCORES, 3, "scores", listOf(1, 4)),
            floatInput(InputRole.DETECTION_COUNT, 4, "count", listOf(1)),
        )
        val output = OutputComponent(
            OutputRole.IMAGE_TENSOR,
            "object_crop",
            0,
            "cropped_image",
            TensorDataType.FLOAT32,
            listOf(4, 1, 1, 3),
        )
        val artifact = artifact(
            "object_crop",
            RuntimeKind.CLASSIC_VISION,
            ArtifactMediaType.CLASSIC_VISION_JSON,
            crop,
        )
        val rgb = byteArrayOf(
            255.toByte(), 0, 0,
            0, 255.toByte(), 0,
            0, 0, 255.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(),
        )
        val boxes = floatArrayOf(
            0f, 0f, 0.5f, 0.5f,
            0f, 0.5f, 0.5f, 1f,
            0.5f, 0f, 1f, 0.5f,
            0.5f, 0.5f, 1f, 1f,
        )

        ProminentObjectCropArtifactSession(
            ArtifactSessionContext(artifact, crop, inputs, listOf(output), 1),
        ).use { session ->
            val result = session.run(
                mapOf(
                    "source_rgb" to RawTensor(
                        "source_rgb",
                        rgb,
                        image.runtimeShape,
                        TensorElementType.UINT8,
                    ),
                    "boxes" to floatTensor("boxes", boxes, listOf(1, 4, 4)),
                    "classes" to floatTensor("classes", FloatArray(4), listOf(1, 4)),
                    "scores" to floatTensor(
                        "scores",
                        floatArrayOf(0.5f, 0.9f, 0.9f, 0.8f),
                        listOf(1, 4),
                    ),
                    "count" to floatTensor("count", floatArrayOf(4f), listOf(1)),
                ),
            ).getValue("cropped_image")

            val fullFrameChannel = 128f / 255f
            assertEquals(listOf(4, 1, 1, 3), result.shape)
            assertArrayEquals(
                floatArrayOf(
                    0f, 1f, 0f,
                    0f, 0f, 1f,
                    1f, 1f, 1f,
                    fullFrameChannel, fullFrameChannel, fullFrameChannel,
                ),
                floats(result.bytes),
                1e-6f,
            )
        }
    }

    @Test
    fun `candidate crop selects signed detections then fills with whole frame`() {
        val crop = cropFile()
        val image = InputComponent(
            InputRole.IMAGE,
            "object_crop",
            0,
            "source_rgb",
            TensorDataType.UINT8,
            listOf(1, 4, 4, 3),
            InputQuantization(QuantizationMode.PER_TENSOR, 1.0 / 127.5, 128),
            TensorLayout.NHWC,
            ColorSpace.RGB,
        )
        val inputs = listOf(
            image,
            floatInput(InputRole.DETECTION_BOXES, 1, "boxes", listOf(1, 2, 4)),
            floatInput(InputRole.DETECTION_CLASSES, 2, "classes", listOf(1, 2)),
            floatInput(InputRole.DETECTION_SCORES, 3, "scores", listOf(1, 2)),
            floatInput(InputRole.DETECTION_COUNT, 4, "count", listOf(1)),
        )
        val output = OutputComponent(
            OutputRole.IMAGE_TENSOR,
            "object_crop",
            0,
            "cropped_image",
            TensorDataType.FLOAT32,
            listOf(4, 2, 2, 3),
        )
        val artifact = artifact(
            "object_crop",
            RuntimeKind.CLASSIC_VISION,
            ArtifactMediaType.CLASSIC_VISION_JSON,
            crop,
        )
        val rgb = ByteArray(4 * 4 * 3).also { bytes ->
            repeat(4 * 4) { pixel ->
                val rightHalf = pixel % 4 >= 2
                bytes[pixel * 3] = if (rightHalf) 0 else 255.toByte()
                bytes[pixel * 3 + 1] = 0
                bytes[pixel * 3 + 2] = if (rightHalf) 255.toByte() else 0
            }
        }
        ProminentObjectCropArtifactSession(
            ArtifactSessionContext(artifact, crop, inputs, listOf(output), 1),
        ).use { session ->
            val result = session.run(
                mapOf(
                    "source_rgb" to RawTensor(
                        "source_rgb",
                        rgb,
                        image.runtimeShape,
                        TensorElementType.UINT8,
                    ),
                    "boxes" to floatTensor(
                        "boxes",
                        floatArrayOf(0f, 0f, 1f, 0.5f, 0f, 0.5f, 1f, 1f),
                        listOf(1, 2, 4),
                    ),
                    "classes" to floatTensor("classes", floatArrayOf(0f, 0f), listOf(1, 2)),
                    "scores" to floatTensor("scores", floatArrayOf(0.1f, 0.9f), listOf(1, 2)),
                    "count" to floatTensor("count", floatArrayOf(2f), listOf(1)),
                ),
            ).getValue("cropped_image")
            val values = floats(result.bytes)
            assertEquals(listOf(4, 2, 2, 3), result.shape)
            assertEquals(0f, values[0], 0f)
            assertEquals(0f, values[1], 0f)
            assertEquals(1f, values[2], 0f)
        }
    }

    @Test
    fun `prominent object crop keeps the complete letterbox when no salient candidate exists`() {
        val crop = cropFile()
        val image = InputComponent(
            InputRole.IMAGE,
            "object_crop",
            0,
            "source_rgb",
            TensorDataType.UINT8,
            listOf(1, 2, 2, 3),
            InputQuantization(QuantizationMode.PER_TENSOR, 1.0 / 127.5, 128),
            TensorLayout.NHWC,
            ColorSpace.RGB,
        )
        val inputs = listOf(
            image,
            floatInput(InputRole.DETECTION_BOXES, 1, "boxes", listOf(1, 2, 4)),
            floatInput(InputRole.DETECTION_CLASSES, 2, "classes", listOf(1, 2)),
            floatInput(InputRole.DETECTION_SCORES, 3, "scores", listOf(1, 2)),
            floatInput(InputRole.DETECTION_COUNT, 4, "count", listOf(1)),
        )
        val output = OutputComponent(
            OutputRole.IMAGE_TENSOR,
            "object_crop",
            0,
            "cropped_image",
            TensorDataType.FLOAT32,
            listOf(4, 2, 2, 3),
        )
        val artifact = artifact(
            "object_crop",
            RuntimeKind.CLASSIC_VISION,
            ArtifactMediaType.CLASSIC_VISION_JSON,
            crop,
        )
        val rgb = byteArrayOf(
            255.toByte(), 0, 0,
            0, 255.toByte(), 0,
            0, 0, 255.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(),
        )

        ProminentObjectCropArtifactSession(
            ArtifactSessionContext(artifact, crop, inputs, listOf(output), 1),
        ).use { session ->
            val result = session.run(
                mapOf(
                    "source_rgb" to RawTensor(
                        "source_rgb",
                        rgb,
                        image.runtimeShape,
                        TensorElementType.UINT8,
                    ),
                    "boxes" to floatTensor("boxes", FloatArray(8), listOf(1, 2, 4)),
                    "classes" to floatTensor("classes", FloatArray(2), listOf(1, 2)),
                    "scores" to floatTensor("scores", FloatArray(2), listOf(1, 2)),
                    "count" to floatTensor("count", floatArrayOf(0f), listOf(1)),
                ),
            ).getValue("cropped_image")

            assertEquals(listOf(4, 2, 2, 3), result.shape)
            assertArrayEquals(
                FloatArray(4).flatMap {
                    listOf(
                    1f, 0f, 0f,
                    0f, 1f, 0f,
                    0f, 0f, 1f,
                    1f, 1f, 1f,
                    )
                }.toFloatArray(),
                floats(result.bytes),
                0f,
            )
        }
    }

    @Test
    fun `four prototype head returns the best quarter turn cosine`() {
        val head = headFile()
        val manifest = manifest(
            "quarter_turn_cosine_fixture_v1",
            temporaryFolder.newFile("quarter-turn-encoder.tflite").apply { writeText("encoder") },
            head,
            featureDimension = 2,
            prototypeCount = 4,
        )
        val headArtifact = manifest.artifacts.single { it.role == "similarity_head" }
        PrototypeCosineArtifactSession(
            ArtifactSessionContext(
                headArtifact,
                head,
                manifest.inputs.filter { it.artifactRole == headArtifact.role },
                manifest.outputs.filter { it.artifactRole == headArtifact.role },
                4,
            ),
        ).use { session ->
            val result = session.run(
                mapOf(
                    "image_features" to floatTensor(
                        "image_features",
                        floatArrayOf(0f, 2f, 1f, 1f, 2f, 0f, -2f, -2f),
                        listOf(4, 2),
                    ),
                    "reference_features" to floatTensor(
                        "reference_features",
                        floatArrayOf(1f, 0f, 0f, -1f, 0f, 1f, -1f, 0f),
                        listOf(4, 2),
                    ),
                ),
            ).getValue("similarity_scores")
            assertArrayEquals(floatArrayOf(1f), floats(result.bytes), 1e-6f)
        }
    }

    @Test
    fun `four prototype provider embeds every signed quarter turn and caches each orientation`() {
        val primary = temporaryFolder.newFile("quarter-turn-provider.tflite").apply {
            writeText("quarter-turn-provider")
        }
        val head = headFile()
        val assets = referenceAssets()
        val provider = ReferenceImageProvider { metadata ->
            assets.singleOrNull { it.localAssetId == metadata.localAssetId }
        }
        val manifest = manifest(
            "quarter_turn_provider_v1",
            primary,
            head,
            featureDimension = 4,
            prototypeCount = 4,
        )
        val cache = PersistentReferenceEmbeddingCache(temporaryFolder.newFolder("quarter-turn-cache"))
        var encodes = 0
        val runnerFactory = ReferenceEmbeddingRunnerFactory {
            object : ReferenceEmbeddingRunner {
                override fun embed(asset: ReferenceImageAsset): FloatArray {
                    encodes++
                    val orientation = when {
                        asset.localAssetId.endsWith("@rotate-90") -> 1
                        asset.localAssetId.endsWith("@rotate-180") -> 2
                        asset.localAssetId.endsWith("@rotate-270") -> 3
                        else -> 0
                    }
                    return FloatArray(4) { if (it == orientation) 1f else 0f }
                }

                override fun close() = Unit
            }
        }
        fun buildTensor() = ReferencePrototypeExternalTensorProvider(
            runtimeContext(manifest, target(assets), primary, head),
            provider,
            cache,
            runnerFactory = runnerFactory,
        ).provide(
            ExternalTensorContext(
                manifest,
                manifest.inputs.filter { it.role == InputRole.REFERENCE_FEATURES },
                target(assets),
            ),
        ).values.single()

        val first = buildTensor()
        assertEquals(listOf(4, 4), first.shape)
        assertArrayEquals(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
            floats(first.bytes),
            1e-6f,
        )
        assertEquals(12, encodes)
        buildTensor()
        assertEquals("each orientation must have an exact persistent cache identity", 12, encodes)
    }

    @Test
    fun `twelve prototype provider adds finite center crops with exact cache identities`() {
        val primary = temporaryFolder.newFile("multi-scale-provider.tflite").apply {
            writeText("multi-scale-provider")
        }
        val head = headFile()
        val assets = referenceAssets()
        val provider = ReferenceImageProvider { metadata ->
            assets.singleOrNull { it.localAssetId == metadata.localAssetId }
        }
        val manifest = manifest(
            "multi_scale_provider_v1",
            primary,
            head,
            featureDimension = 12,
            prototypeCount = 12,
        )
        val cache = PersistentReferenceEmbeddingCache(temporaryFolder.newFolder("multi-scale-cache"))
        var encodes = 0
        val runnerFactory = ReferenceEmbeddingRunnerFactory {
            object : ReferenceEmbeddingRunner {
                override fun embed(asset: ReferenceImageAsset): FloatArray {
                    encodes++
                    val scaleOffset = when {
                        "@crop-75" in asset.localAssetId -> 4
                        "@crop-50" in asset.localAssetId -> 8
                        else -> 0
                    }
                    val rotationOffset = when {
                        asset.localAssetId.endsWith("@rotate-90") -> 1
                        asset.localAssetId.endsWith("@rotate-180") -> 2
                        asset.localAssetId.endsWith("@rotate-270") -> 3
                        else -> 0
                    }
                    val expectedEdge = if ('@' in asset.localAssetId) 2 else 16
                    assertEquals("prototype transforms must be bounded to model input", expectedEdge, asset.width)
                    assertEquals("prototype transforms must be bounded to model input", expectedEdge, asset.height)
                    return FloatArray(12) { if (it == scaleOffset + rotationOffset) 1f else 0f }
                }

                override fun close() = Unit
            }
        }
        fun buildTensor() = ReferencePrototypeExternalTensorProvider(
            runtimeContext(manifest, target(assets), primary, head),
            provider,
            cache,
            runnerFactory = runnerFactory,
        ).provide(
            ExternalTensorContext(
                manifest,
                manifest.inputs.filter { it.role == InputRole.REFERENCE_FEATURES },
                target(assets),
            ),
        ).values.single()

        val first = buildTensor()
        assertEquals(listOf(12, 12), first.shape)
        val expected = FloatArray(12 * 12) { index -> if (index / 12 == index % 12) 1f else 0f }
        assertArrayEquals(expected, floats(first.bytes), 1e-6f)
        assertEquals(36, encodes)
        buildTensor()
        assertEquals("crop and rotation cache identities must both be exact", 36, encodes)
    }

    private fun manifest(
        packageId: String,
        primary: File,
        head: File,
        featureDimension: Int,
        prototypeCount: Int = 1,
    ) = ModelPackageManifest(
        schemaVersion = "3.0",
        packageId = packageId,
        packageVersion = "1.0.0",
        runtimeFamily = RecipeFamily.SIMILARITY_MATCH_V1,
        supportedTasks = setOf(SupportedTask.VISIBLE_STATE),
        promptModes = setOf(TargetMode.REFERENCE_IMAGES),
        modelSource = source("model"),
        weightsSource = source("weights"),
        codeSource = source("code"),
        exportToolSource = source("export"),
        license = ModelPackageLicense(
            "Apache-2.0",
            "d".repeat(64),
            LicenseReviewStatus.APPROVED,
            "2026-08-03T00:00:00Z",
            "evidence/license.md",
            true,
            true,
            false,
        ),
        artifacts = listOf(
            artifact("primary", RuntimeKind.LITERT, ArtifactMediaType.LITERT, primary),
            artifact(
                "similarity_head",
                RuntimeKind.CLASSIC_VISION,
                ArtifactMediaType.CLASSIC_VISION_JSON,
                head,
            ),
        ),
        inputs = listOf(
            imageInput(2, 2),
            InputComponent(
                InputRole.IMAGE_FEATURES,
                "similarity_head",
                0,
                "image_features",
                TensorDataType.FLOAT32,
                listOf(4, featureDimension),
                InputQuantization(QuantizationMode.NONE),
            ),
            InputComponent(
                InputRole.REFERENCE_FEATURES,
                "similarity_head",
                1,
                "reference_features",
                TensorDataType.FLOAT32,
                listOf(prototypeCount, featureDimension),
                InputQuantization(QuantizationMode.NONE),
            ),
        ),
        outputs = listOf(
            OutputComponent(
                OutputRole.IMAGE_FEATURES,
                "primary",
                0,
                "image_embedding",
                TensorDataType.FLOAT32,
                listOf(4, featureDimension),
            ),
            OutputComponent(
                OutputRole.SIMILARITY_SCORES,
                "similarity_head",
                0,
                "similarity_scores",
                TensorDataType.FLOAT32,
                listOf(1, 1),
            ),
        ),
        bindings = listOf(
            TensorBinding("primary", "image_embedding", "similarity_head", "image_features"),
        ),
        adapterContract = AdapterContract("similarity_match_v1", null, null),
        preprocessId = NeuralReferenceRuntimeComponents.PREPROCESS_ID,
        postprocessId = NeuralReferenceRuntimeComponents.POSTPROCESS_ID,
        parameterProfile = ParameterProfile(
            mapOf(
                "match_threshold" to 0.2,
                "rejection_margin" to 0.02,
            ),
            mapOf(
                "match_threshold" to ParameterBound(-1.0, 1.0),
                "rejection_margin" to ParameterBound(0.0, 0.5),
            ),
            SamplingPolicySpec(500, 100, 2_000, true),
        ),
        deviceCompatibility = DeviceCompatibility(
            setOf("android_arm64_8gb_launch_v1"),
            26,
            null,
            setOf("arm64-v8a"),
            8_192,
            emptySet(),
        ),
        signature = ManifestSignature("RFC8785", "Ed25519", "test-key", "AA=="),
    )

    private fun runtimeContext(
        manifest: ModelPackageManifest,
        target: TargetProfile.ReferenceImages,
        primary: File,
        head: File,
    ) = RuntimeComponentContext(
        manifest,
        target,
        primary,
        4,
        mapOf("primary" to primary, "similarity_head" to head),
    )

    private fun imageInput(width: Int, height: Int) = InputComponent(
        InputRole.IMAGE,
        "primary",
        0,
        "image",
        TensorDataType.FLOAT32,
        listOf(1, height, width, 3),
        InputQuantization(QuantizationMode.NONE),
        TensorLayout.NHWC,
        ColorSpace.RGB,
    )

    private fun floatInput(
        role: InputRole,
        index: Int,
        name: String,
        shape: List<Int>,
    ) = InputComponent(
        role,
        "object_crop",
        index,
        name,
        TensorDataType.FLOAT32,
        shape,
        InputQuantization(QuantizationMode.NONE),
    )

    private fun artifact(
        role: String,
        runtime: RuntimeKind,
        mediaType: ArtifactMediaType,
        file: File,
    ) = ArtifactComponent(
        role,
        runtime,
        mediaType,
        "https://example.test/$role.bin",
        sha256(file.readBytes()),
        file.length(),
    )

    private fun headFile() = temporaryFolder.newFile("head-${System.nanoTime()}.json").apply {
        writeText("""{"schema_version":"1.0","runtime_family":"l2_prototype_cosine_candidates_v2"}""")
    }

    private fun cropFile() = temporaryFolder.newFile("crop-${System.nanoTime()}.json").apply {
        writeText(
            """{"schema_version":"1.0","runtime_family":"prominent_object_candidates_v2","selection":"top_three_plus_full_frame_v2","box_encoding":"ymin_xmin_ymax_xmax_normalized_v1","score_threshold":0.2,"padding_fraction":0.0}""",
        )
    }

    private fun referenceAssets() = listOf(
        referenceAsset(1, 255, 0, 0),
        referenceAsset(2, 0, 255, 0),
        referenceAsset(3, 0, 0, 255),
    )

    private fun referenceAsset(index: Int, red: Int, green: Int, blue: Int): ReferenceImageAsset {
        val rgb = ByteArray(16 * 16 * 3).also { bytes ->
            repeat(16 * 16) { pixel ->
                val x = pixel % 16
                val y = pixel / 16
                val accent = if ((x / 2 + y / 2 + index) % 2 == 0) 24 else -24
                bytes[pixel * 3] = (red + accent).coerceIn(8, 247).toByte()
                bytes[pixel * 3 + 1] = (green - accent).coerceIn(8, 247).toByte()
                bytes[pixel * 3 + 2] = (blue + accent / 2).coerceIn(8, 247).toByte()
            }
        }
        return ReferenceImageAsset("asset-$index", sha256(rgb), 16, 16, rgb)
    }

    private fun target(assets: List<ReferenceImageAsset>) = TargetProfile.ReferenceImages(
        "door",
        assets.mapIndexed { index, asset ->
            ReferenceImageMetadata(
                "ref-${index + 1}",
                asset.localAssetId,
                asset.contentSha256,
                asset.width,
                asset.height,
            )
        },
    )

    private fun frame() = SourceFrame(
        1,
        0,
        null,
        1,
        1,
        0,
        PixelRect(0, 0, 1, 1),
        FramePixels.Rgb888(byteArrayOf(255.toByte(), 0, 0), 3),
    )

    private fun floatTensor(
        name: String,
        values: FloatArray,
        shape: List<Int> = listOf(1, values.size),
    ) = RawTensor(
        name,
        ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { buffer -> values.forEach(buffer::putFloat) }
            .array(),
        shape,
        TensorElementType.FLOAT32,
    )

    private fun floats(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        .let { buffer -> FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float } }

    private fun source(name: String) = SourceReference("https://example.test/$name", "1")
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

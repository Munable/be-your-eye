package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

interface ReadingScanController {
    fun updateManualScanRegion(region: NormalizedRect?)
    fun resetAutomaticAnchor()
}

/**
 * Automatic mode starts every frame with scene-level line discovery. An automatic box is coordinate
 * feedback and never becomes an inference ROI. An explicit manual region is already the user's
 * strict, single-line recognition range, so it goes directly to the recognizer without repeating
 * whole-scene discovery. Automatic selection tries lines from the visible frame's centre outward.
 * The nearest readable line establishes a fixed-scene anchor; a miss never switches to a neighbour.
 */
class ReadingScenePipeline(
    private val normalizer: FrameNormalizer,
    private val qualityGate: QualityGate,
    private val detectorInput: InputComponent,
    private val detectorOutput: OutputComponent,
    private val detectorPreprocessor: InputPreprocessor,
    private val detectorSession: ArtifactExecutionSession,
    private val recognizerInput: InputComponent,
    private val recognizerPreprocessor: InputPreprocessor,
    private val recognizerSession: ArtifactExecutionSession,
    private val recognizerAdapter: OutputAdapter,
    pixelThreshold: Float,
    boxThreshold: Float,
    unclipRatio: Float,
    maximumCandidates: Int,
    initialManualScanRegion: NormalizedRect?,
    private val enforceAutomaticShapeGuard: Boolean = true,
    private val clock: NanoClock = SystemNanoClock,
) : VisionPipeline, ReadingScanController {
    private val locator = PpOcrDbTextLineLocator(
        pixelThreshold = pixelThreshold,
        boxThreshold = boxThreshold,
        unclipRatio = unclipRatio,
        maximumCandidates = maximumCandidates,
    )
    private val stateLock = Any()
    private var manualScanRegion: NormalizedRect? = initialManualScanRegion
    private var automaticAnchor: NormalizedRect? = null
    private var targetTrackId: Long = 0

    init {
        require(detectorInput.artifactRole == detectorSession.artifactRole)
        require(detectorOutput.artifactRole == detectorSession.artifactRole)
        require(recognizerInput.artifactRole == recognizerSession.artifactRole)
        require(detectorOutput.role == OutputRole.TEXT_PROBABILITY_MAP)
    }

    override fun updateManualScanRegion(region: NormalizedRect?) = synchronized(stateLock) {
        if (manualScanRegion == region) return@synchronized
        manualScanRegion = region
        automaticAnchor = null
        targetTrackId = nextTrackId(targetTrackId)
    }

    override fun resetAutomaticAnchor() = synchronized(stateLock) {
        automaticAnchor = null
        targetTrackId = nextTrackId(targetTrackId)
    }

    override fun process(frame: SourceFrame): PipelineResult {
        var normalizeNanos = 0L
        var qualityNanos = 0L
        var preprocessNanos = 0L
        var inferenceNanos = 0L
        var adapterNanos = 0L
        var locatorPreprocessNanos = 0L
        var locatorInferenceNanos = 0L
        var locatorAdapterNanos = 0L
        var recognizerPreprocessNanos = 0L
        var recognizerInferenceNanos = 0L
        var recognizerAdapterNanos = 0L

        fun result(observation: Observation, adapterCompleted: Boolean = false) = PipelineResult(
            sourceSequence = frame.sourceSequence,
            monotonicTimeMillis = frame.monotonicTimeMillis,
            capturedAtEpochMillis = frame.capturedAtEpochMillis,
            observation = observation,
            timings = PipelineTimings(
                normalizeNanos = normalizeNanos,
                qualityNanos = qualityNanos,
                preprocessNanos = preprocessNanos,
                inferenceNanos = inferenceNanos,
                adapterNanos = adapterNanos,
                readingStages = ReadingPipelineStageTimings(
                    locatorPreprocessNanos = locatorPreprocessNanos,
                    locatorInferenceNanos = locatorInferenceNanos,
                    locatorAdapterNanos = locatorAdapterNanos,
                    recognizerPreprocessNanos = recognizerPreprocessNanos,
                    recognizerInferenceNanos = recognizerInferenceNanos,
                    recognizerAdapterNanos = recognizerAdapterNanos,
                ),
            ),
            adapterCompleted = adapterCompleted,
        )

        val canonical = try {
            measured({ normalizeNanos += it }) { normalizer.normalize(frame) }
        } catch (_: Exception) {
            return result(unavailable(frame, "normalize_failed", UnavailableReason.INFERENCE_ERROR))
        }
        val qualityFrame = synchronized(stateLock) { manualScanRegion }?.let(canonical::crop) ?: canonical
        val quality = try {
            measured({ qualityNanos += it }) { qualityGate.evaluate(qualityFrame) }
        } catch (_: Exception) {
            return result(unavailable(frame, "quality_gate_failed", UnavailableReason.INFERENCE_ERROR))
        }
        if (quality is QualityGateResult.Rejected) {
            return result(unavailable(frame, quality.diagnosticCode, quality.reason))
        }

        val discovery = try {
            val manualRegion = synchronized(stateLock) { manualScanRegion }
            if (manualRegion == null) {
                discover(
                    frame = canonical,
                    addPreprocessNanos = { preprocessNanos += it },
                    addInferenceNanos = { inferenceNanos += it },
                    addAdapterNanos = { adapterNanos += it },
                    addLocatorPreprocessNanos = { locatorPreprocessNanos += it },
                    addLocatorInferenceNanos = { locatorInferenceNanos += it },
                    addLocatorAdapterNanos = { locatorAdapterNanos += it },
                    addRecognizerPreprocessNanos = { recognizerPreprocessNanos += it },
                    addRecognizerInferenceNanos = { recognizerInferenceNanos += it },
                    addRecognizerAdapterNanos = { recognizerAdapterNanos += it },
                )
            } else {
                synchronized(stateLock) {
                    if (targetTrackId == 0L) targetTrackId = nextTrackId(targetTrackId)
                }
                recognize(
                    frame = canonical,
                    box = manualRegion,
                    addPreprocessNanos = { preprocessNanos += it },
                    addInferenceNanos = { inferenceNanos += it },
                    addAdapterNanos = { adapterNanos += it },
                    addRecognizerPreprocessNanos = { recognizerPreprocessNanos += it },
                    addRecognizerInferenceNanos = { recognizerInferenceNanos += it },
                    addRecognizerAdapterNanos = { recognizerAdapterNanos += it },
                )
            }
        } catch (_: Exception) {
            return result(unavailable(frame, "locator_failed", UnavailableReason.INFERENCE_ERROR))
        }
        return result(discovery, adapterCompleted = true)
    }

    private fun discover(
        frame: CanonicalFrame,
        addPreprocessNanos: (Long) -> Unit,
        addInferenceNanos: (Long) -> Unit,
        addAdapterNanos: (Long) -> Unit,
        addLocatorPreprocessNanos: (Long) -> Unit,
        addLocatorInferenceNanos: (Long) -> Unit,
        addLocatorAdapterNanos: (Long) -> Unit,
        addRecognizerPreprocessNanos: (Long) -> Unit,
        addRecognizerInferenceNanos: (Long) -> Unit,
        addRecognizerAdapterNanos: (Long) -> Unit,
    ): Observation {
        val state = synchronized(stateLock) { manualScanRegion to automaticAnchor }
        val region = state.first
        val scanFrame = region?.let(frame::crop) ?: frame
        val prepared = measured({ nanos ->
            addPreprocessNanos(nanos)
            addLocatorPreprocessNanos(nanos)
        }) { detectorPreprocessor.prepare(scanFrame) }
        val detectorResult = measured({ nanos ->
            addInferenceNanos(nanos)
            addLocatorInferenceNanos(nanos)
        }) {
            detectorSession.run(
                mapOf(detectorInput.tensorName to prepared.toRawTensor(detectorInput.tensorName)),
            )
        }
        val probabilityMap = detectorResult[detectorOutput.tensorName]
            ?: return unavailable(frame, "locator_tensor_missing", UnavailableReason.INCOMPATIBLE_OUTPUT)
        val located = measured({ nanos ->
            addAdapterNanos(nanos)
            addLocatorAdapterNanos(nanos)
        }) {
            locator.locate(probabilityMap, prepared.transform).map { located ->
                if (region == null) located else located.copy(box = located.box.fromRegion(region))
            }
        }
        if (located.isEmpty()) {
            return unavailable(frame, "numeric_target_not_found", UnavailableReason.LOW_QUALITY)
        }
        // Automatic discovery is deliberately fail-closed for scene-sized blobs. A DB detector
        // can merge a card, clock face, or a multi-row display into one high-confidence component;
        // sending that crop to the recognizer turns unrelated digits into a false reading. A
        // manually drawn region remains the explicit user choice and is not constrained by this
        // heuristic. The bounds are normalized, package-neutral, and only reject boxes that are
        // visibly not a single text line.
        val shapeFiltered = if (region == null && enforceAutomaticShapeGuard) {
            located.filter { it.isPlausibleAutomaticLine() }
        } else {
            located
        }
        if (shapeFiltered.isEmpty()) {
            return unavailable(frame, "numeric_target_shape_unavailable", UnavailableReason.LOW_QUALITY)
        }
        val existingAnchor = state.second
        val candidates = if (existingAnchor == null) {
            shapeFiltered.sortedWith(
                compareBy<LocatedTextLine> { it.box.distanceToFrameCentreSquared(frame) }
                    .thenByDescending { it.score }
                    .thenBy { it.box.top }
                    .thenBy { it.box.left },
            ).take(MAX_RECOGNIZED_CANDIDATES)
        } else {
            shapeFiltered.filter { it.box.matchesAnchor(existingAnchor) }
                .sortedBy { boxDistance(it.box, existingAnchor) }
                .take(1)
        }
        if (candidates.isEmpty()) {
            return unavailable(frame, "structured_reading_anchor_missing", UnavailableReason.LOW_QUALITY)
        }
        val chosen = candidates.firstNotNullOfOrNull { candidate ->
            val reading = recognize(
                frame = frame,
                box = candidate.box,
                addPreprocessNanos = addPreprocessNanos,
                addInferenceNanos = addInferenceNanos,
                addAdapterNanos = addAdapterNanos,
                addRecognizerPreprocessNanos = addRecognizerPreprocessNanos,
                addRecognizerInferenceNanos = addRecognizerInferenceNanos,
                addRecognizerAdapterNanos = addRecognizerAdapterNanos,
            ) as? Observation.Reading
            reading?.let { CandidateReading(candidate, it) }
        } ?: return unavailable(frame, "structured_reading_candidates_unreadable", UnavailableReason.LOW_QUALITY)
        val trackId = synchronized(stateLock) {
            if (automaticAnchor == null) {
                automaticAnchor = chosen.located.box
                targetTrackId = nextTrackId(targetTrackId)
            }
            targetTrackId
        }
        return chosen.reading.copy(
            anchorBox = chosen.located.box,
            targetTrackId = trackId,
        )
    }

    private fun recognize(
        frame: CanonicalFrame,
        box: NormalizedRect,
        addPreprocessNanos: (Long) -> Unit,
        addInferenceNanos: (Long) -> Unit,
        addAdapterNanos: (Long) -> Unit,
        addRecognizerPreprocessNanos: (Long) -> Unit,
        addRecognizerInferenceNanos: (Long) -> Unit,
        addRecognizerAdapterNanos: (Long) -> Unit,
    ): Observation {
        val crop = frame.crop(box)
        val prepared = measured({ nanos ->
            addPreprocessNanos(nanos)
            addRecognizerPreprocessNanos(nanos)
        }) { recognizerPreprocessor.prepare(crop) }
        val output = measured({ nanos ->
            addInferenceNanos(nanos)
            addRecognizerInferenceNanos(nanos)
        }) {
            recognizerSession.run(
                mapOf(recognizerInput.tensorName to prepared.toRawTensor(recognizerInput.tensorName)),
            )
        }
        val observation = measured({ nanos ->
            addAdapterNanos(nanos)
            addRecognizerAdapterNanos(nanos)
        }) {
            recognizerAdapter.toObservation(
                RawTensorOutput(output.values.toList()),
                prepared.transform,
                crop,
            )
        }
        return if (observation is Observation.Reading) {
            observation.copy(
                anchorBox = box,
                targetTrackId = synchronized(stateLock) { targetTrackId },
            )
        } else {
            observation
        }
    }

    private inline fun <T> measured(addNanos: (Long) -> Unit, block: () -> T): T {
        val started = clock.nanoTime()
        return try {
            block()
        } finally {
            addNanos((clock.nanoTime() - started).coerceAtLeast(0L))
        }
    }

    private fun unavailable(
        frame: SourceFrame,
        code: String,
        reason: UnavailableReason,
    ) = Observation.Unavailable(reason, code, frame.sourceSequence)

    private fun unavailable(
        frame: CanonicalFrame,
        code: String,
        reason: UnavailableReason,
    ) = Observation.Unavailable(reason, code, frame.sourceSequence)

    private companion object {
        const val MAX_RECOGNIZED_CANDIDATES = 5
    }

    private data class CandidateReading(
        val located: LocatedTextLine,
        val reading: Observation.Reading,
    )
}

data class LocatedTextLine(
    /** Recognition crop after the signed unclip ratio has been applied. */
    val box: NormalizedRect,
    val score: Float,
    /** Detector component before unclip padding; used only to classify edge-clamped lines. */
    val detectorCoreBox: NormalizedRect = box,
)

/** Deterministic DB probability-map postprocess without an Android/OpenCV dependency. */
class PpOcrDbTextLineLocator(
    private val pixelThreshold: Float,
    private val boxThreshold: Float,
    private val unclipRatio: Float,
    private val maximumCandidates: Int,
) {
    private var probabilityScratch = FloatArray(0)
    private var foregroundScratch = BooleanArray(0)
    private var horizontalScratch = BooleanArray(0)
    private var connectedScratch = BooleanArray(0)
    private var visitedScratch = BooleanArray(0)
    private var queueScratch = IntArray(0)

    init {
        require(pixelThreshold in 0f..1f)
        require(boxThreshold in 0f..1f)
        require(unclipRatio in 1f..3f)
        require(maximumCandidates in 1..3_000)
    }

    fun locate(tensor: RawTensor, transform: LetterboxTransform): List<LocatedTextLine> {
        require(tensor.elementType == TensorElementType.FLOAT32)
        require(tensor.shape.size == 4 && tensor.shape[0] == 1 && tensor.shape[1] == 1)
        val height = tensor.shape[2]
        val width = tensor.shape[3]
        require(tensor.bytes.size == width * height * Float.SIZE_BYTES)
        val elementCount = width * height
        ensureScratchCapacity(elementCount)
        ByteBuffer.wrap(tensor.bytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .get(probabilityScratch, 0, elementCount)
        repeat(elementCount) { index ->
            val probability = probabilityScratch[index]
            require(probability.isFinite())
            foregroundScratch[index] = probability >= pixelThreshold
        }
        val connected = dilate(
            foregroundScratch,
            width,
            height,
            horizontalRadius = max(2, width / 64),
            verticalRadius = max(1, height / 192),
        )
        java.util.Arrays.fill(visitedScratch, false)
        val visited = visitedScratch
        // A boxed ArrayDeque allocates one Integer per visited probability-map pixel. A reusable
        // primitive queue keeps full-screen discovery work-conserving on mid-range phones.
        val queue = queueScratch
        val candidates = mutableListOf<LocatedTextLine>()
        for (start in connected.indices) {
            if (!connected[start] || visited[start]) continue
            visited[start] = true
            var queueHead = 0
            var queueTail = 0
            queue[queueTail++] = start
            var left = start % width
            var right = left
            var top = start / width
            var bottom = top
            while (queueHead < queueTail) {
                val value = queue[queueHead++]
                val x = value % width
                val y = value / width
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)
                fun visit(nx: Int, ny: Int) {
                    if (nx !in 0 until width || ny !in 0 until height) return
                    val index = ny * width + nx
                    if (connected[index] && !visited[index]) {
                        visited[index] = true
                        queue[queueTail++] = index
                    }
                }
                visit(x - 1, y)
                visit(x + 1, y)
                visit(x, y - 1)
                visit(x, y + 1)
            }
            val componentWidth = right - left + 1
            val componentHeight = bottom - top + 1
            if (componentWidth < MIN_COMPONENT_SIDE || componentHeight < MIN_COMPONENT_SIDE) continue
            var scoreSum = 0.0
            var positiveCount = 0
            for (y in top..bottom) for (x in left..right) {
                val probability = probabilityScratch[y * width + x]
                if (probability >= pixelThreshold) {
                    scoreSum += probability
                    positiveCount++
                }
            }
            if (positiveCount < MIN_POSITIVE_PIXELS) continue
            val score = (scoreSum / positiveCount).toFloat()
            if (score < boxThreshold) continue
            val expandedWidth = componentWidth * unclipRatio
            val expandedHeight = componentHeight * unclipRatio
            val centerX = (left + right + 1) / 2f
            val centerY = (top + bottom + 1) / 2f
            val expandedLeft = centerX - expandedWidth / 2f
            val expandedRight = centerX + expandedWidth / 2f
            val expandedTop = centerY - expandedHeight / 2f
            val expandedBottom = centerY + expandedHeight / 2f
            val detectorCoreBox = transform.toSourceBox(
                left.toFloat(),
                top.toFloat(),
                (right + 1).toFloat(),
                (bottom + 1).toFloat(),
            ) ?: continue
            val box = transform.toSourceBox(
                expandedLeft,
                expandedTop,
                expandedRight,
                expandedBottom,
            ) ?: continue
            candidates += LocatedTextLine(box, score, detectorCoreBox)
            if (candidates.size >= maximumCandidates) break
        }
        return candidates
            .sortedWith(compareByDescending<LocatedTextLine> { it.score }.thenByDescending {
                (it.box.right - it.box.left) * (it.box.bottom - it.box.top)
            })
            .fold(mutableListOf()) { kept, candidate ->
                if (kept.none { intersectionOverUnion(it.box, candidate.box) >= DUPLICATE_IOU }) {
                    kept += candidate
                }
                kept
            }
    }

    private fun dilate(
        source: BooleanArray,
        width: Int,
        height: Int,
        horizontalRadius: Int,
        verticalRadius: Int,
    ): BooleanArray {
        val horizontal = horizontalScratch
        repeat(height) { y ->
            val rowStart = y * width
            var active = 0
            for (x in 0..horizontalRadius.coerceAtMost(width - 1)) {
                if (source[rowStart + x]) active++
            }
            repeat(width) { x ->
                horizontal[rowStart + x] = active > 0
                val leaving = x - horizontalRadius
                if (leaving >= 0 && source[rowStart + leaving]) active--
                val entering = x + horizontalRadius + 1
                if (entering < width && source[rowStart + entering]) active++
            }
        }
        val result = connectedScratch
        repeat(width) { x ->
            var active = 0
            for (y in 0..verticalRadius.coerceAtMost(height - 1)) {
                if (horizontal[y * width + x]) active++
            }
            repeat(height) { y ->
                result[y * width + x] = active > 0
                val leaving = y - verticalRadius
                if (leaving >= 0 && horizontal[leaving * width + x]) active--
                val entering = y + verticalRadius + 1
                if (entering < height && horizontal[entering * width + x]) active++
            }
        }
        return result
    }

    private fun ensureScratchCapacity(elementCount: Int) {
        if (probabilityScratch.size == elementCount) return
        probabilityScratch = FloatArray(elementCount)
        foregroundScratch = BooleanArray(elementCount)
        horizontalScratch = BooleanArray(elementCount)
        connectedScratch = BooleanArray(elementCount)
        visitedScratch = BooleanArray(elementCount)
        queueScratch = IntArray(elementCount)
    }

    private fun LetterboxTransform.toSourceBox(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): NormalizedRect? {
        val sourceLeft = ((left - offsetX) / scaleX / sourceWidth).coerceIn(0f, 1f)
        val sourceTop = ((top - offsetY) / scaleY / sourceHeight).coerceIn(0f, 1f)
        val sourceRight = ((right - offsetX) / scaleX / sourceWidth).coerceIn(0f, 1f)
        val sourceBottom = ((bottom - offsetY) / scaleY / sourceHeight).coerceIn(0f, 1f)
        return if (sourceLeft < sourceRight && sourceTop < sourceBottom) {
            NormalizedRect(sourceLeft, sourceTop, sourceRight, sourceBottom)
        } else {
            null
        }
    }

    private companion object {
        const val MIN_COMPONENT_SIDE = 3
        const val MIN_POSITIVE_PIXELS = 4
        const val DUPLICATE_IOU = 0.65f
    }
}

private fun PreparedInput.toRawTensor(name: String) = RawTensor(name, bytes, shape, elementType)

private fun CanonicalFrame.crop(box: NormalizedRect): CanonicalFrame {
    val left = floor(box.left * width).toInt().coerceIn(0, width - 1)
    val top = floor(box.top * height).toInt().coerceIn(0, height - 1)
    val right = ceil(box.right * width).toInt().coerceIn(left + 1, width)
    val bottom = ceil(box.bottom * height).toInt().coerceIn(top + 1, height)
    val cropWidth = right - left
    val cropHeight = bottom - top
    val output = ByteArray(cropWidth * cropHeight * 3)
    repeat(cropHeight) { row ->
        val sourceOffset = ((top + row) * width + left) * 3
        rgb888.copyInto(
            output,
            destinationOffset = row * cropWidth * 3,
            startIndex = sourceOffset,
            endIndex = sourceOffset + cropWidth * 3,
        )
    }
    return copy(width = cropWidth, height = cropHeight, rgb888 = output)
}

private fun boxDistance(left: NormalizedRect, right: NormalizedRect): Float {
    val dx = left.centerX - right.centerX
    val dy = left.centerY - right.centerY
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun NormalizedRect.distanceToFrameCentreSquared(frame: CanonicalFrame): Float {
    val dx = (centerX - 0.5f) * frame.width
    val dy = (centerY - 0.5f) * frame.height
    return dx * dx + dy * dy
}

private fun NormalizedRect.fromRegion(region: NormalizedRect): NormalizedRect {
    val width = region.right - region.left
    val height = region.bottom - region.top
    return NormalizedRect(
        left = region.left + left * width,
        top = region.top + top * height,
        right = region.left + right * width,
        bottom = region.top + bottom * height,
    )
}

private fun NormalizedRect.matchesAnchor(anchor: NormalizedRect): Boolean {
    if (intersectionOverUnion(this, anchor) >= MIN_ANCHOR_IOU) return true
    // A wide number must not create a tall acceptance area that includes neighbouring rows.
    val horizontalTolerance = maxOf(MIN_ANCHOR_DISTANCE, (anchor.right - anchor.left) * 0.75f)
    val verticalTolerance = maxOf(MIN_ANCHOR_DISTANCE, (anchor.bottom - anchor.top) * 0.75f)
    return kotlin.math.abs(centerX - anchor.centerX) <= horizontalTolerance &&
        kotlin.math.abs(centerY - anchor.centerY) <= verticalTolerance
}

private fun LocatedTextLine.isPlausibleAutomaticLine(): Boolean {
    if (score < MIN_AUTOMATIC_LINE_SCORE) return false
    if (box.isPlausibleAutomaticLine()) return true
    // The signed unclip ratio adds recognition context. On a close display that padding can clamp
    // to a frame edge and make an otherwise valid single detector line look scene-sized. Admit
    // only that exact case: the padded crop may exceed the width guard when it clamps to a
    // horizontal edge, but it must still satisfy the expanded-height guard and the unpadded
    // detector component must remain a plausible line. A genuinely scene-sized component keeps
    // failing on either its expanded height or its own core geometry.
    val expandedHeight = (box.bottom - box.top).coerceAtLeast(0f)
    return box.touchesHorizontalFrameEdge() &&
        expandedHeight <= MAX_AUTOMATIC_LINE_HEIGHT &&
        detectorCoreBox.isPlausibleAutomaticLine()
}

private fun NormalizedRect.touchesHorizontalFrameEdge(): Boolean =
    left <= FRAME_EDGE_EPSILON || right >= 1f - FRAME_EDGE_EPSILON

private fun NormalizedRect.isPlausibleAutomaticLine(): Boolean {
    val width = (right - left).coerceAtLeast(0f)
    val height = (bottom - top).coerceAtLeast(0f)
    if (width <= 0f || height <= 0f) return false
    val regularLine = width >= MIN_AUTOMATIC_LINE_HEIGHT * MIN_AUTOMATIC_LINE_ASPECT_RATIO &&
        height >= MIN_AUTOMATIC_LINE_HEIGHT &&
        width < MAX_AUTOMATIC_LINE_WIDTH &&
        height <= MAX_AUTOMATIC_LINE_HEIGHT &&
        width >= height * MIN_AUTOMATIC_LINE_ASPECT_RATIO
    // Long timer/counter strings can be rendered with a smaller glyph height than a short
    // numeric line. Keep the scene-sized and vertical guards, but admit a clearly thin,
    // horizontally extended single line instead of turning a valid timer into unavailable.
    val longThinLine = width >= MIN_LONG_THIN_LINE_WIDTH &&
        height >= MIN_LONG_THIN_LINE_HEIGHT &&
        width <= MAX_FRAME_LINE_WIDTH &&
        height <= MAX_AUTOMATIC_LINE_HEIGHT &&
        width >= height * MIN_LONG_THIN_LINE_ASPECT_RATIO
    return regularLine || longThinLine
}

private fun intersectionOverUnion(left: NormalizedRect, right: NormalizedRect): Float {
    val intersectionWidth = (min(left.right, right.right) - max(left.left, right.left)).coerceAtLeast(0f)
    val intersectionHeight = (min(left.bottom, right.bottom) - max(left.top, right.top)).coerceAtLeast(0f)
    val intersection = intersectionWidth * intersectionHeight
    val leftArea = (left.right - left.left) * (left.bottom - left.top)
    val rightArea = (right.right - right.left) * (right.bottom - right.top)
    val union = leftArea + rightArea - intersection
    return if (union <= 0f) 0f else intersection / union
}

private fun nextTrackId(current: Long): Long = if (current == Long.MAX_VALUE) 1 else current + 1

private const val MIN_ANCHOR_IOU = 0.20f
private const val MIN_ANCHOR_DISTANCE = 0.06f
private const val MIN_AUTOMATIC_LINE_HEIGHT = 0.08f
private const val MIN_AUTOMATIC_LINE_ASPECT_RATIO = 1.10f
private const val MIN_AUTOMATIC_LINE_SCORE = 0.80f
private const val MAX_AUTOMATIC_LINE_HEIGHT = 0.65f
private const val MAX_AUTOMATIC_LINE_WIDTH = 0.96f
private const val MIN_LONG_THIN_LINE_WIDTH = 0.35f
private const val MIN_LONG_THIN_LINE_HEIGHT = 0.04f
private const val MIN_LONG_THIN_LINE_ASPECT_RATIO = 3.0f
private const val MAX_FRAME_LINE_WIDTH = 1.0f
private const val FRAME_EDGE_EPSILON = 1e-6f

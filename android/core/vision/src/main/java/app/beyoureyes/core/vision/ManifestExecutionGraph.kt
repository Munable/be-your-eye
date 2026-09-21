package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import java.util.PriorityQueue

data class TensorEndpoint(
    val artifactRole: String,
    val tensorName: String,
) {
    init {
        require(artifactRole.isNotBlank())
        require(tensorName.isNotBlank())
    }
}

data class ArtifactSessionContext(
    val artifact: ArtifactComponent,
    val artifactFile: java.io.File,
    val inputSpecs: List<InputComponent>,
    val outputSpecs: List<OutputComponent>,
    val numberOfThreads: Int,
)

/** One reusable engine session for one signed artifact role. */
interface ArtifactExecutionSession : AutoCloseable {
    val artifactRole: String

    /** Names are exact model tensor names; no package-specific aliases are accepted. */
    fun run(inputsByTensorName: Map<String, RawTensor>): Map<String, RawTensor>
}

fun interface ManifestArtifactSessionFactory {
    fun create(context: ArtifactSessionContext): ArtifactExecutionSession
}

data class ArtifactSessionRegistration(
    val runtimeKind: RuntimeKind,
    val factory: ManifestArtifactSessionFactory,
)

data class ExternalTensorContext(
    val manifest: ModelPackageManifest,
    val requiredInputs: List<InputComponent>,
    val targetProfile: TargetProfile?,
    /** Target material is immutable and encoded while the runtime is created, before any frame. */
    val frame: CanonicalFrame? = null,
)

/**
 * Supplies only package-declared external tensors such as token IDs or cached reference features.
 * Tokenizers/reference encoders remain finite reusable families outside model package IDs.
 */
fun interface ManifestExternalTensorProvider {
    fun provide(context: ExternalTensorContext): Map<TensorEndpoint, RawTensor>
}

fun interface ManifestExternalTensorProviderFactory {
    fun create(context: RuntimeComponentContext): ManifestExternalTensorProvider
}

object RejectAdditionalExternalTensors : ManifestExternalTensorProvider {
    override fun provide(context: ExternalTensorContext): Map<TensorEndpoint, RawTensor> = emptyMap()
}

/**
 * Validated, deterministic artifact DAG. A compatible package changes only Manifest/artifact
 * bytes: execution order, tensor routing, shapes and types all come from outputs[]/bindings[].
 */
class ManifestExecutionGraph(
    private val manifest: ModelPackageManifest,
    sessions: Collection<ArtifactExecutionSession>,
) : AutoCloseable {
    private val sessionsByRole = sessions.associateUniqueBy(ArtifactExecutionSession::artifactRole)
    private val executableArtifacts = manifest.artifacts.filterNot(ArtifactComponent::isStaticSidecar)
    private val inputsByEndpoint = manifest.inputs.associateBy(InputComponent::endpoint)
    private val outputsByEndpoint = manifest.outputs.associateBy(OutputComponent::endpoint)
    private val outputsByRole = manifest.outputs.associateBy(OutputComponent::role)
    private val bindingByTarget = manifest.bindings.associateBy(TensorBinding::targetEndpoint)
    private val executionOrder: List<String> = compileOrder()
    private var cachedExternalInputs: Map<TensorEndpoint, RawTensor>? = null
    private var cachedOutputs: Map<TensorEndpoint, RawTensor> = emptyMap()
    private var cachedArtifactRoles: Set<String> = emptySet()
    private var closed = false

    init {
        require(manifest.structuralErrors().isEmpty()) { "invalid Manifest execution graph" }
        require(sessionsByRole.keys == executableArtifacts.map(ArtifactComponent::role).toSet()) {
            "artifact session roles must exactly match executable Manifest artifacts"
        }
    }

    val requiredExternalInputs: List<InputComponent> = manifest.inputs
        .filter { it.endpoint !in bindingByTarget }
        .sortedWith(compareBy(InputComponent::artifactRole, InputComponent::tensorIndex))

    val requiredTargetInputs: List<InputComponent> = requiredExternalInputs
        .filter { it.role != InputRole.IMAGE }

    val requiredFrameInputs: List<InputComponent> = requiredExternalInputs
        .filter { it.role == InputRole.IMAGE }

    /**
     * Freezes target tensors and executes every target-only artifact exactly once. A text encoder
     * is therefore not part of the CameraX frame loop; only artifacts that depend on IMAGE remain.
     */
    fun prepareTarget(targetInputs: Map<TensorEndpoint, RawTensor>) {
        check(!closed) { "execution graph is closed" }
        check(cachedExternalInputs == null) { "target tensors are already prepared" }
        val expectedTarget = requiredTargetInputs.map(InputComponent::endpoint).toSet()
        require(targetInputs.keys == expectedTarget) {
            "target tensor endpoints must exactly match the non-image Manifest graph roots"
        }
        targetInputs.forEach { (endpoint, tensor) ->
            validateTensor(tensor, checkNotNull(inputsByEndpoint[endpoint]))
        }

        val external = targetInputs.mapValues { (_, tensor) -> tensor.immutableCopy() }
        val produced = mutableMapOf<TensorEndpoint, RawTensor>()
        val preparedRoles = linkedSetOf<String>()
        executionOrder.forEach { artifactRole ->
            val inputSpecs = artifactInputs(artifactRole)
            val resolved = resolveInputs(inputSpecs, external, produced) ?: return@forEach
            // IMAGE is never available during target preparation. This explicit check makes a
            // malformed provider incapable of smuggling frame bytes into the immutable cache.
            require(inputSpecs.none { it.role == InputRole.IMAGE })
            produced += execute(artifactRole, resolved)
            preparedRoles += artifactRole
        }
        cachedExternalInputs = external
        cachedOutputs = produced.mapValues { (_, tensor) -> tensor.immutableCopy() }
        cachedArtifactRoles = preparedRoles.toSet()
    }

    fun runFrame(frameInputs: Map<TensorEndpoint, RawTensor>): RawTensorOutput {
        check(!closed) { "execution graph is closed" }
        val targetInputs = checkNotNull(cachedExternalInputs) { "target tensors are not prepared" }
        val expectedFrame = requiredFrameInputs.map(InputComponent::endpoint).toSet()
        require(frameInputs.keys == expectedFrame) {
            "frame tensor endpoints must exactly match IMAGE graph roots"
        }
        frameInputs.forEach { (endpoint, tensor) ->
            validateTensor(tensor, checkNotNull(inputsByEndpoint[endpoint]))
        }

        val externalInputs = targetInputs + frameInputs
        val produced = cachedOutputs.toMutableMap()
        executionOrder.forEach { artifactRole ->
            if (artifactRole in cachedArtifactRoles) return@forEach
            val inputSpecs = artifactInputs(artifactRole)
            val resolved = checkNotNull(resolveInputs(inputSpecs, externalInputs, produced)) {
                "input tensor is unavailable for artifact: $artifactRole"
            }
            produced += execute(artifactRole, resolved)
        }

        return RawTensorOutput(
            manifest.finalOutputs()
                .sortedWith(compareBy(OutputComponent::artifactRole, OutputComponent::tensorIndex))
                .map { component ->
                checkNotNull(produced[component.endpoint])
            },
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        executionOrder.asReversed().forEach { role ->
            runCatching { checkNotNull(sessionsByRole[role]).close() }
        }
    }

    private fun compileOrder(): List<String> {
        val executableRoles = executableArtifacts.mapTo(linkedSetOf(), ArtifactComponent::role)
        val edges = executableArtifacts.associate { it.role to mutableSetOf<String>() }
        val indegree = executableArtifacts.associate { it.role to 0 }.toMutableMap()
        manifest.bindings.forEach { binding ->
            require(binding.sourceArtifactRole in executableRoles &&
                binding.targetArtifactRole in executableRoles
            ) { "tensor bindings may only reference executable artifacts" }
            val targets = checkNotNull(edges[binding.sourceArtifactRole])
            if (targets.add(binding.targetArtifactRole)) {
                indegree[binding.targetArtifactRole] =
                    checkNotNull(indegree[binding.targetArtifactRole]) + 1
            }
        }
        val queue = PriorityQueue<String>().apply {
            addAll(indegree.filterValues { it == 0 }.keys)
        }
        val order = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val role = queue.remove()
            order += role
            checkNotNull(edges[role]).sorted().forEach { target ->
                val next = checkNotNull(indegree[target]) - 1
                indegree[target] = next
                if (next == 0) queue += target
            }
        }
        require(order.size == executableArtifacts.size) { "artifact graph must be acyclic" }
        return order
    }

    private fun artifactInputs(artifactRole: String): List<InputComponent> = manifest.inputs
        .filter { it.artifactRole == artifactRole }
        .sortedBy(InputComponent::tensorIndex)

    private fun resolveInputs(
        inputSpecs: List<InputComponent>,
        externalInputs: Map<TensorEndpoint, RawTensor>,
        produced: Map<TensorEndpoint, RawTensor>,
    ): Map<String, RawTensor>? {
        val result = linkedMapOf<String, RawTensor>()
        inputSpecs.forEach { spec ->
            val endpoint = spec.endpoint
            val tensor = bindingByTarget[endpoint]?.let { binding ->
                produced[binding.sourceEndpoint]
            } ?: externalInputs[endpoint]
            if (tensor == null) return null
            result[spec.tensorName] = tensor
        }
        return result
    }

    private fun execute(
        artifactRole: String,
        inputsByTensorName: Map<String, RawTensor>,
    ): Map<TensorEndpoint, RawTensor> {
        val declaredOutputs = manifest.outputs
            .filter { it.artifactRole == artifactRole }
            .sortedBy(OutputComponent::tensorIndex)
        val actualOutputs = checkNotNull(sessionsByRole[artifactRole]).run(inputsByTensorName)
        require(actualOutputs.keys == declaredOutputs.map(OutputComponent::tensorName).toSet()) {
            "artifact outputs must exactly match outputs[]"
        }
        return declaredOutputs.associate { spec ->
            val tensor = checkNotNull(actualOutputs[spec.tensorName])
            validateTensor(tensor, spec)
            spec.endpoint to tensor
        }
    }

    private fun validateTensor(tensor: RawTensor, spec: InputComponent) {
        require(tensor.name == spec.tensorName)
        require(tensor.shape == spec.runtimeShape)
        require(tensor.elementType == spec.dataType.toTensorElementType())
        require(tensor.bytes.size == spec.runtimeShape.checkedElementCount() * tensor.elementType.storageByteCount)
    }

    private fun validateTensor(tensor: RawTensor, spec: OutputComponent) {
        require(tensor.name == spec.tensorName)
        require(tensor.shape == spec.runtimeShape)
        require(tensor.elementType == spec.dataType.toTensorElementType())
        require(tensor.bytes.size == spec.runtimeShape.checkedElementCount() * tensor.elementType.storageByteCount)
    }

    private fun <T> Collection<T>.associateUniqueBy(key: (T) -> String): Map<String, T> {
        val mapped = associateBy(key)
        require(mapped.size == size) { "duplicate artifact session role" }
        return mapped
    }
}

/** Composite and multi-input production path with the same failure semantics as replay/CameraX. */
class GraphObservationPipeline(
    private val manifest: ModelPackageManifest,
    private val targetProfile: TargetProfile?,
    private val normalizer: FrameNormalizer,
    private val qualityGate: QualityGate,
    private val primaryImagePreprocessor: InputPreprocessor,
    private val graph: ManifestExecutionGraph,
    private val adapter: OutputAdapter,
    private val clock: NanoClock = SystemNanoClock,
) : VisionPipeline {
    override fun process(frame: SourceFrame): PipelineResult {
        var normalizeNanos = 0L
        var qualityNanos = 0L
        var preprocessNanos = 0L
        var inferenceNanos = 0L
        var adapterNanos = 0L

        fun result(
            observation: Observation,
            adapterCompleted: Boolean = false,
        ) = PipelineResult(
            sourceSequence = frame.sourceSequence,
            monotonicTimeMillis = frame.monotonicTimeMillis,
            capturedAtEpochMillis = frame.capturedAtEpochMillis,
            observation = observation,
            timings = PipelineTimings(
                normalizeNanos,
                qualityNanos,
                preprocessNanos,
                inferenceNanos,
                adapterNanos,
            ),
            adapterCompleted = adapterCompleted,
        )

        val canonical = try {
            measured({ normalizeNanos = it }) { normalizer.normalize(frame) }
        } catch (_: Exception) {
            return result(unavailable(frame, UnavailableReason.INFERENCE_ERROR, "normalize_failed"))
        }
        val quality = try {
            measured({ qualityNanos = it }) { qualityGate.evaluate(canonical) }
        } catch (_: Exception) {
            return result(unavailable(frame, UnavailableReason.INFERENCE_ERROR, "quality_gate_failed"))
        }
        if (quality is QualityGateResult.Rejected) {
            return result(unavailable(frame, quality.reason, quality.diagnosticCode))
        }

        val primaryInputSpec = manifest.inputs.single {
            it.role == InputRole.IMAGE && it.artifactRole == "primary"
        }
        val prepared = try {
            measured({ preprocessNanos = it }) { primaryImagePreprocessor.prepare(canonical) }
        } catch (_: Exception) {
            return result(unavailable(frame, UnavailableReason.INFERENCE_ERROR, "preprocess_failed"))
        }
        val output = try {
            measured({ inferenceNanos = it }) {
                val frameTensors = graph.requiredFrameInputs.associate { spec ->
                    require(spec.dataType == primaryInputSpec.dataType)
                    require(spec.runtimeShape == primaryInputSpec.runtimeShape)
                    require(spec.layout == primaryInputSpec.layout)
                    require(spec.colorSpace == primaryInputSpec.colorSpace)
                    require(spec.quantization == primaryInputSpec.quantization)
                    spec.endpoint to RawTensor(
                        name = spec.tensorName,
                        bytes = prepared.bytes,
                        shape = prepared.shape,
                        elementType = prepared.elementType,
                    )
                }
                graph.runFrame(frameTensors)
            }
        } catch (_: Exception) {
            return result(unavailable(frame, UnavailableReason.INFERENCE_ERROR, "backend_failed"))
        }
        val observation = try {
            measured({ adapterNanos = it }) {
                adapter.toObservation(output, prepared.transform, canonical)
            }
        } catch (_: Exception) {
            return result(unavailable(frame, UnavailableReason.INCOMPATIBLE_OUTPUT, "adapter_failed"))
        }
        if (observation.sourceSequence != frame.sourceSequence) {
            return result(
                unavailable(frame, UnavailableReason.INCOMPATIBLE_OUTPUT, "source_sequence_mismatch"),
            )
        }
        return result(observation, adapterCompleted = true)
    }

    private inline fun <T> measured(setNanos: (Long) -> Unit, block: () -> T): T {
        val started = clock.nanoTime()
        return try {
            block()
        } finally {
            setNanos((clock.nanoTime() - started).coerceAtLeast(0L))
        }
    }

    private fun unavailable(
        frame: SourceFrame,
        reason: UnavailableReason,
        code: String,
    ) = Observation.Unavailable(reason, code, frame.sourceSequence)
}

internal val InputComponent.endpoint: TensorEndpoint
    get() = TensorEndpoint(artifactRole, tensorName)

internal val OutputComponent.endpoint: TensorEndpoint
    get() = TensorEndpoint(artifactRole, tensorName)

internal val TensorBinding.sourceEndpoint: TensorEndpoint
    get() = TensorEndpoint(sourceArtifactRole, sourceTensorName)

internal val TensorBinding.targetEndpoint: TensorEndpoint
    get() = TensorEndpoint(targetArtifactRole, targetTensorName)

internal val TensorElementType.storageByteCount: Int
    get() = when (this) {
        TensorElementType.UINT8, TensorElementType.INT8 -> Byte.SIZE_BYTES
        TensorElementType.INT32 -> Int.SIZE_BYTES
        TensorElementType.INT64 -> Long.SIZE_BYTES
        TensorElementType.FLOAT16 -> Short.SIZE_BYTES
        TensorElementType.FLOAT32 -> Float.SIZE_BYTES
    }

private fun RawTensor.immutableCopy() = copy(bytes = bytes.copyOf(), shape = shape.toList())

internal fun TensorDataType.toTensorElementType(): TensorElementType = when (this) {
    TensorDataType.UINT8 -> TensorElementType.UINT8
    TensorDataType.INT8 -> TensorElementType.INT8
    TensorDataType.INT32 -> TensorElementType.INT32
    TensorDataType.INT64 -> TensorElementType.INT64
    TensorDataType.FLOAT16 -> TensorElementType.FLOAT16
    TensorDataType.FLOAT32 -> TensorElementType.FLOAT32
}

internal fun List<Int>.checkedElementCount(): Int = fold(1, Math::multiplyExact)

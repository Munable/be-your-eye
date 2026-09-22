package app.beyoureyes.core.domain

import java.math.BigDecimal

const val MAX_LOCAL_MONITORS = 20
const val MIN_REFERENCE_IMAGES = 3
const val MAX_REFERENCE_IMAGES = 20

enum class MonitorKind(val wireValue: String) {
    REFERENCE("reference_images"),
    READING("structured_reading"),
    OBJECT_DETECTION("object_detection"),
}

sealed interface MonitorTarget {
    val kind: MonitorKind

    data class ReferenceImages(val imageCount: Int) : MonitorTarget {
        override val kind = MonitorKind.REFERENCE

        init {
            require(imageCount in MIN_REFERENCE_IMAGES..MAX_REFERENCE_IMAGES)
        }
    }

    data class ObjectClass(
        val targetId: String,
        val labelZhCn: String,
        val labelEn: String,
        val labels: Map<String, String> = emptyMap(),
    ) : MonitorTarget {
        override val kind = MonitorKind.OBJECT_DETECTION

        init {
            require(targetId.matches(Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")))
            require(labelZhCn.isNotBlank() && labelZhCn.length <= 40)
            require(labelEn.isNotBlank() && labelEn.length <= 40)
            require(labels.keys.all { it in SUPPORTED_LOCALE_TAGS })
            require(labels.values.all { it.isNotBlank() && it.length <= 40 })
        }

        fun localizedLabel(languageTag: String): String {
            val locale = supportedLocaleForTag(languageTag)
            return labels[locale]
                ?: if (locale == "zh-Hans" || locale == "zh-Hant") labelZhCn else labelEn
        }
    }

    data class NumericReading(
        val config: ReadingTargetConfig = ReadingTargetConfig(),
    ) : MonitorTarget {
        override val kind = MonitorKind.READING

        val manualRoi: NormalizedRect? get() = config.manualRoi
        val confirmedFormat: ConfirmedReadingFormat? get() = config.confirmedFormat
    }
}

data class ReadingTargetConfig(
    val manualRoi: NormalizedRect? = null,
    val confirmedFormat: ConfirmedReadingFormat? = null,
)

sealed interface MonitorRule {
    data class TargetPresence(
        val kind: PresenceRuleKind = PresenceRuleKind.APPEARS,
        val durationSeconds: Int = DEFAULT_TRIGGER_DURATION_SECONDS,
    ) : MonitorRule {
        init {
            require(durationSeconds in ALLOWED_TRIGGER_DURATIONS_SECONDS)
        }
    }

    sealed interface ReadingThreshold : MonitorRule {
        val configured: Boolean
        val durationSeconds: Int

        data class Single(
            val comparison: ReadingComparison = ReadingComparison.GT,
            val thresholdDecimal: String,
            override val configured: Boolean,
            override val durationSeconds: Int = DEFAULT_TRIGGER_DURATION_SECONDS,
        ) : ReadingThreshold {
            init {
                requireCanonicalDecimal(thresholdDecimal)
                require(durationSeconds in ALLOWED_TRIGGER_DURATIONS_SECONDS)
            }
        }

        data class Outside(
            val lowerThresholdDecimal: String,
            val upperThresholdDecimal: String,
            override val configured: Boolean,
            override val durationSeconds: Int = DEFAULT_TRIGGER_DURATION_SECONDS,
        ) : ReadingThreshold {
            init {
                require(configured) { "outside reading rules must be configured" }
                requireCanonicalDecimal(lowerThresholdDecimal)
                requireCanonicalDecimal(upperThresholdDecimal)
                require(durationSeconds in ALLOWED_TRIGGER_DURATIONS_SECONDS)
                require(BigDecimal(lowerThresholdDecimal) < BigDecimal(upperThresholdDecimal)) {
                    "outside thresholds require lower < upper"
                }
            }
        }
    }
}

enum class PresenceRuleKind(val wireValue: String) {
    APPEARS("appears"),
    REMAINS("remains"),
    DISAPPEARS("disappears"),
}

enum class ReadingComparison(val wireValue: String) {
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
}

data class Monitor(
    val id: String,
    val revision: Long,
    val name: String,
    val target: MonitorTarget,
    val rule: MonitorRule,
    val createdAtEpochMillis: Long,
) {
    val kind: MonitorKind get() = target.kind

    init {
        require(id.isNotBlank())
        require(revision >= 1)
        require(name.isNotBlank() && name.length <= 100)
        require(createdAtEpochMillis >= 0)
        require(
            target is MonitorTarget.ReferenceImages && rule is MonitorRule.TargetPresence ||
                target is MonitorTarget.ObjectClass && rule is MonitorRule.TargetPresence ||
                target is MonitorTarget.NumericReading && rule is MonitorRule.ReadingThreshold &&
                rule.configured == (target.confirmedFormat != null),
        ) { "monitor target and rule must match" }
    }
}

data class MonitorEvent(
    val id: String,
    val monitorId: String,
    /** Notification/fallback copy only. Product UI should render [fact]. */
    val text: String,
    val occurredAtEpochMillis: Long,
    val remoteMonitorName: String? = null,
    val isRemote: Boolean = false,
    /** App-private trigger photo for a local reference appearance; never synchronized. */
    val localTriggerSnapshotUri: String? = null,
    val fact: MonitorEventFact,
) {
    init {
        require(id.isNotBlank() && monitorId.isNotBlank() && text.isNotBlank())
        require(occurredAtEpochMillis >= 0)
        require(isRemote || remoteMonitorName == null)
        require(!isRemote || localTriggerSnapshotUri == null)
    }
}

/** Product-facing event facts decoded identically for local and synchronized events. */
sealed interface MonitorEventFact {
    data class ObjectEpisode(
        val targetId: String,
        val condition: MonitorObjectEventCondition,
        val durationMillis: Long,
        val count: Int,
    ) : MonitorEventFact {
        init {
            require(targetId.isNotBlank())
            require(durationMillis >= 0)
            require(count >= 0)
        }
    }

    data class VisualConditionMet(
        val targetId: String,
        val condition: MonitorVisualEventCondition,
        val durationMillis: Long,
    ) : MonitorEventFact {
        init {
            require(targetId.isNotBlank())
            require(durationMillis > 0)
        }
    }

    data class ReadingThresholdCrossed(
        val displayText: String,
        val valueDecimal: String,
        val format: ConfirmedReadingFormat,
        val unit: String?,
        val operator: MonitorReadingOperator,
        val thresholdDecimal: String? = null,
        val lowerThresholdDecimal: String? = null,
        val upperThresholdDecimal: String? = null,
    ) : MonitorEventFact {
        init {
            require(
                displayText.isNotBlank() &&
                    displayText.length <= StructuredReadingValue.MAX_READING_TEXT_LENGTH,
            )
            requireEventDecimal(valueDecimal)
            require(unit == format.unit)
            if (operator == MonitorReadingOperator.OUTSIDE) {
                require(thresholdDecimal == null)
                requireNotNull(lowerThresholdDecimal).also(::requireEventDecimal)
                requireNotNull(upperThresholdDecimal).also(::requireEventDecimal)
                require(BigDecimal(lowerThresholdDecimal) < BigDecimal(upperThresholdDecimal))
            } else {
                requireNotNull(thresholdDecimal).also(::requireEventDecimal)
                require(lowerThresholdDecimal == null && upperThresholdDecimal == null)
            }
        }
    }

    data class StateTransition(
        val fromState: String,
        val toState: String,
    ) : MonitorEventFact {
        init {
            require(fromState.isNotBlank() && toState.isNotBlank())
        }
    }

    /** Explicit projection failure; raw payload data is never copied into the product model. */
    data class Unknown(val reason: MonitorEventUnknownReason) : MonitorEventFact
}

enum class MonitorObjectEventCondition {
    APPEARED,
    DISAPPEARED,
    COUNT_MATCHED,
}

enum class MonitorVisualEventCondition {
    PRESENT_FOR_DURATION,
    ABSENT_FOR_DURATION,
}

enum class MonitorReadingOperator {
    GT,
    GTE,
    LT,
    LTE,
    EQ,
    OUTSIDE,
}

enum class MonitorEventUnknownReason {
    MALFORMED_PAYLOAD,
    UNSUPPORTED_TYPE,
    INVALID_FIELDS,
}

data class RemoteMonitor(
    val id: String,
    val revision: Long,
    val name: String,
    val kind: MonitorKind,
    val monitoringDeviceId: String,
) {
    init {
        require(id.isNotBlank() && revision >= 1 && name.isNotBlank() && monitoringDeviceId.isNotBlank())
    }
}

enum class ReadingStatus(val wireValue: String) {
    CANDIDATE("candidate"),
    STABLE("stable"),
    UNAVAILABLE("unavailable"),
}

data class LatestReading(
    val monitorId: String,
    val monitorRevision: Long,
    val status: ReadingStatus,
    val valueDecimal: String?,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(monitorId.isNotBlank() && monitorRevision >= 1 && updatedAtEpochMillis >= 0)
        require((status == ReadingStatus.UNAVAILABLE) == (valueDecimal == null))
        valueDecimal?.let(::requireCanonicalDecimal)
    }
}

data class ReferenceMaterial(
    val sourceUri: String,
    val thumbnailUri: String? = null,
    val exactSha256: String,
    val differenceHash: Long,
    val meanRed: Int,
    val meanGreen: Int,
    val meanBlue: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(sourceUri.isNotBlank())
        require(thumbnailUri == null || thumbnailUri.isNotBlank())
        require(exactSha256.matches(Regex("[0-9a-f]{64}")))
        require(meanRed in 0..255 && meanGreen in 0..255 && meanBlue in 0..255)
        require(width > 0 && height > 0)
    }
}

object ReferenceMaterialDeduplicator {
    fun isDuplicate(candidate: ReferenceMaterial, accepted: Collection<ReferenceMaterial>): Boolean =
        accepted.any { existing -> existing.exactSha256 == candidate.exactSha256 }

    fun requireDistinct(materials: List<ReferenceMaterial>) {
        val accepted = mutableListOf<ReferenceMaterial>()
        materials.forEach { material ->
            require(!isDuplicate(material, accepted)) { "reference images must be distinct" }
            accepted += material
        }
    }

    fun differenceHash(grayscale: IntArray): Long {
        require(grayscale.size == 72)
        var value = 0L
        repeat(8) { row ->
            repeat(8) { column ->
                if (grayscale[row * 9 + column] > grayscale[row * 9 + column + 1]) {
                    value = value or (1L shl (row * 8 + column))
                }
            }
        }
        return value
    }
}

enum class MaterialSufficiencyStage { NEED_MORE, READY, SUFFICIENT, MORE_STABLE }

data class MaterialSufficiency(
    val count: Int,
    val stage: MaterialSufficiencyStage,
    val progress: Float,
    val canContinue: Boolean,
)

fun materialSufficiency(count: Int): MaterialSufficiency {
    require(count in 0..MAX_REFERENCE_IMAGES)
    val stage = when {
        count < MIN_REFERENCE_IMAGES -> MaterialSufficiencyStage.NEED_MORE
        count <= 5 -> MaterialSufficiencyStage.READY
        count <= 9 -> MaterialSufficiencyStage.SUFFICIENT
        else -> MaterialSufficiencyStage.MORE_STABLE
    }
    return MaterialSufficiency(
        count = count,
        stage = stage,
        progress = count / MAX_REFERENCE_IMAGES.toFloat(),
        canContinue = count >= MIN_REFERENCE_IMAGES,
    )
}

fun normalizeDecimal(raw: String): String? {
    val value = raw.trim()
    if (value.length !in 1..64 || !DECIMAL_INPUT.matches(value)) return null
    return runCatching {
        BigDecimal(value).let { if (it.signum() == 0) "0" else it.stripTrailingZeros().toPlainString() }
    }.getOrNull()?.takeIf { DECIMAL.matches(it) && it.length <= 64 }
}

fun uniqueMonitorName(baseName: String, existingNames: Collection<String>): String {
    val base = baseName.trim()
    require(base.isNotEmpty() && base.length <= 100)
    if (base !in existingNames) return base
    return generateSequence(2, Int::inc)
        .map { "$base $it" }
        .first { it !in existingNames }
}

private fun requireCanonicalDecimal(value: String) {
    require(value.length in 1..64 && DECIMAL.matches(value))
    require(!(BigDecimal(value).signum() == 0 && value.startsWith('-')))
}

private fun requireEventDecimal(value: String) {
    require(value.length in 1..64 && DECIMAL.matches(value))
}

const val DEFAULT_TRIGGER_DURATION_SECONDS = 1
val ALLOWED_TRIGGER_DURATIONS_SECONDS = 1..60

private val DECIMAL = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$")
private val DECIMAL_INPUT = Regex("^-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)$")

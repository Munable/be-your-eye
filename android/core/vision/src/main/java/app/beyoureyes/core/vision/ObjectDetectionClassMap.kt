package app.beyoureyes.core.vision

import java.text.Normalizer
import java.util.Locale

/** Resolves only the finite class table carried by an already verified signed Manifest. */
object ObjectDetectionClassMap {
    val provider: ClassMapProvider = ClassMapProvider { spec ->
        runCatching {
            require(spec.identity.isNotBlank())
            require(spec.sha256.matches(Regex("^[0-9a-f]{64}$")))
            require(spec.classIdBase == 0)
            require(spec.targets.isNotEmpty())
            require(spec.targets.map(ClassMapTarget::rawClassId).distinct().size == spec.targets.size)
            require(spec.targets.map(ClassMapTarget::targetId).distinct().size == spec.targets.size)

            val aliases = mutableMapOf<String, String>()
            spec.targets.forEach { target ->
                (target.aliases + target.labelZhCn + target.labelEn + target.labels.values).forEach { alias ->
                    val normalized = Normalizer.normalize(alias, Normalizer.Form.NFKC)
                        .trim()
                        .replace(Regex("\\s+"), " ")
                        .lowercase(Locale.ROOT)
                    require(normalized.isNotEmpty())
                    val previous = aliases.put(normalized, target.targetId)
                    require(previous == null || previous == target.targetId)
                }
            }

            val targets = spec.targets.associateBy(ClassMapTarget::rawClassId)
            ResolvedClassMap(
                identity = spec.identity,
                sha256 = spec.sha256,
                labelsByRawClassId = targets.mapValues { (_, target) -> target.targetId },
                targetsByRawClassId = targets,
            )
        }.getOrNull()
    }
}

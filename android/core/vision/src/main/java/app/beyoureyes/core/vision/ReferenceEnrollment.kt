package app.beyoureyes.core.vision

/** Canonical app-private reference bytes supplied to the signed localized reference graph. */
data class ReferenceImageAsset(
    val localAssetId: String,
    val contentSha256: String,
    val width: Int,
    val height: Int,
    val rgb888: ByteArray,
) {
    init {
        require(localAssetId.isNotBlank())
        require(contentSha256.matches(SHA_256))
        require(width in 1..MAX_REFERENCE_EDGE && height in 1..MAX_REFERENCE_EDGE)
        val pixelCount = width.toLong() * height
        require(pixelCount <= MAX_REFERENCE_PIXELS)
        require(rgb888.size.toLong() == pixelCount * RGB_CHANNELS)
    }

    private companion object {
        const val RGB_CHANNELS = 3
        const val MAX_REFERENCE_EDGE = 1_024
        const val MAX_REFERENCE_PIXELS = 1_048_576L
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

fun interface ReferenceImageProvider {
    fun load(metadata: ReferenceImageMetadata): ReferenceImageAsset?
}

data class ReferenceEnrollmentResult(
    val accepted: List<ReferenceImageAsset>,
) {
    init {
        require(accepted.size <= TargetProfile.MAX_REFERENCE_IMAGES)
    }
}

/** Import already decoded and de-duplicated these materials; runtime must use every one. */
class ReferenceEnrollmentProcessor {
    fun resolve(
        target: TargetProfile.ReferenceImages,
        provider: ReferenceImageProvider,
    ): ReferenceEnrollmentResult = ReferenceEnrollmentResult(
        target.images.map { metadata ->
            val asset = requireNotNull(provider.load(metadata)) {
                "reference asset is unavailable: ${metadata.referenceId}"
            }
            require(asset.localAssetId == metadata.localAssetId) {
                "reference asset ID mismatch: ${metadata.referenceId}"
            }
            require(asset.contentSha256 == metadata.contentSha256) {
                "reference asset hash mismatch: ${metadata.referenceId}"
            }
            require(asset.width == metadata.width && asset.height == metadata.height) {
                "reference asset dimensions mismatch: ${metadata.referenceId}"
            }
            asset
        },
    )
}

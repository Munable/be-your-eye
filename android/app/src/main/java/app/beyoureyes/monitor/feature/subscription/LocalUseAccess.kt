package app.beyoureyes.monitor.feature.subscription

import app.beyoureyes.core.vision.BuildChannel

/** Local use is independent of a cloud account in the Community distribution. */
internal fun localUseAccessDecision(
    buildChannel: BuildChannel,
    cloudAccess: ProductAccessState,
): ProductAccessDecision = when (buildChannel) {
    BuildChannel.COMMUNITY -> ProductAccessDecision.GRANTED
    else -> productAccessDecision(cloudAccess)
}

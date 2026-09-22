package app.beyoureyes.monitor.design

import androidx.compose.runtime.Composable
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.monitor.feature.about.currentAppLanguageTag
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun MonitorTarget.ObjectClass.localizedLabel(): String =
    localizedLabel(currentAppLanguageTag(LocalContext.current))

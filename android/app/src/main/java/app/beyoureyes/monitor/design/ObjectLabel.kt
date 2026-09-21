package app.beyoureyes.monitor.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import app.beyoureyes.core.domain.MonitorTarget

@Composable
internal fun MonitorTarget.ObjectClass.localizedLabel(): String =
    if (LocalConfiguration.current.locales[0].language == "zh") labelZhCn else labelEn

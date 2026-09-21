package app.beyoureyes.monitor.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R

internal enum class MainTab(@param:androidx.annotation.StringRes val labelRes: Int) {
    MONITORS(R.string.tab_monitors),
    HISTORY(R.string.tab_history),
    ACCOUNT(R.string.tab_account),
}

@Composable
internal fun MainTabScaffold(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = ProductColors.Background,
        bottomBar = {
            NavigationBar(
                containerColor = ProductColors.Background,
                tonalElevation = 0.dp,
            ) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    MainTab.MONITORS -> Icons.Outlined.MonitorHeart
                                    MainTab.HISTORY -> Icons.Outlined.History
                                    MainTab.ACCOUNT -> Icons.Outlined.PersonOutline
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(if (tab == MainTab.ACCOUNT && app.beyoureyes.monitor.BuildConfig.COMMUNITY_BUILD) R.string.community_tab else tab.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ProductColors.Cyan,
                            selectedTextColor = ProductColors.TextPrimary,
                            indicatorColor = ProductColors.CyanSoft,
                            unselectedIconColor = ProductColors.TextMuted,
                            unselectedTextColor = ProductColors.TextMuted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) { content() }
    }
}

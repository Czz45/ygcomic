package com.yg.comic.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem

/**
 * 底部三 tab：改用 miuix 新版 FloatingNavigationBar 悬浮胶囊栏，三个独立按钮 + 选中高亮。
 * 放在 Miuix Scaffold 的 floatingToolbar 槽里——槽位已避开手势小白条，
 * 这里 defaultWindowInsetsPadding=false 不再叠加系统栏内缩，避免把底栏压太低。
 */
@Composable
fun PillTabBar(
    tabs: List<Tab>,
    currentRoute: String,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingNavigationBar(
        // 不再额外加底部 padding——组件自身固定抬高已够避开小白条，
        // 之前 padding(bottom=8.dp) 把整条顶高了，用户反馈偏高故去掉。
        modifier = modifier,
        // 恢复胶囊默认底色+阴影（v209 曾去掉，用户要求加回边框）。
        // 不指定 color/shadowElevation 即用组件默认（surfaceContainer + 默认阴影）。
        showDivider = false,
        // 固定抬高避开小白条：false 走组件固定抬高，不用随手势条显隐变化的 navigationBars。
        defaultWindowInsetsPadding = false,
    ) {
        tabs.forEach { tab ->
            FloatingNavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onSelect(tab) },
                icon = tab.icon,
                label = tab.label,
            )
        }
    }
}

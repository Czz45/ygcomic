package com.yg.comic.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowInsetsControllerCompat
import com.yg.comic.data.SettingsStore
import com.yg.comic.model.ThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 主题：跟随系统 / 手动浅色深色。
 * 采用 Miuix 原生配色（ColorSchemeMode.System/Light/Dark，keyColor=null，Spec2021，TonalSpot），
 * 得到模板同款蓝白观感（primary=0xFF3482FF，background=White，surface=0xFFF7F7F7）。
 * 状态栏/手势条图标随明暗翻转。
 */
@Composable
fun YGTheme(store: SettingsStore, content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mode by store.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val controller = remember(mode, dark) {
        ThemeController(
            colorSchemeMode = when (mode) {
                ThemeMode.SYSTEM -> ColorSchemeMode.System
                ThemeMode.LIGHT -> ColorSchemeMode.Light
                ThemeMode.DARK -> ColorSchemeMode.Dark
            },
            keyColor = null,
            colorSpec = ThemeColorSpec.Spec2021,
            paletteStyle = ThemePaletteStyle.TonalSpot,
            isDark = dark,
        )
    }

    MiuixTheme(
        controller = controller,
        content = {
            LaunchedEffect(dark) {
                val window = (context as? Activity)?.window ?: return@LaunchedEffect
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CompositionLocalProvider(
                LocalContentColor provides MiuixTheme.colorScheme.onBackground,
                content = content,
            )
        },
    )
}

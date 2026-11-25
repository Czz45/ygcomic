package com.yg.comic.ui.placeholder

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yg.comic.model.MediaType
import top.yukonga.miuix.kmp.basic.*

@Composable
fun PlaceholderScreen(type: String, onBack: () -> Unit) {
    val name = runCatching { MediaType.valueOf(type).label }.getOrDefault(type)
    Scaffold { _ ->
        Column(Modifier.fillMaxSize()) {
            SmallTopAppBar(
                title = name,
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") } },
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Construction, contentDescription = null, modifier = Modifier.size(56.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(16.dp))
                    Text("$name 功能开发中", fontSize = MiuixTheme.textStyles.title4.fontSize)
                    Spacer(Modifier.height(8.dp))
                    Text("已预留接口，后续扩展不改书架结构", fontSize = MiuixTheme.textStyles.body2.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

package com.yg.comic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

/**
 * 对话框底部操作按钮：浅灰「取消」+ 蓝色「确定」并排等宽。
 * 对齐系统对话框观感（白底圆角卡片 + 两个实心按钮）。
 */
@Composable
fun DialogActions(
    cancelText: String = "取消",
    confirmText: String = "确定",
    confirmEnabled: Boolean = true,
    primaryOnLeft: Boolean = false,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (primaryOnLeft) {
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = confirmEnabled,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(confirmText) }
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(),
            ) { Text(cancelText) }
        } else {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(),
            ) { Text(cancelText) }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = confirmEnabled,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(confirmText) }
        }
    }
}

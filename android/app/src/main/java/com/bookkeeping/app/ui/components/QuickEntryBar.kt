package com.bookkeeping.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingTextSecondary

/**
 * 「新建账单」表单顶部的一句话记账区：写一句人话，交给解析器把下面的字段填好。
 *
 * 这一块是可选的——不填就是原来的手动填表；填了在回车或失焦时把原文回调出去。
 * 提交后**不清空**：解析可能失败，用户要能改几个字再提一次，什么时候清由调用方决定。
 * 空白输入不回调，也不报错。解析不出的字段由调用方通过 [warning] 指出来。
 */
@Composable
fun QuickEntryBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    warning: String? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (QuickEntryRule.canSubmit(value)) {
            keyboard?.hide()
            onSubmit(value)
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = tr("一句话记账"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(12.dp),
            placeholder = {
                Text(
                    text = tr("比如：11月16日中午12点在麦当劳花了36块5，餐饮，微信支付，备注加班餐"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = QingTextSecondary,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = QingSurface,
                unfocusedContainerColor = QingSurface,
                focusedBorderColor = QingPrimary,
                unfocusedBorderColor = QingOutline,
                cursorColor = QingPrimary,
            ),
        )
        Text(
            text = tr("写下消费或收入，自动帮你填好账单"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

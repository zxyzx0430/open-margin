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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingTextSecondary

/**
 * 「新建账单」表单顶部的一句话记账区：写一句人话，交给解析器把下面的字段填好。
 *
 * 这一块是可选的——不填就是原来的手动填表；输入停顿约 500ms 后回调解析，键盘完成也会立即解析。
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
    previewContent: @Composable () -> Unit = {},
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
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.fillMaxWidth(),
            label = { Text(tr("一句话记账")) },
            minLines = 2,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(12.dp),
            placeholder = {
                Text(
                    text = tr("例如：午饭 25 微信\n打车 18 支付宝"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = QingTextSecondary,
                    maxLines = 2,
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
        previewContent()
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

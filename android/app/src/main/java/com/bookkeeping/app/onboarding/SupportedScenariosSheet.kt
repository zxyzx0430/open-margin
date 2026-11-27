package com.bookkeeping.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCardOff
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.QingSheetGrabber
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.QingWarning
import com.bookkeeping.app.ui.theme.qingColors

internal data class ScenarioItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val badge: String? = null,
)

@Composable
internal fun supportedCaptureScenarios(): List<ScenarioItem> = listOf(
    ScenarioItem(
        icon = Icons.Filled.Notifications,
        title = tr("支付通知自动记账"),
        description = tr("自动识别微信支付、支付宝付款/收款/退款通知，秒级入账并匹配 150+ 官方品牌图标与分类记忆（需开启通知使用权）"),
        badge = tr("自动入账"),
    ),
    ScenarioItem(
        icon = Icons.Filled.Visibility,
        title = tr("支付完成页识别"),
        description = tr("在微信、支付宝「支付成功」「付款结果」页面实时抓取金额与商户，补齐无通知渠道的消费（需开启页面识别）"),
        badge = tr("自动入账"),
    ),
    ScenarioItem(
        icon = Icons.AutoMirrored.Filled.ReceiptLong,
        title = tr("转账与红包入账"),
        description = tr("识别微信收发红包、好友转账、群收款及支付宝转账记录，开启页面识别自动接管，也支持无障碍兜底"),
        badge = tr("自动入账"),
    ),
    ScenarioItem(
        icon = Icons.Filled.Sms,
        title = tr("银行动账短信"),
        description = tr("支持工、建、农、中、招、交、平安、浦发、兴业、中信、广发等主流银行消费/入账/还款短信（按需开启短信权限）"),
        badge = tr("可选开启"),
    ),
    ScenarioItem(
        icon = Icons.Filled.AddAPhoto,
        title = tr("截图、离线语音与历史账单导入"),
        description = tr("双击「＋」批量识别微信/支付宝支付截图并保留原图；长按「＋」离线语音一句话记账；支持导入官方 CSV/XLSX 并自动去重"),
        badge = tr("手动触发"),
    ),
)

@Composable
internal fun knownBoundariesAndUnsupported(): List<ScenarioItem> = listOf(
    ScenarioItem(
        icon = Icons.Filled.Lock,
        title = tr("密码与验证页自动跳过"),
        description = tr("遇到支付密码键盘、指纹/人脸验证、短信验证码等敏感页面时主动停止识别，绝不采集"),
        badge = tr("自动跳过"),
    ),
    ScenarioItem(
        icon = Icons.Filled.EventRepeat,
        title = tr("无通知的免密自动扣款"),
        description = tr("部分免密代扣若未弹系统通知也未打开支付页，系统不会通知清衡；建议在「订阅」登记周期提醒或定期导入账单补齐"),
        badge = tr("暂不支持"),
    ),
    ScenarioItem(
        icon = Icons.Filled.CreditCardOff,
        title = tr("现金消费与无短信实体刷卡"),
        description = tr("未经过手机通知、支付页或银行短信的线下消费，可用「＋」手动或长按语音 3 秒补记"),
        badge = tr("暂不支持"),
    ),
)

@Composable
internal fun privacyAndTipsItems(): List<ScenarioItem> = listOf(
    ScenarioItem(
        icon = Icons.Filled.Security,
        title = tr("本机优先，数据完全可控"),
        description = tr("账单、截图原图与自定义图标默认仅存于手机本地，不上传第三方云；可选自建同步服务器或 WebDAV 网盘自动备份"),
    ),
    ScenarioItem(
        icon = Icons.Filled.BatteryChargingFull,
        title = tr("国产系统防杀后台建议"),
        description = tr("华为、小米、OPPO、vivo 等系统可能限制后台通知监听；若遇漏记，请在「设置 → 后台运行设置」开启自启动与无限制省电"),
    ),
    ScenarioItem(
        icon = Icons.Filled.BugReport,
        title = tr("遇到漏记、重复或识别异常"),
        description = tr("可在「设置 → 账单诊断日志」查看最近 7 天每笔账单的识别与去重链路，并一键导出排查"),
    ),
)

@Composable
internal fun SupportedScenariosContent(
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = qingColors
    val supported = supportedCaptureScenarios()
    val boundaries = knownBoundariesAndUnsupported()
    val privacyTips = privacyAndTipsItems()
    val verticalSpacing = if (compact) 10.dp else 14.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        ScenarioSectionCard(
            header = tr("自动与半自动记账场景"),
            items = supported,
            iconTint = QingPrimaryDeep,
            iconBg = QingPrimaryContainer,
            badgeTint = QingPrimaryDeep,
            badgeBg = QingPrimaryContainer,
            compact = compact,
            cardColor = colors.surfaceCard,
        )
        ScenarioSectionCard(
            header = tr("已知边界与暂不支持"),
            items = if (compact) boundaries + privacyTips.take(1) else boundaries,
            iconTint = QingWarning,
            iconBg = QingWarning.copy(alpha = 0.14f),
            badgeTint = QingWarning,
            badgeBg = QingWarning.copy(alpha = 0.14f),
            compact = compact,
            cardColor = colors.surfaceCard,
        )
        if (!compact) {
            ScenarioSectionCard(
                header = tr("隐私承诺与防漏记建议"),
                items = privacyTips,
                iconTint = QingPrimaryDeep,
                iconBg = QingPrimaryContainer,
                badgeTint = QingPrimaryDeep,
                badgeBg = QingPrimaryContainer,
                compact = false,
                cardColor = colors.surfaceCard,
            )
        }
    }
}

@Composable
private fun ScenarioSectionCard(
    header: String,
    items: List<ScenarioItem>,
    iconTint: Color,
    iconBg: Color,
    badgeTint: Color,
    badgeBg: Color,
    compact: Boolean,
    cardColor: Color,
) {
    val colors = qingColors
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        Text(
            text = header,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(cardColor),
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = if (compact) 54.dp else 60.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (compact) 12.dp else 16.dp,
                            vertical = if (compact) 10.dp else 12.dp,
                        ),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (compact) 30.dp else 34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(iconBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(if (compact) 16.dp else 18.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = item.title,
                                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            if (!compact && item.badge != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(badgeBg)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = item.badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = badgeTint,
                                    )
                                }
                            }
                        }
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportedScenariosSheet(
    onDismiss: () -> Unit,
) {
    val colors = qingColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceSheet,
        shape = RoundedCornerShape(
            topStart = QingRadiusTokens.radiusHero,
            topEnd = QingRadiusTokens.radiusHero,
        ),
        dragHandle = { QingSheetGrabber() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("支持场景与能力说明"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = tr("清晰透明地了解清衡能记什么、不采集什么"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = tr("关闭"))
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SupportedScenariosContent(compact = false)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

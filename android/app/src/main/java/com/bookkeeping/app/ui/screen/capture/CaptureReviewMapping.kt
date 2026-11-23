package com.bookkeeping.app.ui.screen.capture

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.merchant.MerchantIconCatalog
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.CategoryClassifier
import com.bookkeeping.app.notification.ClassificationDirection
import com.bookkeeping.app.notification.ClassificationInput
import com.bookkeeping.app.notification.ClassificationKind
import com.bookkeeping.app.notification.ClassificationResult
import com.bookkeeping.app.notification.ClassificationSource
import com.bookkeeping.app.notification.ClassificationStatus
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore

object CaptureReviewMapping {
    /** Explain when a review card has no usable original image. */
    fun screenshotUnavailableNotice(candidate: CaptureCandidate): String? {
        candidate.screenshotUnavailableReason?.let { reason ->
            return when (reason) {
                CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_QUOTA ->
                    tr("截图未保留（空间不足）；释放空间后重新导入。")
                CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_IO ->
                    tr("截图保存失败；请重新选择图片后核对。")
                CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_INVALID ->
                    tr("图片无效，截图未保留；请换图重新导入。")
                CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_MISSING ->
                    tr("截图未提供或未保留；请重新导入原图。")
                else -> null
            }
        }
        return when {
            candidate.reason == CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_QUOTA ->
                tr("截图未保留（空间不足）；释放空间后重新导入。")
            candidate.reason == CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_IO ->
                tr("截图保存失败；请重新选择图片后核对。")
            candidate.reason == CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_INVALID ->
                tr("图片无效，截图未保留；请换图重新导入。")
            candidate.reason == CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_MISSING ->
                tr("截图未提供或未保留；请重新导入原图。")
            candidate.reason == "screenshot decode failed" ||
                candidate.reason == "screenshot start failed" ||
                candidate.reason.startsWith("screenshot error ") ->
                tr("截图无法读取；请重新选择图片。")
            candidate.reason == "screenshot unsupported" ->
                tr("图片格式不支持；请换一张截图。")
            candidate.reason.startsWith(CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_PREFIX) ->
                tr("截图未能保留；请重新导入原图。")
            else -> null
        }
    }

    fun classificationFor(
        candidate: CaptureCandidate,
        merchant: String,
        contextText: String = "",
    ): ClassificationResult {
        val kind = when (candidate.kind) {
            CaptureCandidate.KIND_REFUND -> ClassificationKind.REFUND
            CaptureCandidate.KIND_TRANSFER -> ClassificationKind.TRANSFER
            CaptureCandidate.KIND_WITHDRAWAL -> ClassificationKind.WITHDRAWAL
            CaptureCandidate.KIND_RED_PACKET -> ClassificationKind.RED_PACKET
            CaptureCandidate.KIND_AUTO_PAY -> ClassificationKind.PAY_AFTER_USE
            CaptureCandidate.KIND_BILL -> ClassificationKind.BILL
            else -> ClassificationKind.PAYMENT
        }
        val direction = when (candidate.direction) {
            CaptureCandidate.DIRECTION_INCOME -> ClassificationDirection.INCOME
            CaptureCandidate.DIRECTION_EXPENSE -> ClassificationDirection.EXPENSE
            else -> ClassificationDirection.NEUTRAL
        }
        val source = when (candidate.paymentPlatform) {
            CaptureCandidate.PLATFORM_WECHAT -> ClassificationSource.WECHAT
            CaptureCandidate.PLATFORM_ALIPAY -> ClassificationSource.ALIPAY
            else -> ClassificationSource.OTHER
        }
        return CategoryClassifier.classify(
            ClassificationInput(
                merchantRaw = merchant,
                contextText = contextText,
                direction = direction,
                source = source,
                kind = kind,
                status = ClassificationStatus.COMPLETED,
                amountCents = candidate.amountCents ?: 0L,
            ),
        )
    }

    fun defaultCategory(candidate: CaptureCandidate): TransactionCategory =
        candidate.transactionCategory
            ?.let { runCatching { TransactionCategory.valueOf(it) }.getOrNull() }
            ?: classificationFor(
            candidate = candidate,
            merchant = candidate.merchantCandidates.firstOrNull().orEmpty(),
        ).category

    fun toTransactionEntity(
        candidate: CaptureCandidate,
        merchant: String,
        category: TransactionCategory,
        evidenceImageName: String? = null,
    ): TransactionEntity {
        val source = candidate.transactionSource
            ?.let { runCatching { TransactionSource.valueOf(it) }.getOrNull() }
            ?: when (candidate.paymentPlatform) {
            CaptureCandidate.PLATFORM_WECHAT -> TransactionSource.WECHAT
            CaptureCandidate.PLATFORM_ALIPAY -> TransactionSource.ALIPAY
            else -> TransactionSource.OTHER
        }
        val sourceLabel = when (source) {
            TransactionSource.BANK_SMS -> "银行短信"
            TransactionSource.WECHAT -> "微信"
            TransactionSource.ALIPAY -> "支付宝"
            TransactionSource.MANUAL -> "手动"
            TransactionSource.OTHER -> "其他来源"
        }
        val platformLabel = when (candidate.paymentPlatform) {
            CaptureCandidate.PLATFORM_WECHAT -> "微信"
            CaptureCandidate.PLATFORM_ALIPAY -> "支付宝"
            else -> "页面"
        }
        val refund = candidate.kind == CaptureCandidate.KIND_REFUND
        val income = refund || candidate.direction == CaptureCandidate.DIRECTION_INCOME
        val redPacketIncome = candidate.kind == CaptureCandidate.KIND_RED_PACKET && income
        val transferLike = candidate.kind == CaptureCandidate.KIND_TRANSFER ||
            candidate.kind == CaptureCandidate.KIND_WITHDRAWAL ||
            (candidate.kind == CaptureCandidate.KIND_RED_PACKET && !income)
        val name = merchant.trim().ifBlank {
            when (candidate.kind) {
                CaptureCandidate.KIND_REFUND -> "${sourceLabel}退款"
                CaptureCandidate.KIND_TRANSFER -> "${sourceLabel}转账"
                CaptureCandidate.KIND_RED_PACKET -> "${sourceLabel}红包"
                CaptureCandidate.KIND_WITHDRAWAL -> "${sourceLabel}提现"
                else -> "未知商户"
            }
        }
        val note = if (candidate.intakeMethod == CaptureCandidate.INTAKE_SMS) {
            "[银行短信待确认]"
        } else if (candidate.intakeMethod == CaptureCandidate.INTAKE_NOTIFICATION) {
            "[支付通知待确认] $sourceLabel"
        } else buildString {
            append(
                when (candidate.kind) {
                    CaptureCandidate.KIND_REFUND -> "[退款] "
                    CaptureCandidate.KIND_TRANSFER -> "[转账] "
                    CaptureCandidate.KIND_RED_PACKET -> "[红包] "
                    CaptureCandidate.KIND_WITHDRAWAL -> "[提现] "
                    CaptureCandidate.KIND_AUTO_PAY -> "[自动支付] "
                    CaptureCandidate.KIND_BILL -> "[账单] "
                    else -> "[扫描] "
                },
            )
            append(platformLabel)
            when (candidate.kind) {
                CaptureCandidate.KIND_TRANSFER,
                CaptureCandidate.KIND_RED_PACKET -> append(if (income) " · 收入" else " · 支出")
                else -> Unit
            }
            candidate.orderId?.let { append(" · 单号 ").append(it) }
        }
        return TransactionEntity(
            amountCents = requireNotNull(candidate.amountCents) {
                "A transaction cannot be created without a verified amount"
            },
            currency = candidate.transactionCurrency ?: "CNY",
            type = if (income) TransactionType.INCOME.name else TransactionType.EXPENSE.name,
            category = candidate.transactionCategory ?: when {
                refund -> TransactionCategory.OTHER.name
                redPacketIncome -> TransactionCategory.GIFT.name
                transferLike -> TransactionCategory.TRANSFER.name
                else -> category.name
            },
            merchant = name,
            note = note,
            source = source.name,
            timestamp = candidate.eventTimeMs?.takeIf { it > 0L } ?: candidate.capturedAtMs,
            merchantIconKey = MerchantIconCatalog.forMerchant(name)?.key,
            account = candidate.transactionAccount,
            remoteReviewStatus = when {
                refund -> "refund"
                transferLike -> "transfer"
                else -> "unverified"
            },
            sourceTransactionId = candidate.orderId,
            captureConfidence = candidate.confidence,
            captureKind = candidate.kind,
            captureObservedAtMs = candidate.capturedAtMs,
            evidenceImageName = evidenceImageName,
            eventTimeNeedsConfirmation = candidate.eventTimeNeedsConfirmation,
        )
    }
}

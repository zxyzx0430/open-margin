package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.data.repository.CaptureMergePolicy
import com.bookkeeping.app.data.repository.CrossSourceDuplicatePolicy
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import java.util.Locale

/**
 * The single pipeline every screenshot entry point (MediaStore watcher,
 * ACTION_SEND, Photo Picker) goes through.
 *
 * Stage order is fixed: validate the input, interpret the page, drop exact
 * duplicates, then apply the high / medium / low admission gate per draft.
 * Nothing here writes the ledger; the caller executes [ImportPlan.bookings]
 * through the normal repository, and only when auto booking is allowed.
 */
object ScreenshotImportPipeline {

    const val MAX_IMAGE_BYTES = 12L * 1024 * 1024

    private val SUPPORTED_MIME_PREFIXES = listOf("image/jpeg", "image/jpg", "image/png", "image/webp",
        "image/heic", "image/heif")

    fun validate(request: ScreenshotIntakeRequest): IntakeValidation {
        if (!request.permissionGranted) return IntakeValidation.Rejected(FailureReason.PERMISSION_DENIED)
        val uri = request.uri?.trim().orEmpty()
        if (uri.isEmpty() || !uri.startsWith("content://")) {
            return IntakeValidation.Rejected(FailureReason.URI_INVALID)
        }
        val mime = request.mimeType?.lowercase(Locale.ROOT)?.trim()
        if (mime.isNullOrEmpty() || SUPPORTED_MIME_PREFIXES.none { mime.startsWith(it) }) {
            return IntakeValidation.Rejected(FailureReason.UNSUPPORTED_MIME)
        }
        if (!request.readable) return IntakeValidation.Rejected(FailureReason.UNREADABLE)
        if ((request.byteSize ?: 0L) > MAX_IMAGE_BYTES) {
            return IntakeValidation.Rejected(FailureReason.OVERSIZED)
        }
        return IntakeValidation.Ready
    }

    /**
     * @param allowAuto false for preview-only paths; every candidate is then held
     *   for review instead of being booked automatically.
     * @param previousImageFingerprints fingerprints already present in the local
     *   candidate inbox, so a re-shared image is not booked twice.
     */
    fun plan(
        request: ScreenshotIntakeRequest,
        blocks: List<TextBlock>?,
        existing: List<LedgerFingerprint>,
        previousImageFingerprints: Set<String> = emptySet(),
        allowAuto: Boolean = true,
        previewOnly: Boolean = false,
        timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
        /**
         * 由**另一条采集通道**写下的账本行（通知/短信/无障碍/手工：没有截图
         * provenance）。层 1 只在这一组里找跨通道重复；传空表示不做该检查。
         */
        crossChannelRows: List<LedgerFingerprint> = emptyList(),
    ): ImportPlan {
        when (val validation = validate(request)) {
            is IntakeValidation.Rejected -> return ImportPlan(emptyList(), validation.reason)
            IntakeValidation.Ready -> Unit
        }
        if (blocks == null) return ImportPlan(emptyList(), FailureReason.DECODE_FAILED)
        if (blocks.none { it.text.isNotBlank() }) {
            return ImportPlan(emptyList(), FailureReason.OCR_EMPTY)
        }
        val fingerprint = request.imageFingerprint
        if (fingerprint != null && fingerprint in previousImageFingerprints) {
            return ImportPlan(emptyList(), repeatImage = true)
        }
        val evidence = ScreenshotPageInterpreter.interpret(blocks, request.capturedAtMs, timeZone)
        if (evidence.drafts.isEmpty()) {
            val failure = when (evidence.pageType) {
                ScreenshotPageInterpreter.PAGE_UNKNOWN -> FailureReason.UNKNOWN_PAGE
                else -> FailureReason.NOT_TRANSACTION
            }
            val detail = "pageType=${evidence.pageType}, platform=${evidence.platform}, reason=${evidence.reason}"
            return ImportPlan(emptyList(), failure, detailReason = detail)
        }

        val decisions = mutableListOf<CandidateDecision>()
        val draftsSeenHere = mutableListOf<TransactionDraft>()
        var mediumAsked = false
        evidence.drafts.forEach { draft ->
            val dedup = deduplicate(draft, existing)
            val assessment = ScreenshotCapturePolicy.assess(
                evidence = ScreenshotCapturePolicy.Evidence(
                    supportedPaymentPage = draft.supportedPaymentPage,
                    paymentPlatformKnown = evidence.platform != CaptureCandidate.PLATFORM_UNKNOWN,
                    transactionLike = evidence.transactionLike,
                    amountCents = draft.amountCents,
                    amountUnambiguous = draft.amountUnambiguous,
                    direction = draft.direction,
                    directionExplicit = draft.directionExplicit,
                    completionStatus = draft.completionStatus,
                    completionExplicit = draft.completionExplicit,
                    completionInferredFromList = draft.completionInferredFromList,
                    kind = draft.kind,
                    conflictingFields = evidence.conflictingFields,
                    duplicateResult = dedup.first,
                ),
                allowAuto = allowAuto,
                previewOnly = previewOnly,
            )
            var action = assessment.action
            if (action == Action.AUTO_BOOK && draft.reason == "paired chat transfer; time needs confirmation") {
                action = Action.ASK_USER
            }
            // Belt and braces: the policy already asks for these kinds, but a
            // screenshot decision must never carry AUTO_BOOK for money that
            // only the user can classify, even if a caller forgets to pass the
            // kind into the evidence.
            if (action == Action.AUTO_BOOK && ScreenshotCapturePolicy.requiresConfirmation(draft.kind)) {
                action = Action.ASK_USER
            }
            // 层 1：账本里已经有同额、同向、时间邻近、但由另一条采集通道记下的一条。
            // 层 3：同一张图里已经出现过同额、同向、同商户的一条。
            // 两者都只降级为人工确认，绝不静默替用户判断「这是同一笔」。
            val crossSource = if (action == Action.AUTO_BOOK || action == Action.ASK_USER) {
                crossSourceLedgerMatch(draft, request.capturedAtMs, crossChannelRows)
            } else {
                null
            }
            val sameImageRepeat = (action == Action.AUTO_BOOK || action == Action.ASK_USER) &&
                draftsSeenHere.any { isSameMoneyAsEarlierDraft(draft, it) }
            if (crossSource != null || sameImageRepeat) action = Action.ASK_USER
            draftsSeenHere += draft
            // 结构化标记：只描述「为什么被降级」。跨通道与同图重复是两种不同的断言
            //（前者指向账本里的一行，后者指向同一张图里的另一条草稿），所以不合并。
            // 动作判定顺序不变：这一步只读上面已经算好的结果。
            val duplicateHint = when {
                crossSource != null -> DuplicateHint.CROSS_CHANNEL
                sameImageRepeat -> DuplicateHint.SAME_IMAGE
                dedup.first == ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS -> DuplicateHint.LEDGER_AMBIGUOUS
                else -> DuplicateHint.NONE
            }
            var reason = draft.reason
            var matched: Long? = null
            when (action) {
                Action.LINK_EXISTING -> {
                    matched = dedup.second
                    reason = "existing ledger transaction"
                }
                Action.AUTO_BOOK -> reason = "high confidence; auto booked"
                Action.ASK_USER -> {
                    if (mediumAsked) {
                        decisions += CandidateDecision(
                            index = draft.index,
                            tier = assessment.tier,
                            action = action,
                            reason = "queued behind the previous confirmation",
                            draft = draft,
                            queued = true,
                            ledgerWriteAllowed = allowAuto && !previewOnly,
                            duplicateHint = duplicateHint,
                        )
                        return@forEach
                    }
                    mediumAsked = true
                    reason = when {
                        crossSource != null -> "same amount already recorded from another channel"
                        sameImageRepeat -> "same amount appears twice in this screenshot"
                        dedup.first == ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS -> "possible existing ledger transaction"
                        draft.reason == "paired chat transfer; time needs confirmation" -> draft.reason
                        !draft.completionExplicit -> "completion evidence missing; needs confirmation"
                        ScreenshotCapturePolicy.requiresConfirmation(draft.kind) ->
                            "${draft.kind.lowercase(Locale.ROOT)} needs confirmation before booking"
                        // 金额读到了、方向没读到：卡片的第一个动作是让用户点 支出/收入，
                        // 这句写进候选 reason，方便诊断时区分「问方向」和「问重复」。
                        draft.direction == CaptureCandidate.DIRECTION_UNKNOWN ->
                            "transaction direction not readable; asking the user to pick"
                        // A list row infers completion from its place in the
                        // list; that is enough for a payment, not for a
                        // transfer, whose state word the list never prints.
                        draft.kind == CaptureCandidate.KIND_TRANSFER &&
                            draft.completionInferredFromList ->
                            "transfer completion inferred from a bill list; needs confirmation"
                        assessment.tier == CaptureCandidate.TIER_HIGH ->
                            "high confidence but auto booking disabled"
                        else -> "needs user confirmation"
                    }
                }
                Action.WAIT_FOR_EVIDENCE -> {
                    action = Action.WAIT_FOR_EVIDENCE
                    reason = "transaction not completed yet"
                }
                Action.IGNORE -> {
                    action = Action.IGNORE
                    reason = draft.reason.ifBlank { "not a reliable transaction" }
                }
            }
            if (matched == null && crossSource != null) {
                // 只是「疑似」：记录被怀疑的那条，供待确认卡片/诊断追溯；不写库。
                matched = crossSource.transactionId
            }
            decisions += CandidateDecision(
                index = draft.index,
                tier = assessment.tier,
                action = action,
                reason = reason,
                draft = draft,
                matchedTransactionId = matched,
                ledgerWriteAllowed = allowAuto && !previewOnly,
                duplicateHint = duplicateHint,
            )
        }
        return ImportPlan(decisions)
    }

    /**
     * Only a positively identified row may be linked.
     *
     * 1. An exact platform-scoped order id is the only proof that two captures
     *    describe one transaction, so it is the only path to EXACT_MATCH. Two
     *    rows sharing an order id are one payment; a second match is AMBIGUOUS.
     * 2. Without an order id, platform + amount + direction + currency +
     *    merchant + the two-minute window is a *resemblance*, not an identity:
     *    a convenience store rings up the same amount twice in a row, so the
     *    same merchant and the same cents a minute apart are ordinary, distinct
     *    transactions. Those rows (and a row missing its merchant, and a row
     *    without a real event time) return AMBIGUOUS, which routes the
     *    candidate to human review. Nothing is merged silently: swallowing a
     *    real payment loses money from the ledger, while asking costs one tap.
     * 3. Different merchants mean different transactions (two shops can charge
     *    the same amount a minute apart), so those stay CLEAR.
     */
    fun deduplicate(
        draft: TransactionDraft,
        existing: List<LedgerFingerprint>,
    ): Pair<ScreenshotCapturePolicy.DuplicateResult, Long?> {
        val orderId = draft.orderId?.trim()?.takeIf(String::isNotEmpty)
        val platform = platformOf(draft.pageIdentity)
        if (orderId != null) {
            if (platform == CaptureCandidate.PLATFORM_UNKNOWN) {
                return ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS to null
            }
            val exact = existing.filter {
                it.platform == platform && it.platform != CaptureCandidate.PLATFORM_UNKNOWN &&
                    it.orderId?.trim() == orderId
            }
            when {
                exact.size == 1 -> return ScreenshotCapturePolicy.DuplicateResult.EXACT_MATCH to exact.single().transactionId
                exact.size > 1 -> return ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS to null
            }
        }
        val amount = draft.amountCents ?: return ScreenshotCapturePolicy.DuplicateResult.CLEAR to null
        if (platform == CaptureCandidate.PLATFORM_UNKNOWN) {
            return ScreenshotCapturePolicy.DuplicateResult.CLEAR to null
        }
        val merchant = normalize(draft.merchantCandidates.firstOrNull())
        val currency = normalizeCurrency(draft.currency)
        val sameShape = existing.filter { row ->
            row.platform == platform && row.platform != CaptureCandidate.PLATFORM_UNKNOWN &&
                row.amountCents == amount && row.direction == draft.direction &&
                normalizeCurrency(row.currency) == currency &&
                (orderId == null || row.orderId?.trim().let { it == null || it == orderId })
        }
        if (sameShape.isEmpty()) {
            return ScreenshotCapturePolicy.DuplicateResult.CLEAR to null
        }
        // Without a real transaction time nothing can be placed inside (or
        // outside) the window, so the match stays reviewable.
        if (draft.eventTimeMs == null) {
            return ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS to null
        }
        val inWindow = sameShape.filter { row ->
            row.eventTimeMs <= 0L ||
                kotlin.math.abs(row.eventTimeMs - draft.eventTimeMs) <= DEDUP_WINDOW_MS
        }
        if (inWindow.isEmpty()) {
            return ScreenshotCapturePolicy.DuplicateResult.CLEAR to null
        }
        // A row without a merchant name is plausible but not identified: the
        // OCR may simply have missed it, so the user decides.
        if (merchant.isEmpty() || inWindow.any { normalize(it.merchant).isEmpty() }) {
            return ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS to null
        }
        val identified = inWindow.filter { normalize(it.merchant) == merchant }
        // Two distinct payments to one shop in the same two minutes look exactly
        // like this, and nothing here can tell them apart: ask, never link.
        // Only the order id branch above may ever return EXACT_MATCH.
        return when {
            identified.isEmpty() -> ScreenshotCapturePolicy.DuplicateResult.CLEAR to null
            else -> ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS to null
        }
    }

    /** Next medium-confidence candidate to ask about; the rest stay queued. */
    fun nextConfirmation(decisions: List<CandidateDecision>): CandidateDecision? =
        decisions.filter { it.action == Action.ASK_USER }.minByOrNull { it.index }

    /**
     * 层 1：账本里是否已有「同额、同向、同币种、同平台、时间邻近，但**由另一条
     * 采集通道**先记下」的一条。
     *
     * 关键事实：通知和截图写进账本的 `source` 都是 `WECHAT` / `ALIPAY`——包名只进
     * 通知指纹表，不进账本。所以「跨来源」不能用 `source` 区分，真正区分通道的是
     * 截图 provenance（`captureObservedAtMs`）。调用方把**没有** provenance 的行
     * 作为 [crossChannelRows] 传进来；同通道的重复仍由 [deduplicate] 处理。
     *
     * 只把 AUTO_BOOK 降级为待确认：命中不等于同一笔，绝不代替用户判断。
     * 页面没有真实交易时间时用截图时间做锚点并放宽到
     * [UNTIMED_CROSS_SOURCE_WINDOW_MS]，因为用户常常付款后过一阵才截图。
     *
     * @return 时间上最接近的一条账本行；没有就返回 null。
     */
    fun crossSourceLedgerMatch(
        draft: TransactionDraft,
        capturedAtMs: Long,
        crossChannelRows: List<LedgerFingerprint>,
    ): LedgerFingerprint? {
        val amount = draft.amountCents?.takeIf { it > 0L } ?: return null
        val platform = platformOf(draft.pageIdentity)
        if (platform == CaptureCandidate.PLATFORM_UNKNOWN) return null
        val eventTime = draft.eventTimeMs?.takeIf { it > 0L }
        val anchor = eventTime ?: capturedAtMs
        val window = if (eventTime == null) UNTIMED_CROSS_SOURCE_WINDOW_MS else CROSS_SOURCE_WINDOW_MS
        val currency = normalizeCurrency(draft.currency)
        val merchant = draft.merchantCandidates.firstOrNull()
        return crossChannelRows
            .asSequence()
            // 微信和支付宝同额同方也是两笔钱，不跨平台提示。
            .filter { it.platform == platform }
            .filter { it.amountCents == amount }
            .filter { it.direction == draft.direction }
            .filter { normalizeCurrency(it.currency) == currency }
            .filter { it.eventTimeMs <= 0L || kotlin.math.abs(it.eventTimeMs - anchor) <= window }
            .filter { ledgerMerchantCompatible(merchant, it.merchant) }
            .minByOrNull { row ->
                if (row.eventTimeMs <= 0L) Long.MAX_VALUE else kotlin.math.abs(row.eventTimeMs - anchor)
            }
    }

    /**
     * 层 3：同一张图里已经出现过同额、同向、同商户的草稿。
     *
     * 账单列表页可能把同一笔钱列两次（或用户重复截图同一页），第二行不能静默
     * 自动入账。商户缺失时无法断言「同方」，不在这里降级。
     */
    fun isSameMoneyAsEarlierDraft(draft: TransactionDraft, earlier: TransactionDraft): Boolean {
        val amount = draft.amountCents?.takeIf { it > 0L } ?: return false
        if (earlier.amountCents != amount) return false
        if (earlier.direction != draft.direction) return false
        if (normalizeCurrency(earlier.currency) != normalizeCurrency(draft.currency)) return false
        val mine = draft.merchantCandidates.firstOrNull().orEmpty()
        val theirs = earlier.merchantCandidates.firstOrNull().orEmpty()
        if (!CaptureMergePolicy.hasMerchantInfo(mine)) return false
        if (!CaptureMergePolicy.hasMerchantInfo(theirs)) return false
        return CrossSourceDuplicatePolicy.merchantsSame(mine, theirs)
    }

    /**
     * 层 1 的商户闸门：两边都有真实商户且明显不是一家 → 不可能是同一笔。
     * 任何一边缺商户（含「未知商户」这类占位名）都放行，因为截图 OCR 常常读不到，
     * 此时只能交用户确认，不能当作反证。
     */
    private fun ledgerMerchantCompatible(draftMerchant: String?, ledgerMerchant: String?): Boolean {
        if (draftMerchant.isNullOrBlank() || ledgerMerchant.isNullOrBlank()) return true
        if (!CaptureMergePolicy.hasMerchantInfo(draftMerchant)) return true
        if (!CaptureMergePolicy.hasMerchantInfo(ledgerMerchant)) return true
        return CrossSourceDuplicatePolicy.merchantsSame(draftMerchant, ledgerMerchant)
    }

    private fun normalize(value: String?): String =
        value.orEmpty().trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

    /** Legacy rows without a currency column value are CNY, like the entity default. */
    private fun normalizeCurrency(value: String?): String =
        value?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: "CNY"

    private fun platformOf(pageIdentity: String): String = when {
        pageIdentity.startsWith("wechat") -> CaptureCandidate.PLATFORM_WECHAT
        pageIdentity.startsWith("alipay") -> CaptureCandidate.PLATFORM_ALIPAY
        else -> CaptureCandidate.PLATFORM_UNKNOWN
    }

    private const val DEDUP_WINDOW_MS = 2L * 60 * 1000

    /** 页面有真实交易时间时的跨来源询问窗口（与层 2 拦截窗口一致）。 */
    private const val CROSS_SOURCE_WINDOW_MS = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS

    /**
     * 页面只有截图时间（支付成功页通常不显示日期）时的询问窗口。
     * 沿用本仓库手工记账疑似重复「最宽 12 小时」的粗筛口径：这里只是多问一句，
     * 不写库、不拦截，宽一点比静默重复更安全。
     */
    private const val UNTIMED_CROSS_SOURCE_WINDOW_MS = 12L * 60 * 60 * 1000
}

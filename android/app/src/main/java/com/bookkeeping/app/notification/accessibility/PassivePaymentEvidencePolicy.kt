package com.bookkeeping.app.notification.accessibility

/**
 * Passive capture is a financial event detector, not a parser preview.
 * Keep the old parser and explicit import semantics; verify ambiguous node
 * batches with OCR before they can create user-visible review cards.
 */
internal object PassivePaymentEvidencePolicy {
    private fun identified(card: PaymentCardDetection): Boolean =
        !card.orderId.isNullOrBlank() || card.eventTimeMs != null && card.merchantCandidates.isNotEmpty()

    fun actionable(cards: List<PaymentCardDetection>): List<PaymentCardDetection> =
        cards.mapNotNull { card ->
            when {
                card.isFinal -> card
                card.status == PaymentCardStatus.UNKNOWN && identified(card) ->
                    card.copy(reviewRequired = true)
                else -> null
            }
        }

    fun requiresOcr(raw: List<PaymentCardDetection>, actionable: List<PaymentCardDetection>): Boolean =
        raw.any { it.status == PaymentCardStatus.COMPLETED || it.status == PaymentCardStatus.UNKNOWN } && (actionable.size != 1 ||
            actionable.any { it.reviewRequired || !it.isFinal })

    // A completed transaction with explicit completion or independent identity
    // remains recoverable even when OCR is empty, fails, or lacks geometry.
    fun recoverable(cards: List<PaymentCardDetection>): List<PaymentCardDetection> =
        actionable(cards).filter { it.isFinal && (it.completionExplicit || identified(it) ||
            !it.reviewRequired && it.merchantCandidates.isNotEmpty()) || identified(it) }
            .map { it.copy(reviewRequired = true) }

    fun selectAfterOcr(
        nodeCards: List<PaymentCardDetection>,
        ocrCards: List<PaymentCardDetection>,
        history: Boolean,
    ): List<PaymentCardDetection> {
        if (history) return emptyList()
        val visual = actionable(ocrCards)
        val recoverable = recoverable(nodeCards)
        if (visual.isEmpty()) return recoverable
        // Preserve genuinely independent cards missed by OCR only with a
        // distinct strong order ID. Never fan out conflicting amount guesses.
        val independent = recoverable.filter { node ->
            val id = node.orderId?.takeIf { it.isNotBlank() }
            val merchant = node.merchantCandidates.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            visual.all { other ->
                val otherId = other.orderId?.takeIf { it.isNotBlank() }
                val otherMerchant = other.merchantCandidates.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                // If both strong IDs exist, they decide. Without IDs, preserve an
                // independently completed card only when its own merchant and
                // amount do not overlap the OCR card. Keep it review-only.
                if (id != null && otherId != null) id != otherId
                else node.completionExplicit && merchant != null && otherMerchant != null &&
                    !merchant.contains(otherMerchant, ignoreCase = true) &&
                    !otherMerchant.contains(merchant, ignoreCase = true) &&
                    node.amountCents != other.amountCents
            }
        }
        return visual + independent
    }
}

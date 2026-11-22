package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore

/** Pure decision and warning mapping for actions shown in the review notification. */
object ScreenshotReviewNotificationPolicy {
    enum class Action { RECORD, IGNORE, OPEN_REVIEW }

    enum class Warning {
        WEAK_DUPLICATE,
        NOTIFICATION_KEY_AMBIGUITY,
        SAME_IMAGE_REPEAT,
    }

    fun warning(candidate: CaptureCandidate): Warning? = when {
        candidate.reason == CaptureCandidate.REASON_LIKELY_DUPLICATE -> Warning.WEAK_DUPLICATE
        candidate.reason == CaptureCandidate.REASON_SAME_IMAGE_REPEAT -> Warning.SAME_IMAGE_REPEAT
        candidate.intakeMethod in setOf(CaptureCandidate.INTAKE_NOTIFICATION, CaptureCandidate.INTAKE_SMS) &&
            candidate.reason.contains("notification record key", ignoreCase = true) ->
            Warning.NOTIFICATION_KEY_AMBIGUITY
        candidate.intakeMethod in setOf(CaptureCandidate.INTAKE_NOTIFICATION, CaptureCandidate.INTAKE_SMS) &&
            candidate.reason.contains("notification record link", ignoreCase = true) ->
            Warning.NOTIFICATION_KEY_AMBIGUITY
        candidate.intakeMethod in setOf(CaptureCandidate.INTAKE_NOTIFICATION, CaptureCandidate.INTAKE_SMS) &&
            candidate.reason.contains("legacy notification fingerprint", ignoreCase = true) ->
            Warning.NOTIFICATION_KEY_AMBIGUITY
        else -> null
    }

    /** Unreadable source evidence stays in the in-app review flow; notification actions cannot bypass it. */
    fun requiresImageReview(candidate: CaptureCandidate): Boolean =
        candidate.screenshotUnavailableReason != null ||
            candidate.reason.startsWith(CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_PREFIX)

    /** A warning never grants permission: image, direction and preview gates all apply. */
    fun canRecord(candidate: CaptureCandidate): Boolean =
        !requiresImageReview(candidate) && CaptureBookGate.mayConfirm(candidate)

    fun availableActions(candidate: CaptureCandidate): Set<Action> = when {
        requiresImageReview(candidate) -> setOf(Action.OPEN_REVIEW)
        canRecord(candidate) -> setOf(Action.RECORD, Action.IGNORE)
        else -> setOf(Action.IGNORE)
    }
}

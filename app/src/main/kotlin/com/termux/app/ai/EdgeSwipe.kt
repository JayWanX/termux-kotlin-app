package com.termux.app.ai

/**
 * The swipe rules behind the panel's edge gesture.
 *
 * Kept as pure functions so the thresholds are pinned by tests without synthesising touch events, and
 * so the screens and their tests share one definition instead of two that can drift apart.
 */
internal object EdgeSwipe {

    /**
     * Whether a drag that started on the right edge should open the panel.
     *
     * @param deltaX horizontal travel since the drag began; negative is towards the left.
     * @param thresholdPx distance that counts as deliberate.
     */
    fun shouldOpen(deltaX: Float, thresholdPx: Float): Boolean =
        thresholdPx > 0f && deltaX < -thresholdPx

    /**
     * Whether a drag inside the open panel should dismiss it.
     *
     * @param accumulatedDx rightward travel accumulated since the drag began.
     * @param thresholdPx distance that counts as deliberate.
     */
    fun shouldClose(accumulatedDx: Float, thresholdPx: Float): Boolean =
        thresholdPx > 0f && accumulatedDx > thresholdPx
}

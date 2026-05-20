package com.transformplatform.scheduler.service

// ── WorkflowOrchestrator ──────────────────────────────────────────────────────
//
// Port interface defined in platform-scheduler so that WindowCloseChecker
// can call it without a circular dependency.
//
// The implementation (WorkflowOrchestratorImpl) lives in platform-api where
// it has access to TransformationPipeline, S3ArchivalService, SpecService, etc.
//
// Both executeClosingActions() and executeOnFileArrived() are synchronous for
// Phase 1d. Phase 2 will introduce async execution backed by Quartz retries.

interface WorkflowOrchestrator {

    /**
     * Execute all enabled ON_CLOSING actions for the given window.
     * Called by WindowCloseChecker.executeCloseSequence() after startClosing().
     *
     * @return total records processed across all actions (used for WindowClosed audit log)
     * @throws Exception if any action fails and continueOnFailure = false
     */
    fun executeClosingActions(windowInstanceId: String): Int

    /**
     * Execute all enabled ON_FILE_ARRIVED actions for the given window.
     * Called by FileArrivalHandlerService when a file matches the window's close trigger.
     *
     * @return total records processed across all actions
     */
    fun executeOnFileArrived(windowInstanceId: String): Int
}

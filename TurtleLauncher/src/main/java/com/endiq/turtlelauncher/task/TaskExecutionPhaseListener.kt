package com.endiq.turtlelauncher.task

/**
 * Listener for the various phases of a task execution.
 */
interface TaskExecutionPhaseListener {
    fun onBeforeStart() {}
    fun execute() {}
    fun onEnded() {}
    fun onFinally() {}
    /**
     * What runs after a task throws during execution.
     * @param throwable the exception that triggered this
     */
    fun onThrowable(throwable: Throwable) {}
}
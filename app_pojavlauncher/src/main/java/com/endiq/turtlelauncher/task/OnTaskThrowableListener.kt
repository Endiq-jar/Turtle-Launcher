package com.endiq.turtlelauncher.task

fun interface OnTaskThrowableListener {
    fun onThrowable(throwable: Throwable)
}
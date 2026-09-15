package com.helix.app.ui

/** API 30+ storage AppOp changes may kill the app; the host runner executes these in grant phases. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresStorageHostPhase

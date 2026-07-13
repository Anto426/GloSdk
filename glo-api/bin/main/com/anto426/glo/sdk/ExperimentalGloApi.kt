package com.anto426.glo.sdk

/**
 * Marks protocol surfaces that exist in the analysed SDK but are not yet backed by a complete
 * capture from a physical device. Callers must explicitly opt in before using them.
 */
@RequiresOptIn(
    message = "This part of the glo protocol still requires dynamic verification on a physical device.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalGloApi

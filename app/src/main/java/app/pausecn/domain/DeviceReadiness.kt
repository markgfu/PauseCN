package app.pausecn.domain

fun isDeviceReadyForIntervention(
    screenInteractive: Boolean,
    keyguardLocked: Boolean,
): Boolean = screenInteractive && !keyguardLocked

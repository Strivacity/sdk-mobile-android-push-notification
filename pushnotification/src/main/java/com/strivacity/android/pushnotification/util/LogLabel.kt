package com.strivacity.android.pushnotification.util

import com.strivacity.android.pushnotification.model.PushNotificationException

/** The failure's SDK error code, else its full class name. Never its message. */
internal fun logLabel(failure: Throwable): String =
    if (failure is PushNotificationException) failure.code else failure.javaClass.name

package com.example.phonebilling.ui.common

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

fun Long.toRupiah(): String {
    val number = NumberFormat.getCurrencyInstance(Locale("in", "ID"))
    return number.format(this / 100.0)
}

fun Long.toClock(): String =
    SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(this))

fun Long.toCountdown(): String {
    val safe = coerceAtLeast(0)
    val hours = TimeUnit.MILLISECONDS.toHours(safe)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

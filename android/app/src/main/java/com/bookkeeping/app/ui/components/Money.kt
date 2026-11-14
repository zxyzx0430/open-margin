package com.bookkeeping.app.ui.components

import java.util.Locale

fun formatCny(cents: Long): String = "¥" + "%.2f".format(Locale.CHINA, cents / 100.0)

fun formatSignedCny(cents: Long, income: Boolean): String =
    (if (income) "+" else "−") + formatCny(kotlin.math.abs(cents))

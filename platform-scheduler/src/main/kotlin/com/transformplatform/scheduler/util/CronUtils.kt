package com.transformplatform.scheduler.util

import org.springframework.scheduling.support.CronExpression

// ── CronUtils ─────────────────────────────────────────────────────────────────
//
// Spring's CronExpression requires 6 fields:
//   <seconds> <minutes> <hours> <day-of-month> <month> <day-of-week>
//
// Standard Unix / crontab format uses 5 fields (no seconds field):
//   <minutes> <hours> <day-of-month> <month> <day-of-week>
//
// This utility accepts both.  When a 5-field expression is detected, it prepends
// "0 " (fire at second=0) to produce the 6-field Spring equivalent.
//
// Examples:
//   "0 6 * * *"       → "0 0 6 * * *"   daily at 06:00:00
//   "30 8 * * 1-5"    → "0 30 8 * * 1-5" weekdays at 08:30:00
//   "0 0 6 * * *"     → "0 0 6 * * *"    already 6-field, unchanged
//   "0/30 * * * * ?"  → "0/30 * * * * ?" already 6-field, unchanged

/**
 * Normalise this cron string to Spring's 6-field format.
 * 5-field Unix cron expressions are accepted and automatically prefixed with "0 " (seconds).
 */
fun String.toSpringCron(): String {
    val trimmed = this.trim()
    val fieldCount = trimmed.split("\\s+".toRegex()).size
    return if (fieldCount == 5) "0 $trimmed" else trimmed
}

/**
 * Parse a cron expression, accepting both 5-field Unix and 6-field Spring formats.
 *
 * Prefer this over [CronExpression.parse] wherever user-supplied or Postman-supplied
 * cron strings are processed, to avoid "Cron expression must consist of 6 fields" errors.
 */
fun parseCron(expression: String): CronExpression = CronExpression.parse(expression.toSpringCron())

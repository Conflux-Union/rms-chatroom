/**
 * Unified datetime utilities for the application.
 *
 * All datetime values from the backend are in UTC ISO 8601 format with Z suffix.
 * This module provides utilities to parse and format these values for display
 * in the user's local timezone (Beijing time for this application).
 */

/**
 * Parse a UTC datetime string from the backend.
 * Handles both ISO 8601 with Z suffix and without.
 *
 * @param dateStr - UTC datetime string from backend (e.g., "2026-01-14T10:30:00Z")
 * @returns Date object in local timezone
 */
export function parseUTCDateTime(dateStr: string): Date {
  // Ensure the string is treated as UTC
  const utcStr = dateStr.endsWith('Z') ? dateStr : dateStr + 'Z'
  return new Date(utcStr)
}

/**
 * Format a UTC datetime string for display.
 * Converts to local timezone (Beijing time) and formats as "YYYY-MM-DD HH:mm".
 *
 * @param dateStr - UTC datetime string from backend
 * @returns Formatted string in local timezone
 */
export function formatDateTime(dateStr: string): string {
  const date = parseUTCDateTime(dateStr)
  const pad = (n: number) => String(n).padStart(2, '0')
  const y = date.getFullYear()
  const m = pad(date.getMonth() + 1)
  const d = pad(date.getDate())
  const hh = pad(date.getHours())
  const mm = pad(date.getMinutes())
  return `${y}-${m}-${d} ${hh}:${mm}`
}

/**
 * Format a UTC datetime string for time-only display.
 * Converts to local timezone and formats as "HH:mm:ss".
 *
 * @param dateStr - UTC datetime string from backend
 * @returns Formatted time string in local timezone
 */
export function formatTime(dateStr: string): string {
  const date = parseUTCDateTime(dateStr)
  return date.toLocaleTimeString('zh-CN', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/**
 * Format a Date object for time-only display.
 * Formats as "HH:mm:ss" in local timezone.
 *
 * @param date - Date object
 * @returns Formatted time string
 */
export function formatTimeFromDate(date: Date): string {
  return date.toLocaleTimeString('zh-CN', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/**
 * Get the timestamp in milliseconds from a UTC datetime string.
 *
 * @param dateStr - UTC datetime string from backend
 * @returns Unix timestamp in milliseconds
 */
export function getTimestamp(dateStr: string): number {
  return parseUTCDateTime(dateStr).getTime()
}

/**
 * Calculate the difference in minutes between two UTC datetime strings.
 *
 * @param dateStr1 - First UTC datetime string
 * @param dateStr2 - Second UTC datetime string
 * @returns Difference in minutes (dateStr2 - dateStr1)
 */
export function diffMinutes(dateStr1: string, dateStr2: string): number {
  const time1 = getTimestamp(dateStr1)
  const time2 = getTimestamp(dateStr2)
  return (time2 - time1) / 1000 / 60
}

/**
 * Check if a UTC datetime string is within the last N minutes.
 *
 * @param dateStr - UTC datetime string from backend
 * @param minutes - Number of minutes to check
 * @returns True if the datetime is within the last N minutes
 */
export function isWithinMinutes(dateStr: string, minutes: number): boolean {
  const messageTime = parseUTCDateTime(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - messageTime.getTime()
  return diffMs / 1000 / 60 <= minutes
}

/**
 * Format duration in milliseconds to "mm:ss" format.
 * Used for audio/video playback duration.
 *
 * @param ms - Duration in milliseconds
 * @returns Formatted duration string
 */
export function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000)
  const mins = Math.floor(seconds / 60)
  const secs = seconds % 60
  return `${mins}:${secs.toString().padStart(2, '0')}`
}

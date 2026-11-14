package com.voyagerfiles.ui.screens

import rikka.shizuku.Shizuku
import java.io.File

/** Whether a `su` binary is on the device. Says nothing about whether root will be granted. */
internal fun suBinaryPresent(): Boolean {
    val pathDirs = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }
    val knownDirs = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/debug_ramdisk")
    return (pathDirs + knownDirs).distinct().any { dir -> runCatching { File(dir, "su").exists() }.getOrDefault(false) }
}

/** Whether the Shizuku service is running and reachable. */
internal fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

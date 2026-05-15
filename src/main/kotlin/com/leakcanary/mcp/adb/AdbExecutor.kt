package com.leakcanary.mcp.adb

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Executes adb commands via ProcessBuilder and returns raw output.
 * Supports optional device_id for multi-device setups.
 * Resolves the adb binary path automatically from ANDROID_HOME, PATH, or common SDK locations.
 * Fully cross-platform: Windows, macOS, Linux.
 */
object AdbExecutor {

    private const val ADB_TIMEOUT_SECONDS = 30L

    /**
     * Resolved absolute path to the adb binary.
     * Checked once on first use and cached for subsequent calls.
     */
    private val adbPath: String by lazy { resolveAdbPath() }

    /** Whether the host OS is Windows. */
    private val isWindows: Boolean by lazy {
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
    }

    /**
     * Runs the full LeakCanary logcat dump (no OS-level grep filtering,
     * filtering is done in Kotlin by the parser).
     *
     * @param deviceId optional device serial for multi-device selection
     * @return raw Logcat output
     */
    fun captureLeakLogs(deviceId: String? = null): Result<String> {
        return captureFullLogs(deviceId)
    }

    /**
     * Runs the full unfiltered LeakCanary logcat dump.
     *
     * @param deviceId optional device serial for multi-device selection
     * @return raw unfiltered LeakCanary logcat output
     */
    fun captureFullLogs(deviceId: String? = null): Result<String> {
        return runCommand(buildAdbLogcatCommand(deviceId))
    }

    /**
     * Lists all connected devices and emulators via `adb devices`.
     *
     * @return raw output of `adb devices`
     */
    fun listDevices(): Result<String> {
        return runCommand(listOf(adbPath, "devices"))
    }

    /**
     * Runs an arbitrary shell command on the device via `adb shell <command>`.
     * No host-side shell wrapper needed — adb shell handles piping/quotes on the device.
     *
     * @param deviceId optional device serial for multi-device selection
     * @param shellCommand the command to run inside `adb shell`
     * @return raw output of the shell command
     */
    fun runShellCommand(deviceId: String?, shellCommand: String): Result<String> {
        val command = mutableListOf<String>().apply {
            add(adbPath)
            if (!deviceId.isNullOrBlank()) {
                add("-s")
                add(deviceId)
            }
            add("shell")
            add(shellCommand)
        }
        return runCommand(command)
    }

    /**
     * Clears the logcat buffer on the device.
     *
     * @param deviceId optional device serial for multi-device selection
     * @return result of the clear operation
     */
    fun clearLogcat(deviceId: String? = null): Result<String> {
        val command = mutableListOf<String>().apply {
            add(adbPath)
            if (!deviceId.isNullOrBlank()) {
                add("-s")
                add(deviceId)
            }
            add("logcat")
            add("-c")
        }
        return runCommand(command)
    }

    /**
     * Builds the adb logcat command: [`adb`, `logcat`, `-d`, `-s`, `LeakCanary`]
     * (plus optional `-s <deviceId>`).
     */
    private fun buildAdbLogcatCommand(deviceId: String?): List<String> {
        return mutableListOf<String>().apply {
            add(adbPath)
            if (!deviceId.isNullOrBlank()) {
                add("-s")
                add(deviceId)
            }
            add("logcat")
            add("-d")
            add("-s")
            add("LeakCanary")
        }
    }

    /**
     * Resolves the absolute path to the adb binary by checking:
     * 1. ANDROID_HOME/platform-tools/adb (or adb.exe)
     * 2. ANDROID_SDK_ROOT/platform-tools/adb (or adb.exe)
     * 3. Common known paths per OS
     * 4. Falls back to "adb" (relying on PATH)
     */
    private fun resolveAdbPath(): String {
        val adbExe = if (isWindows) "adb.exe" else "adb"

        // Check ANDROID_HOME
        val fromAndroidHome = System.getenv("ANDROID_HOME")
        if (!fromAndroidHome.isNullOrBlank()) {
            val candidate = File(fromAndroidHome, "platform-tools/$adbExe")
            if (candidate.exists()) return candidate.absolutePath
        }

        // Check ANDROID_SDK_ROOT
        val fromSdkRoot = System.getenv("ANDROID_SDK_ROOT")
        if (!fromSdkRoot.isNullOrBlank()) {
            val candidate = File(fromSdkRoot, "platform-tools/$adbExe")
            if (candidate.exists()) return candidate.absolutePath
        }

        // Check LOCALAPPDATA (Windows) — common for Android Studio installs
        if (isWindows) {
            val localAppData = System.getenv("LOCALAPPDATA")
            if (!localAppData.isNullOrBlank()) {
                val candidate = File(localAppData, "Android/Sdk/platform-tools/$adbExe")
                if (candidate.exists()) return candidate.absolutePath
            }
        }

        val home = System.getProperty("user.home") ?: ""

        // macOS: ~/Library/Android/sdk/platform-tools/adb
        if (!isWindows) {
            val macCandidate = File(home, "Library/Android/sdk/platform-tools/$adbExe")
            if (macCandidate.exists()) return macCandidate.absolutePath

            // Linux: ~/Android/Sdk/platform-tools/adb
            val linuxCandidate = File(home, "Android/Sdk/platform-tools/$adbExe")
            if (linuxCandidate.exists()) return linuxCandidate.absolutePath

            // /usr/local/bin/adb
            val usrLocalBin = File("/usr/local/bin/$adbExe")
            if (usrLocalBin.exists()) return usrLocalBin.absolutePath
        }

        return "adb"
    }

    /**
     * Executes a command via ProcessBuilder.
     * Cross-platform — does not assume /bin/sh.
     */
    private fun runCommand(command: List<String>): Result<String> {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }

            val finished = process.waitFor(ADB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Result.failure(RuntimeException("adb command timed out after ${ADB_TIMEOUT_SECONDS}s"))
            }

            val exitCode = process.exitValue()
            if (exitCode != 0 && output.isBlank()) {
                Result.failure(RuntimeException("adb command failed with exit code $exitCode. Ensure adb is installed and a device is connected."))
            } else {
                Result.success(output)
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException("Failed to execute adb command: ${e.message}", e))
        }
    }
}

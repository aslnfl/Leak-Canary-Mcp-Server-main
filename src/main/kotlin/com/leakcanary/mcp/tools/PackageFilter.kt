package com.leakcanary.mcp.tools

import com.leakcanary.mcp.model.LeakTrace
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * Extracts the app package name from a fully qualified class name.
 * Takes the first 3 dot-separated segments (e.g., "com.todo.app" from "com.todo.app.ui.LoginActivity").
 * Falls back to fewer segments if the class name has less than 3.
 */
fun extractAppPackage(className: String): String {
    val parts = className.split(".")
    return when {
        parts.size >= 3 -> parts.take(3).joinToString(".")
        parts.size >= 2 -> parts.take(2).joinToString(".")
        else -> className
    }
}

/**
 * Extracts unique app package names from a list of leak traces,
 * based on the leaking class name in each trace.
 */
fun detectAppPackages(traces: List<LeakTrace>): Set<String> {
    return traces
        .map { it.leakingClassName }
        .filter { it.isNotBlank() }
        .map { extractAppPackage(it) }
        .toSet()
}

/**
 * Filters leak traces to only those whose leaking class name starts with the given package.
 */
fun filterByPackage(traces: List<LeakTrace>, packageName: String): List<LeakTrace> {
    return traces.filter { it.leakingClassName.startsWith(packageName, ignoreCase = true) }
}

/**
 * Applies package filtering only when data came from logcat (which may contain leaks from
 * multiple apps). When data came from app storage, it's already scoped to the requested
 * package, and leaking class names are simple names (e.g., "HomeActivity") rather than
 * fully qualified, so package prefix filtering would incorrectly filter out all results.
 */
fun filterByPackageIfNeeded(
    traces: List<LeakTrace>,
    packageName: String?,
    source: LeakDataSource,
    toolName: String
): PackageFilterResult {
    // App storage data is already scoped to the package — no filtering needed
    if (source == LeakDataSource.APP_STORAGE) {
        return PackageFilterResult.Ok(traces)
    }

    if (!packageName.isNullOrBlank()) {
        val filtered = filterByPackage(traces, packageName)
        if (filtered.isEmpty()) {
            return PackageFilterResult.NoMatch(
                "No leak traces found for package '$packageName'. " +
                        "Detected packages: ${detectAppPackages(traces).sorted().joinToString(", ")}"
            )
        }
        return PackageFilterResult.Ok(filtered)
    }

    // No package specified — check for multi-app
    val multiAppPrompt = buildMultiAppPrompt(traces, toolName)
    if (multiAppPrompt != null) {
        return PackageFilterResult.MultiApp(multiAppPrompt)
    }
    return PackageFilterResult.Ok(traces)
}

sealed class PackageFilterResult {
    data class Ok(val traces: List<LeakTrace>) : PackageFilterResult()
    data class NoMatch(val message: String) : PackageFilterResult()
    data class MultiApp(val prompt: CallToolResult) : PackageFilterResult()
}

/**
 * If multiple app packages are detected and no package_name was provided,
 * returns a CallToolResult prompting the user to choose. Otherwise returns null.
 */
fun buildMultiAppPrompt(traces: List<LeakTrace>, toolName: String): CallToolResult? {
    val packages = detectAppPackages(traces)
    if (packages.size <= 1) return null

    val packageList = packages.sorted().mapIndexed { index, pkg ->
        val count = traces.count { it.leakingClassName.startsWith(pkg, ignoreCase = true) }
        "  ${index + 1}. $pkg ($count leak${if (count != 1) "s" else ""})"
    }.joinToString("\n")

    return CallToolResult(
        content = listOf(
            TextContent(
                text = "Multiple apps with LeakCanary detected. Please specify which app to analyze " +
                        "by providing the 'package_name' parameter.\n\n" +
                        "Detected apps:\n$packageList\n\n" +
                        "Example: use package_name=\"${packages.sorted().first()}\" to filter leaks for that app."
            )
        )
    )
}

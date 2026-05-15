package com.leakcanary.mcp.analyzer

import com.leakcanary.mcp.model.LeakTrace

/**
 * Generates actionable fix suggestions based on a leak's classification.
 * Each fix is a human-readable recommendation specific to the leak pattern.
 */
object FixSuggester {

    /**
     * Returns a list of fix suggestions for the given analyzed leak trace.
     *
     * @param trace the analyzed LeakTrace (must have classification populated)
     * @return list of human-readable fix suggestion strings
     */
    fun suggest(trace: LeakTrace): List<String> {
        val fixes = mutableListOf<String>()

        when (trace.classification) {
            "SINGLETON_LEAK" -> fixes.addAll(singletonFixes(trace))
            "LISTENER_LEAK" -> fixes.addAll(listenerFixes(trace))
            "CONTEXT_LEAK" -> fixes.addAll(contextFixes(trace))
            "LIBRARY_LEAK" -> fixes.addAll(libraryFixes(trace))
            "VIEWMODEL_LEAK" -> fixes.addAll(viewModelFixes(trace))
            "HANDLER_LEAK" -> fixes.addAll(handlerFixes(trace))
            else -> fixes.addAll(genericFixes(trace))
        }

        return fixes
    }

    private fun singletonFixes(trace: LeakTrace): List<String> {
        val leakingClass = trace.leakingClassName
        val singletonNode = trace.nodes.firstOrNull {
            it.referenceName.contains("Instance", ignoreCase = false) ||
                    it.referenceName.contains("mInstance", ignoreCase = false) ||
                    it.className.contains("singleton", ignoreCase = true)
        }
        val singletonClass = singletonNode?.className?.split(" ")?.firstOrNull() ?: "the singleton"

        return listOf(
            "SINGLETON HOLDING ACTIVITY REFERENCE",
            "The singleton '$singletonClass' is holding a reference to the destroyed '$leakingClass'.",
            "",
            "Fix steps:",
            "1. If the singleton stores a Context, change it to use context.getApplicationContext() instead of the raw Activity context.",
            "2. If the singleton stores a listener/callback that references an Activity, add an unregister method:",
            "   fun unregister() { listener = null; context = null }",
            "3. Call the unregister method from the Activity's onDestroy():",
            "   override fun onDestroy() { super.onDestroy(); ${singletonClass}.getInstance().unregister() }",
            "4. Consider using WeakReference if the singleton must hold an Activity reference temporarily."
        )
    }

    private fun listenerFixes(trace: LeakTrace): List<String> {
        val leakingClass = trace.leakingClassName
        val listenerNode = trace.nodes.firstOrNull {
            it.referenceName.contains("Listener", ignoreCase = true) ||
                    it.referenceName.contains("Callback", ignoreCase = true) ||
                    it.referenceName.contains("Observer", ignoreCase = true)
        }
        val listenerField = listenerNode?.referenceName ?: "the listener"

        return listOf(
            "LISTENER/CALLBACK NOT UNREGISTERED",
            "The field '$listenerField' still holds a reference to the destroyed '$leakingClass'.",
            "",
            "Fix steps:",
            "1. Null out the listener reference when the Activity/Fragment is destroyed:",
            "   override fun onDestroy() { super.onDestroy(); listenerHolder.setListener(null) }",
            "2. For Fragments, prefer onDestroyView() for view-related callbacks:",
            "   override fun onDestroyView() { super.onDestroyView(); adapter.setCallback(null) }",
            "3. If using an interface pattern, provide an explicit removeListener() or unregister() method.",
            "4. Consider using lifecycle-aware components (LifecycleObserver) that auto-cleanup."
        )
    }

    private fun contextFixes(trace: LeakTrace): List<String> {
        val leakingClass = trace.leakingClassName
        return listOf(
            "ACTIVITY CONTEXT STORED WHERE APPLICATION CONTEXT IS NEEDED",
            "An Activity context ('$leakingClass') is being held beyond the Activity lifecycle.",
            "",
            "Fix steps:",
            "1. Replace the Activity context with Application context wherever possible:",
            "   this.context = context.getApplicationContext()",
            "2. In singleton or long-lived object constructors, always call getApplicationContext():",
            "   fun init(context: Context) { this.appContext = context.applicationContext }",
            "3. Avoid passing 'this' from Activities to objects that outlive the Activity.",
            "4. Review all fields named 'context' or 'mContext' in the holding class."
        )
    }

    private fun libraryFixes(trace: LeakTrace): List<String> {
        val leakingClass = trace.leakingClassName
        val libraryNode = trace.nodes.firstOrNull { node ->
            listOf("com.google.", "com.facebook.", "com.crashlytics.", "com.bumptech.").any {
                node.className.contains(it, ignoreCase = true)
            }
        }
        val libraryClass = libraryNode?.className?.split(" ")?.firstOrNull() ?: "third-party library"

        return listOf(
            "THIRD-PARTY LIBRARY LEAK",
            "The library class '$libraryClass' is retaining the destroyed '$leakingClass'.",
            "",
            "Fix steps:",
            "1. Ensure the library API is initialized with Application context instead of Activity context:",
            "   LibrarySDK.init(applicationContext) instead of LibrarySDK.init(this)",
            "2. Check if the library provides a cleanup or teardown method and call it in onDestroy().",
            "3. Check the library's issue tracker for known memory leak reports.",
            "4. If no fix is available, consider wrapping the library call to limit context exposure.",
            "5. As a last resort, file an issue on the library's repository with the leak trace."
        )
    }

    private fun viewModelFixes(trace: LeakTrace): List<String> {
        val leakingClass = trace.leakingClassName
        return listOf(
            "VIEWMODEL RETAINING VIEW/ACTIVITY REFERENCE",
            "A ViewModel is holding a reference to '$leakingClass' which has been destroyed.",
            "",
            "Fix steps:",
            "1. Never store Activity, Fragment, or View references inside a ViewModel.",
            "2. Clear any references in the ViewModel's onCleared() callback:",
            "   override fun onCleared() { super.onCleared(); viewReference = null }",
            "3. Use LiveData or StateFlow to communicate from ViewModel to UI instead of direct references.",
            "4. If the ViewModel needs a Context, use AndroidViewModel which provides Application context."
        )
    }

    private fun handlerFixes(trace: LeakTrace): List<String> {
        val leakingClass = trace.leakingClassName
        return listOf(
            "HANDLER/MESSAGE LEAK",
            "A Handler or posted Message is retaining '$leakingClass' after destruction.",
            "",
            "Fix steps:",
            "1. Remove all pending callbacks and messages in onDestroy():",
            "   handler.removeCallbacksAndMessages(null)",
            "2. Use a static inner class for the Handler with a WeakReference to the Activity.",
            "3. Consider replacing Handler with coroutines or lifecycle-aware alternatives.",
            "4. Avoid posting delayed messages that might outlive the Activity."
        )
    }

    private fun genericFixes(trace: LeakTrace): List<String> {
        val leakingClass = trace.leakingClassName
        return listOf(
            "MEMORY LEAK DETECTED",
            "The object '$leakingClass' is being retained after it should have been garbage collected.",
            "",
            "General fix steps:",
            "1. Identify the reference marked with ~~~ in the leak trace — that is the likely cause.",
            "2. Ensure the holding object clears its reference when the leaked object is destroyed.",
            "3. Check for static fields, singletons, or long-lived objects that might hold Activity/Fragment refs.",
            "4. Review event bus registrations, broadcast receivers, and content observers for proper unregistration.",
            "5. Use LeakCanary's heap dump viewer for deeper analysis of the object graph."
        )
    }
}

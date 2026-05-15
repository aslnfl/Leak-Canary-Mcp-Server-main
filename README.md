# LeakCanary MCP Server

A standalone **Kotlin/JVM MCP server** that automates Android memory leak detection using [LeakCanary](https://square.github.io/leakcanary/) and `adb logcat`. It parses leak traces, classifies root causes, assigns priority levels, suggests fixes, and tracks leak history across sessions -- all exposed as MCP tools for use in **Cursor**, **VS Code**, and **Android Studio**.

---

## Prerequisites

- **Java 17+** installed and available on PATH
- **adb** installed (Android SDK Platform-Tools) -- the server auto-detects `adb` from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or common SDK install locations
- An Android device or emulator connected via USB/WiFi with **LeakCanary** integrated in the debug build

---

**Repository Link**: [https://github.com/ravisharma46/Leak-Canary-Mcp-Server.git](https://github.com/ravisharma46/Leak-Canary-Mcp-Server.git)

## Quick Install (Recommended)

The fastest and most universally compatible way to use the MCP server across **Windows, macOS, and Linux**. It requires zero path configuration.

Add this directly to your **`~/.cursor/mcp.json`**:

```json
{
  "mcpServers": {
    "leakcanary": {
      "command": "npx",
      "args": ["-y", "leakcanary-mcp-fix"]
    }
  }
}
```

Restart Cursor and the 12 tools will appear in the MCP tools panel.

---

## Setup in VS Code

Make sure you have [GitHub Copilot](https://marketplace.visualstudio.com/items?itemName=GitHub.copilot) extension installed with **Agent mode** enabled (VS Code 1.99+).

**Option A — Workspace level** (`.vscode/mcp.json` in your project root):

```json
{
  "servers": {
    "leakcanary": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "leakcanary-mcp-fix"]
    }
  }
}
```

**Option B — User level** (VS Code `settings.json`):

```json
{
  "mcp": {
    "servers": {
      "leakcanary": {
        "type": "stdio",
        "command": "npx",
        "args": ["-y", "leakcanary-mcp-fix"]
      }
    }
  }
}
```

Open **Copilot Chat → Agent mode** and the LeakCanary tools will be available.

---

## Setup in Android Studio

Android Studio supports MCP servers through the **Gemini** plugin (Android Studio Meerkat 2024.3+ or later).

1. Open **Settings → Tools → AI Assistant → MCP Servers**
2. Click **+ Add** and configure:

```json
{
  "mcpServers": {
    "leakcanary": {
      "command": "npx",
      "args": ["-y", "leakcanary-mcp-fix"]
    }
  }
}
```

Alternatively, add a `mcp.json` file in the `.idea` folder of your project:

```json
{
  "mcpServers": {
    "leakcanary": {
      "command": "npx",
      "args": ["-y", "leakcanary-mcp-fix"]
    }
  }
}
```

Restart Android Studio and the tools will appear under **Gemini → MCP Tools**.

> **Note:** All setups use `npx` to dynamically execute the wrapper. The wrapper auto-downloads and updates the server JAR for you on each run.

---

## Database-First Data Strategy

The server reads leak data **directly from the app's LeakCanary SQLite database** as the primary source, not from `adb logcat`. This means leak data is always available -- even after reboots, logcat buffer clears, or the next day.

### How it works

1. **Primary: App Database (BLOB deserialization)** -- Pulls the app's `leaks.db` to the host and deserializes the `HeapAnalysisSuccess` Java objects stored as BLOBs using the [shark](https://github.com/square/leakcanary/tree/main/shark) library. This provides the **full analysis** -- complete reference chains, retained sizes, leak status per node, GC roots, heap metadata, and device info. Identical quality to what you see in the LeakCanary app.
2. **Fallback: Logcat** -- If the database isn't accessible (app not debuggable, not installed, etc.), falls back to reading `adb logcat -s LeakCanary`.
3. **Auto-Discovery** -- When **no `package_name` is provided**, the server automatically scans all installed apps on the device to find ones with a LeakCanary database. If multiple apps are found, it prompts you to choose. If only one app is found, it reads leaks directly.

This means you can just say **"list all leaks"** without knowing any package names -- the server will find your LeakCanary-enabled apps and show you what's available.

---

## MCP Tools (12 total)

### Leak Detection & Analysis

| Tool | Description |
|------|-------------|
| `detect_leaks` | Capture LeakCanary logs, parse all leak traces, classify/prioritize, and return a full structured report. Falls back to app storage when logcat is empty. |
| `list_leaks` | List all detected leaks in a deduplicated compact summary with occurrence counts. Falls back to app storage when logcat is empty. |
| `search_leak` | Search for leaks by class name or keyword (e.g. "SearchHistoryManager"). Falls back to app storage when logcat is empty. |
| `analyze_leak` | Deep-dive a specific leak by its signature hash -- full reference chain, root cause, severity. Falls back to app storage when logcat is empty. |
| `suggest_fixes` | Generate actionable fix steps for a leak based on its pattern (singleton, listener, context, etc.). Falls back to app storage when logcat is empty. |

### Memory & Device Info

| Tool | Description |
|------|-------------|
| `get_heap_summary` | Extract heap-level statistics (class count, instance count, heap size, bitmap stats, device info) |
| `get_device_memory` | Get live memory stats for an app (PSS, Java Heap, Native Heap, Code, Stack, Graphics) via `dumpsys meminfo` |
| `list_devices` | List all connected Android devices and emulators with their connection state |

### History, Diff & Reporting

| Tool | Description |
|------|-------------|
| `get_leak_history` | Query persisted leak records across sessions with optional filters (signature, date, limit) |
| `leak_diff` | Compare current leaks against previous scans -- shows NEW, RECURRING, and RESOLVED leaks |
| `export_report` | Generate a shareable Markdown leak report with priority summary, reference chains, and history |
| `clear_leaks` | Clear the logcat buffer so the next scan only shows newly detected leaks |

---

## Usage Examples

Once the MCP server is active, you can ask the AI agent in natural language:

- **"Detect memory leaks on my connected device"** -- runs `detect_leaks`, returns a full prioritized report
- **"List all memory leaks"** -- runs `list_leaks`, returns a deduplicated summary with occurrence counts
- **"Check memory leak in SearchHistoryManager"** -- runs `search_leak`, filters leaks matching the class name
- **"Show me the heap summary"** -- runs `get_heap_summary`, returns heap stats
- **"How much memory is my app using?"** -- runs `get_device_memory`, returns live PSS/heap stats
- **"Analyze the leak with signature 42ac34ed..."** -- runs `analyze_leak`, returns a deep-dive of that specific leak
- **"How do I fix the leak with signature 48c887..."** -- runs `suggest_fixes`, returns actionable fix steps
- **"Show me leak history from the last week"** -- runs `get_leak_history` with a date filter
- **"What leaks are new since last scan?"** -- runs `leak_diff`, shows new/recurring/resolved leaks
- **"Export a leak report"** -- runs `export_report`, generates a Markdown file for sharing
- **"Clear leaks and start fresh"** -- runs `clear_leaks`, resets the logcat buffer
- **"What devices are connected?"** -- runs `list_devices`, shows all adb devices

### Auto-discovery & multi-app support

When you ask for leaks **without specifying an app**, the server automatically discovers all debuggable apps on the device that have a LeakCanary database:

```
No LeakCanary output found in logcat, but I found apps with LeakCanary
databases on the device.

Please specify which app to analyze by providing the 'package_name' parameter:
  1. com.myapp.debug (15 leaks)
  2. com.otherapp.debug (2 leaks)
  3. com.myapp.man.preprod (2 leaks)

Example: use package_name="com.myapp.debug" to see its leaks.
```

You can then specify the app:
- **"List leaks for com.myapp.debug"** -- reads stored leaks from that app's database
- **"Detect leaks for com.otherapp.debug"** -- analyzes leaks for the other app

If only **one** app has LeakCanary on the device, the server skips the prompt and shows leaks directly -- no extra parameters needed.

### Persistent leak reading

The logcat buffer clears over time (reboot, buffer rotation, next day). The server handles this transparently:

- **With `package_name`:** Reads leaks directly from the app's LeakCanary SQLite database
- **Without `package_name`:** Auto-discovers apps with LeakCanary and prompts you to choose

The database is pulled to the host and queried locally with `sqlite3`, so it works even on devices that don't have `sqlite3` installed. Requires the app to be a debug build (for `run-as` access).

### Multi-device support

If you have multiple devices connected, pass the `device_id` parameter:
- **"Detect leaks on device emulator-5554"** -- the tool will use `adb -s emulator-5554`
- **"What devices are connected?"** -- runs `list_devices` to see all available device IDs

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.2 / JVM 17 |
| MCP SDK | [io.modelcontextprotocol:kotlin-sdk:0.8.3](https://github.com/modelcontextprotocol/kotlin-sdk) |
| Leak Analysis | [shark:2.14](https://github.com/square/leakcanary/tree/main/shark) (LeakCanary's heap analysis library) |
| Serialization | kotlinx-serialization-json |
| Transport | STDIO (stdin/stdout JSON-RPC) |
| Build | Gradle Kotlin DSL + Shadow plugin (fat JAR) |
| History storage | Local JSON file (`~/.leakcanary-mcp/history.json`) |
| Report export | Markdown files (`~/.leakcanary-mcp/reports/`) |

---

## License

This project is for internal use at Todo App.

---

## Author

Developed by **Ravi Kumar**

🙏 If you like LeakCanary MCP Server you can show support by starring ⭐ this repository.

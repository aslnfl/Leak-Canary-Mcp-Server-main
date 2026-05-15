# Release Instructions

How to publish a new version of the LeakCanary MCP Server. Developers using the launcher script will auto-update on their next run.

---

## 1. Build the fat JAR

```bash
./gradlew shadowJar
```

This produces:
```
build/libs/leakcanary-mcp-server-all.jar
```

## 2. Create a GitHub release

Choose a semantic version tag (e.g. `v1.0.0`, `v1.1.0`, `v2.0.0`):

```bash
gh release create vX.X.X \
  --repo ravisharma46/Leak-Canary-Mcp-Server \
  --title "vX.X.X" \
  --notes "Release notes here" \
  build/libs/leakcanary-mcp-server-all.jar
```

This creates the release and uploads the JAR as a release asset in one step.

## 3. Publish to NPM (Optional but Recommended)

For users to get the latest version via the `npx` wrapper, update the `package.json` version to match your new GitHub release, then publish to NPM:

```bash
# Update version in package.json
npm version patch # or minor, or major

# Publish to NPM registry
npm publish --access public
```

---

## How auto-update works

The Node.js wrapper (`bin/index.js` published to NPM) polls the GitHub releases API on each run. If the GitHub tag has changed since the last download, it pulls the new JAR automatically entirely independently from NPM versioning. (Meaning even if you don't publish to NPM, the wrapper evaluates the latest GitHub release anyway).

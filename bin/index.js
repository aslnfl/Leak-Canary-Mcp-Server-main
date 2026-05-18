#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn, execSync } = require('child_process');
const https = require('https');

const REPO = "aslnfl/Leak-Canary-Mcp-Server";
const ASSET_NAME = "leakcanary-mcp-server-all.jar";
const INSTALL_DIR = path.join(os.homedir(), '.leakcanary-mcp');
const JAR_PATH = path.join(INSTALL_DIR, 'server.jar');
const VERSION_FILE = path.join(INSTALL_DIR, 'version');

// Helpers for logging specifically to stderr, to keep stdout entirely clean for JSON-RPC MCP
function logError(msg) { console.error(`\x1b[31mERROR: ${msg}\x1b[0m`); }
function logWarn(msg)  { console.error(`\x1b[33mWARN:  ${msg}\x1b[0m`); }
function logInfo(msg)  { console.error(`\x1b[32mINFO:  ${msg}\x1b[0m`); }

// 1. Initial Checks
try {
    execSync('java -version', { stdio: 'ignore' });
} catch (e) {
    logError("Java not found. Please install Java 17+ to use this MCP server.");
    process.exit(1);
}

try {
    execSync('adb version', { stdio: 'ignore' });
} catch (e) {
    logWarn("adb not found. Leak detection requires a connected Android device with adb.");
}

if (!fs.existsSync(INSTALL_DIR)) {
    fs.mkdirSync(INSTALL_DIR, { recursive: true });
}

// 2. Fetch and Download Engine
function fetchJson(url) {
    return new Promise((resolve, reject) => {
        const req = https.get(url, { headers: { 'User-Agent': 'NodeJS LeakCanary-MCP Installer' } }, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                return resolve(fetchJson(res.headers.location));
            }
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve(JSON.parse(data)));
        });
        req.on('error', reject);
    });
}

function downloadToFile(url, dest) {
    return new Promise((resolve, reject) => {
        const req = https.get(url, { headers: { 'User-Agent': 'NodeJS LeakCanary-MCP Installer', 'Accept': 'application/octet-stream' } }, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                return resolve(downloadToFile(res.headers.location, dest)); // follow redirect
            }
            if (res.statusCode !== 200) {
                return reject(new Error(`Download failed with status ${res.statusCode}`));
            }
            const file = fs.createWriteStream(dest);
            res.pipe(file);
            file.on('finish', () => { file.close(resolve); });
        });
        req.on('error', reject);
    });
}

async function downloadBinary() {
    try {
        const release = await fetchJson(`https://api.github.com/repos/${REPO}/releases/latest`);
        if (!release.tag_name) throw new Error("Could not fetch latest release format.");
        
        const latestVersion = release.tag_name;
        
        let currentVersion = "";
        if (fs.existsSync(VERSION_FILE)) {
            currentVersion = fs.readFileSync(VERSION_FILE, 'utf8').trim();
        }
        
        if (currentVersion !== latestVersion || !fs.existsSync(JAR_PATH)) {
            logInfo(currentVersion ? `Updating from ${currentVersion} to ${latestVersion} ...` : `First run — downloading LeakCanary MCP Server ${latestVersion} ...`);
            
            const asset = release.assets.find(a => a.name === ASSET_NAME);
            if (!asset) throw new Error(`Could not find ${ASSET_NAME} in release ${latestVersion}`);
            
            logInfo(`Downloading ${ASSET_NAME} ...`);
            
            const tempFile = path.join(INSTALL_DIR, `temp-${ASSET_NAME}`);
            await downloadToFile(asset.browser_download_url, tempFile);
            
            // Atomically replace JAR
            fs.renameSync(tempFile, JAR_PATH);
            fs.writeFileSync(VERSION_FILE, latestVersion);
            logInfo(`Downloaded ${latestVersion} successfully.`);
        }
    } catch (err) {
        if (!fs.existsSync(JAR_PATH)) {
            logError(`Failed to fetch and no cached JAR exists. ${err.message}`);
            process.exit(1);
        } else {
            // It's perfectly fine if offline or ratelimited, just use the cached fallback
            logWarn(`Update check failed. Using cached JAR. (${err.message})`);
        }
    }
}

async function startServer() {
    await downloadBinary();
    logInfo("LeakCanary MCP ready! Starting JSON-RPC transport...");
    
    // Spawn the loaded jar, inheriting only the necessary streams
    const child = spawn('java', ['-jar', JAR_PATH], { 
        stdio: 'inherit',
        env: process.env
    });
    
    child.on('error', (err) => {
        logError(`Failed to start Java process: ${err.message}`);
        process.exit(1);
    });
    child.on('exit', (code) => {
         process.exit(code || 0);
    });
}

startServer();

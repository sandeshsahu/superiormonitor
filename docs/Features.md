<h1 align="center">
  Features & Commands
</h1>

<p align="center">
  <strong>Capabilities, Telemetry & Fallback Mechanics</strong>
</p>

---

> [!NOTE]
> This document provides a comprehensive overview of the features, commands, and fallback mechanisms implemented within Superior Monitor.

---

## Security & Privacy

### 1.1. Persistent Enforcement

Evaluates the network state automatically upon device boot-up and actively monitors for manual state changes. This feature is collapsed by default in the UI and requires explicit activation.

- 📶 **Force Mobile Data**: Automatically re-enables mobile data if disabled (via root: `su -c svc data enable`).
- 📡 **Force WiFi**: Automatically re-enables Wi-Fi if disabled (via root: `su -c svc wifi enable`).
- 🌐 **Force Hotspot**: Automatically re-enables the Mobile Hotspot if disabled (via advanced Java Proxy Reflection into hidden `TetheringManager` APIs, bypassing standard user prompts).
- 🔄 **Boot-Time Evaluation**: Upon device reboot, the enforcer evaluates all active toggles and re-applies them. If any toggle fails to apply (due to OEM restrictions), it is automatically disabled to prevent boot loops or crashes.

> [!WARNING]
> **Compatibility Notice**: The Persistent Enforcement feature involves complex manipulation of restricted internal Android APIs. It was engineered and verified on a Realme device running Android 11. Due to fragmented OEM modifications across the Android ecosystem, this feature **may not work universally** and could cause unexpected behavior, UI crashes, or soft reboots on other devices. The application includes built-in fail-safes that automatically disable failing toggles upon boot.

---

### 1.2. Security Snapshots

Periodically captures media based on configured intervals (e.g., `1 min`, `5 min`, `1 hour`) and securely forwards them to Telegram. To maximize battery life and prevent blank images, **all scheduled captures** (screen, front, rear) and **on-demand screenshots** are automatically skipped if the device screen is locked or turned off. However, **on-demand background camera captures** (front/rear) actively bypass the lock screen to capture the device's surroundings at any time.

- 🖼️ **Screenshots**: Captures a high-resolution image of the current screen.
- 🤳 **Front Shots**: Captures a photo using the front-facing camera.
- 📷 **Rear Shots**: Captures a photo using the rear-facing camera.

---

### 1.3. Basic Updates

Identifies live telephony events and forwards logs and recordings to Telegram.

- 🎙️ **Call Recording**: Utilizes the integrated BCR engine to record calls and forward the audio files (`.opus`/`.m4a`) to Telegram. Upon successful upload, files are retained locally in a hidden permanent directory.
- ☎️ **Call Events**: Identifies incoming, outgoing, and missed call events, forwarding a chat log containing the contact name, number, call type, and timestamp.
- ✉️ **SMS Events**: Identifies incoming and outgoing SMS messages, forwarding a chat log containing the contact name, number, message body, carrier (SIM) name, and timestamp.
- 📦 **App Activity**: Automatically detects when applications are installed or uninstalled on the device and forwards an alert to Telegram containing the app name and package name.
- ⌨️ **Key Events**: Captures key presses per-app using an Accessibility Service. Batches keystrokes and forwards them on a scheduled interval via `AlarmManager`.
- 🌊 **Flood Protection (Batching)**: A live-text batching engine actively protects your Telegram chat from spam. If a burst of notifications occurs (4+ messages in 3 seconds), they are automatically bundled into a single `.txt` document instead of flooding the chat. 

---

### 1.4. Social Updates

Monitors live social messaging applications without relying on notifications, utilizing root-level database extraction.

- 🟢 **WhatsApp**: Intercepts incoming and outgoing WhatsApp messages via `stat` polling. Extracts the live `msgstore.db` to internal app storage (bypassing Android 11+ scoped storage isolation) and safely handles WAL checkpoints using `OPEN_READWRITE` and `chown`. Dynamically switches between modern (`jid_map`) and legacy SQL schemas for maximum compatibility.
- 💼 **WhatsApp Business**: Powered by the exact same engine as standard WhatsApp via a unified `WhatsAppVariant` system. Targets the WA Business application database natively.
- 📘 **Instagram**: Intercepts direct messages via lightweight SQLite polling of `direct.db`. Natively handles mixed media types (BLOBs vs Strings), and prevents group-chat duplicates via timestamp baselining. 

> [!NOTE]
> All social monitors instantly suspend polling when offline to save battery, and rely on an **event-driven baseline**. Upon startup, they only process *new* events occurring after boot, completely preventing massive historical message dumps and API limit breaches.

---

### 1.5. Fetch Operations & Popups

On-demand data extraction and physical device interaction triggered via Telegram.

- 📇 **Contacts & Call Logs**: Allows the owner to remotely fetch the latest device contacts or a specific number (3, 5, 10, 15) of recent call history records.
- 💬 **Remote Popups**: Allows the owner to send custom text alerts that immediately pop up on the physical device screen to warn or notify the user.

---

### 1.6. Launcher Stealth & Visibility

Controlled via the application's Settings screen or remotely via Telegram commands.

- 👁️ **Hide/Unhide Launcher Icon**: Toggles the visibility of the Superior Monitor application icon in the device's app drawer.
- 🤫 **Secret Access**: If the icon is hidden, the app can be launched via the secret dialer code `*#*#677#*#*`, or remotely toggled via the `/settings` Telegram command.
- 🙈 **Camouflage Mode (Wi-Fi)**: If the launcher icon cannot be completely hidden due to OEM system restrictions, the application disguises itself using a standard Wi-Fi icon and name. Tapping it seamlessly redirects the user to the native Android Wi-Fi settings.

---

### 1.7. Recorder Settings (BCR Integration)

These features are modified and integrated from the open-source [Basic Call Recorder (BCR)](https://github.com/chenxiaolong/BCR).

- 🎛️ **Audio Source & Quality**: Configurable sources (Voice Call, Voice Uplink + Downlink, etc.), encoding formats (`Ogg/Opus`, `M4A`, `FLAC`), sampling rates, and bitrates.
- 📱 **Record Telecom Apps**: Supports recording calls from third-party VoIP apps integrated with the system telecom manager (device compatibility varies).
- ⏱️ **Record Dialing State**: Initiates recording as soon as a call begins dialing, even prior to connection.

---

## 2. Telegram Bot Commands

The device can be controlled remotely via the following Telegram commands:

| Command | Description |
|:---:|:---|
| `/start` | Initializes the bot, sends a welcome message, and opens the main dashboard keyboard. (Note: Live device telemetry is retrieved via the `System Check` button on this keyboard). |
| `/menu` | Opens the remote dashboard with 4 core control menus. *Automatically deletes itself after 2 minutes of inactivity to hide traces.* (See details below) |
| `/settings` | Accesses remote application settings that perfectly mirror the physical device's dashboard. *Toggles update the device in real-time.* (See details below) |

### `/menu` Details
- 💬 **Send Message**: Send a custom text popup directly to the physical device screen.
- 📸 **Snapshot Engine**: Manually capture media via 4 options (Get Screenshot, Get Rear shot, Get Front shot, Back to menu). *Screenshots are skipped if the device is locked.*
- 🎙️ **Media Ops**: Contains the **Microphone** feature, allowing you to record on-demand background audio for selectable durations (`1min`, `3min`, `5min`, `10min`).
- 📇 **Fetch Data**: Retrieve the latest device contacts, or fetch recent call activity logs (selectable limits: `3`, `5`, `10`, `15`).

### `/settings` Details
- 👁️ **Launcher**: Remotely **Open Application** on the device, **Hide Icon**, or **Unhide Icon**.
- ⏱️ **Snapshot Settings**: Toggle front/rear camera and screenshot intervals directly from Telegram.
- 📶 **Persistent Enforcement**: Remotely toggle Wi-Fi, Data, and Hotspot enforcement.
- ⚙️ **Basic Updates**: Remotely toggle Call Events, SMS Events, and 
     1. 🎙️ **Recorder Settings**: Configure BCR call recording parameters remotely.
     2. ⌨️ **Key Events**: configure keyevent upload interval remotely

- 💬 **Social Updates**: Remotely toggle WhatsApp, WA Business, and Instagram monitoring.

---

## 3. Upcoming Features

*These features are currently under consideration for future releases.*

### 3.1. Basic Updates
- 📷 **Media Events**: A new toggle within Basic Updates to instantly forward newly taken photos.
- 🔔 **Notification Events**: A new toggle within Basic Updates for forwarding real-time push notifications.

### 3.2. Social Updates
- 👻 **Snapchat**: Support for intercepting real-time Snapchat messages.

### 3.3. Bot Commands
- 💬 **WhatsApp Export**: Add a new option in the `/menu` command for exporting the complete chat history of a single conversation.
- 📸 **Cam Record**: Add a new option under `/menu` → `Media Ops` for recording video from the front or rear camera for a specified duration.

---

## 4. Advanced Fallback & Offline Mechanics

Superior Monitor is engineered to handle intermittent network connectivity gracefully. Data is only transmitted when the device is online. During offline periods, data is safely cached in unified local `offline/` directories.

### 4.1. Offline Queuing

- 📸 **Routine Media**: Snapshots and camera shots are securely saved locally into `captures/screen/`, `captures/front/`, or `captures/rear/` offline directories.
- 📝 **Text Logs**: Calls, SMS, WhatsApp, and Instagram messages are appended sequentially to persistent text files (e.g., `offline_calls.txt`).
- 📇 **FetchOps Data**: On-demand fetched contacts and call history are securely cached if the network drops during extraction.
- 🎙️ **Call Recordings**: Stored securely in offline folders until the network is available.
- 🎤 **On-Demand Voice Recording**: If an admin manually requests a live microphone recording and the upload fails, the audio safely drops into the offline queue. (On-demand live screen captures intentionally bypass queues and self-delete).

### 4.2. Recovery & Trickle-Sync Strategy

Upon network restoration, the system validates DNS reachability and Telegram API stability before initiating the automated trickle-sync process:

1. ⚡ **Lightweight Logs**: Text logs (SMS, calls, WhatsApp, FetchOps) are evaluated and uploaded sequentially. Upon successful upload, local caches are permanently deleted.
2. ⏳ **Sequential Audio**: Heavy files (Call recordings, Microphone) are strictly decoupled from zip batching. They upload sequentially one-by-one with intentional 2-second delays to prevent Telegram API rate limits (`HTTP 429`).
3. 📦 **Batched Snapshots**: If the snapshot queue contains **more than 3 items**, the backend natively compresses them into a single `.zip` file for bulk upload. If successful, the original snapshots and zip are deleted.
4. 🗄️ **Zero-Loss Cleanup**: If any upload attempt fails due to connection drops or API rejection, the engine gracefully aborts deletion, securely retaining the file for the next sync attempt.

---

<p align="center">
  <sub>Built with ❤️ by <a href="https://gitlab.com/sandeshsahu">@sandeshsahu</a></sub>
</p>
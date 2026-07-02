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

##  Security & Privacy

- 🔐 **Persistent Local Storage**: Sensitive data such as your Telegram Bot token and Chat ID are stored purely on the device's persistent local storage. No credentials ever leave the device except through the Telegram Bot API.
- 📡 **No Data Interception**: The application utilizes the industry-standard `OkHttp3` client to communicate securely and directly with the Telegram Bot API over HTTPS, ensuring there is no middleman data interception.
- 🛡️ **Bot Intrusion Defense System**: Provides active security against unauthorized Telegram users attempting to hijack the bot.
    - 🚷 **3-Strikes Direct Message Defense**: Automatically monitors unauthorized DM attempts. Warns the user twice before permanently blocking them and ignoring all future requests.
    - 🚪 **Auto-Leave Groups**: Automatically detects if the bot is added to an unauthorized group chat. Instantly leaves the chat to prevent spam abuse and Telegram API rate-limiting.
    - 📝 **Intrusion Logging**: Writes persistent logs of all unauthorized access attempts to local storage (`access.log`), capturing the intruder's User ID, Username, and Name.
    - 🚨 **Owner Alerting**: Sends real-time alerts to the authorized chat owner whenever an intrusion attempt occurs.

---

##  1. Core Features

###  1.1. Persistent Enforcement

Evaluates the network state automatically upon device boot-up and actively monitors for manual state changes. This feature is collapsed by default in the UI and requires explicit activation.

- 📶 **Force Mobile Data**: Automatically re-enables mobile data if disabled (via root: `su -c svc data enable`).
- 📡 **Force WiFi**: Automatically re-enables Wi-Fi if disabled (via root: `su -c svc wifi enable`).
- 🌐 **Force Hotspot**: Automatically re-enables the Mobile Hotspot if disabled (via advanced Java Proxy Reflection into hidden `TetheringManager` APIs, bypassing standard user prompts).
- 🔄 **Boot-Time Evaluation**: Upon device reboot, the enforcer evaluates all active toggles and re-applies them. If any toggle fails to apply (due to OEM restrictions), it is automatically disabled to prevent boot loops or crashes.

> [!WARNING]
> **Compatibility Notice**: The Persistent Enforcement feature involves complex manipulation of restricted internal Android APIs. It was engineered and verified on a Realme device running Android 11. Due to fragmented OEM modifications across the Android ecosystem, this feature **may not work universally** and could cause unexpected behavior, UI crashes, or soft reboots on other devices. The application includes built-in fail-safes that automatically disable failing toggles upon boot.

---

### 1.2. Security Snapshots

Periodically captures media based on configured intervals (e.g., `1 min`, `5 min`, `1 hour`) and securely forwards them to Telegram. To maximize battery life and prevent blank images, scheduled screen captures and scheduled front,rear shots are automatically skipped if the device screen is locked or turned off. Manual front and rear camera captures using `/menu` command are still allowed in this state.

- 🖼️ **Screenshots**: Captures a high-resolution image of the current screen.
- 🤳 **Front Shots**: Captures a photo using the front-facing camera.
- 📷 **Rear Shots**: Captures a photo using the rear-facing camera.

---

### 1.3. Basic Updates (Telephony)

Identifies live telephony events and forwards logs and recordings to Telegram.

- 🎙️ **Call Recording**: Utilizes the integrated BCR engine to silently record calls and forward the audio files (`.opus`/`.m4a`) to Telegram.
- ☎️ **Call Events**: Identifies incoming, outgoing, and missed call events, forwarding a chat log containing the contact name, number, call type, and timestamp.
- ✉️ **SMS Events**: Identifies incoming and outgoing SMS messages, forwarding a chat log containing the contact name, number, message body, carrier (SIM) name, and timestamp.

---

### 1.4. Social Updates

Monitors live social messaging applications without relying on notifications.

- 🟢 **WhatsApp**: Intercepts incoming and outgoing WhatsApp messages via root-level SQLite extraction, forwarding them to Telegram along with the contact name, message type, timestamp, and content. Automatically suspends polling to save battery when offline.
- 📘 **Instagram**: Intercepts direct messages via lightweight SQLite polling (`direct.db`), natively handling media types (BLOBs), deduplication, and parsing User IDs. Suspends polling when offline.

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
| `/start` | Initializes the bot, sends a welcome message, and opens the main dashboard keyboard. (Note: Live device telemetry is actually retrieved via the `System Check` button on this keyboard, not the start command itself). |
| `/menu` | Opens the remote dashboard to toggle real-time settings, fetch on-demand contacts/logs, send custom popups, and capture manual media. |
| `/settings` | Accesses remote application settings to remotely enable or disable specific features, including hiding/unhiding the launcher icon or launching the app directly on the device. |

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

Superior Monitor is engineered to handle intermittent network connectivity gracefully. Data is only transmitted when the device is online. During offline periods, data is safely cached in local `offline/` directories.

### 4.1. Offline Queuing

- 📸 **Routine Media**: Snapshots and camera shots are securely saved locally when offline.
- 📝 **Text Logs**: Calls, SMS, WhatsApp, and Instagram messages are appended sequentially to persistent text files (e.g., `offline_calls.txt`).
- 📇 **FetchOps Data**: On-demand fetched contacts and call history are securely cached if the network drops during extraction.
- 🎙️ **Call Recordings**: Stored securely in offline folders until network is available.
- 🎤 **On-Demand Voice Recording**: If a live microphone recording is active and a phone call is initiated/received, the recording gracefully pauses or stops to avoid audio collision.

### 4.2. Recovery & Trickle-Sync Strategy

Upon network restoration, `BotService` validates DNS reachability and Telegram API stability before initiating the automated path-based trickle-sync process:

1. ⚡ **Lightweight Logs**: Text logs (SMS, calls, WhatsApp, FetchOps) are evaluated and uploaded sequentially. Upon successful upload, local caches are permanently deleted.
2. ⏳ **Sequential Audio**: Heavy files (Call recordings, Microphone) are strictly decoupled from zip batching. They are uploaded sequentially one-by-one with intentional 2-second delays to prevent Telegram API rate limits (`HTTP 429`).
3. 📦 **Batched Snapshots**: If the snapshot queue contains more than 4 items, the backend natively compresses them into a single `.zip` file for bulk upload. If successful, the original snapshots and zip are deleted.
4. 🗄️ **Zero-Loss Cleanup**: If any upload attempt fails due to connection drops, the engine gracefully aborts deletion, securely retaining the file in the `offline/` folder for the next sync attempt.

---

<p align="center">
  <sub>Built with ❤️ by <a href="https://github.com/sandeshsahu1">@sandeshsahu1</a></sub>
</p>
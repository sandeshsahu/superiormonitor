<h1 align="center">
  System Architecture
</h1>

<p align="center">
  <strong>Internal Mechanics, Component Structure & Integrations</strong>
</p>

---

> [!NOTE]
> This document provides a comprehensive overview of the internal mechanisms, component structure, and architectural patterns implemented within the Superior Monitor system.

---

## 1. High-Level Architecture

```mermaid
graph TD
    A[UI Layer] --> B[MainViewModel]
    B --> C[BotService]
    C --> D[Monitor Subsystems]
    C --> E[TelegramApi]
    D --> F[CallMonitor / SmsMonitor]
    D --> G[WhatsAppMonitor]
    D --> H[SnapshotEngine / BackgroundCamera]
    D --> I[MediaOperations]
    D --> J[NetworkEnforcer]
    C --> K[System Hooks]
```

---

## 2. Core Directory Structure

```
app/src/main/java/com/system/superiormonitor/
├── bot/                    # Telegram C2 orchestration layer
│   ├── BotService.kt       # Central foreground service
│   ├── BotCommands.kt      # Command parser & routing
│   ├── BotActions.kt       # Side-effects & offline queue
│   ├── BotMessages.kt      # Unified text strings & markups
│   └── TelegramApi.kt      # Networking singleton
├── monitor/                # Background data extraction & subsystems
│   ├── CallMonitor.kt      # Telephony event interception
│   ├── SmsMonitor.kt       # SMS interception (incoming + outgoing)
│   ├── WhatsAppMonitor.kt  # Root-level database decryption
│   ├── MediaOperations.kt  # On-demand media captures & mic recording
│   ├── SnapshotEngine.kt   # Scheduled snapshots via AlarmManager
│   ├── BackgroundCamera.kt # Silent camera captures
│   └── NetworkEnforcer.kt  # Persistent Wi-Fi/Data/Hotspot enforcement
├── ui/                     # Jetpack Compose presentation layer
│   ├── AppScreen.kt        # Navigation scaffold & drawer
│   ├── DashboardScreen.kt  # Main dashboard with feature toggles
│   ├── PermissionsScreen.kt # Centralized permission management
│   ├── SettingsScreen.kt   # Bot credentials & launcher visibility
│   ├── BCRSettingsScreen.kt # Call recorder configuration
│   ├── LogsScreen.kt       # Real-time log viewer
│   ├── Components.kt       # Reusable UI components
│   ├── MainViewModel.kt    # MVVM ViewModel & StateFlow management
│   └── CamouflageActivity.kt # Disguised entry point
├── data/                   # Persistence & models
│   ├── PrefsManager.kt     # SharedPreferences with Kotlin delegates
│   └── TelegramModels.kt   # Telegram API data classes
├── theme/                  # Design system
│   └── Theme.kt            # Material 3 color tokens & typography
├── util/                   # Utilities & telemetry
│   ├── LogManager.kt       # Centralized logging with StateFlow
│   └── TelemetryCollector.kt # Hardware & network metrics
├── receiver/               # System broadcast receivers
│   ├── BootReceiver.kt     # ACTION_BOOT_COMPLETED handler
│   ├── DialerCodeReceiver.kt # Secret dialer code interceptor
│   └── MonitorDeviceAdminReceiver.kt # Device Administrator receiver
├── service/                # System services
│   ├── MonitorAccessibilityService.kt # Accessibility service hook
│   └── MonitorNotificationListenerService.kt # Notification listener
├── MainActivity.kt         # Entry point & Compose host
└── SuperiorMonitorApp.kt   # Application class
```

---

## 3. Component Details

### 3.1. `bot/` — Telegram Orchestration Layer

Handles all real-time communication between the device and the Telegram Bot API.

| File | Responsibility |
|:---|:---|
| **`BotService.kt`** | **The Lifecycle Manager** — Lightweight foreground service. Manages the Telegram polling loop, starts background monitors, and listens for network changes. |
| **`BotCommands.kt`** | **The Router** — Parses raw Telegram updates and routes commands/callbacks. Fully decoupled from string building and side-effects. |
| **`BotActions.kt`** | **The Controller** — Single source of truth for actions and side-effects. Centralizes the offline queue (`sendOrQueue()`), authorization/security (`handleUnauthorizedAccess()`), and device control. |
| **`BotMessages.kt`** | **The View / Text Dictionary** — Single source of truth for all text. Contains ALL user-facing strings, message templates, captions, and Telegram Inline Keyboard Markups (`JSON_MARKUP`). |
| **`TelegramApi.kt`** | **Networking Singleton** — Uses `HttpURLConnection` for all Telegram API requests. Contains API reachability validation, markdown escaping, and file upload logic. |

---

### 3.2. `monitor/` — Background Data Extraction

Responsible for gathering telemetry, media, and intercepting device events.

| File | Responsibility |
|:---|:---|
| **`CallMonitor.kt`** | Hooks into the Android Telephony framework to capture call events. Uses `BotActions.sendOrQueue()` for offline resilience and `BotMessages` for strings. |
| **`SmsMonitor.kt`** | Monitors incoming/outgoing SMS traffic. Captures messages with debounce + mutex locking, delegating offline handling to `BotActions`. |
| **`WhatsAppMonitor.kt`** | Uses root (`su`) to continuously poll and decrypt `msgstore.db`. Implements stat-polling, and relies on `BotActions` for robust offline logging. |
| **`MediaOperations.kt`** | Handles on-demand media captures and duration-based mic recordings. Fully decoupled, it uses `BotMessages` for formatting captions. |
| **`SnapshotEngine.kt`** | Orchestrates scheduled snapshots using `AlarmManager` with `setExactAndAllowWhileIdle()` and automatic fallback to inexact alarms on Android 14+. Also contains the `SnapshotScheduler` class for scheduling management. |
| **`BackgroundCamera.kt`** | Handles silent camera captures using `CameraManager` with thread-safe filename separation to prevent file corruption during simultaneous front/rear captures. |
| **`NetworkEnforcer.kt`** | Persistent network enforcement module. Evaluates connectivity state on boot and monitors for changes. Re-enables Wi-Fi, Mobile Data, or Hotspot via root commands and Java Proxy Reflection into `TetheringManager`. Includes built-in fail-safes that auto-disable failing toggles. |

> [!WARNING]
> **Experimental Feature**: The `NetworkEnforcer` heavily manipulates restricted internal Android APIs (e.g., dynamic proxy generation for `TetheringManager` callbacks). This feature was engineered and verified on a Realme device running Android 11. Due to OEM customizations and varying Android versions, this capability **may not work universally** and could cause unexpected behavior, system UI crashes, or soft reboots. The enforcer includes automatic fail-safe logic to disable problematic toggles on boot failure.

---

### 3.3. `ui/` — Jetpack Compose Presentation Layer

Provides a modern, visually cohesive UI using Jetpack Compose and Material 3.

| File | Responsibility |
|:---|:---|
| **`AppScreen.kt`** | The main navigation scaffold with a custom `ModalNavigationDrawer`, persistent top bar, and animated screen transitions between all pages. |
| **`DashboardScreen.kt`** | The primary dashboard displaying all feature toggles: Service Status, Persistent Enforcement (collapsible), Security Snapshots, Basic Updates, and Social Updates. |
| **`PermissionsScreen.kt`** | Centralized permission management UI showing the grant status of all required permissions (Write Secure Settings, Device Admin, Accessibility, Notification Listener, and standard Android permissions). |
| **`SettingsScreen.kt`** | Bot credential configuration (Token & Chat ID), launcher visibility toggle with confirmation dialogs, system checks, and About section. |
| **`BCRSettingsScreen.kt`** | Call recorder settings: audio source, encoding format, sampling rate, bitrate, and recording behavior configuration. |
| **`LogsScreen.kt`** | Real-time log viewer powered by `LogManager`'s `StateFlow`, with log clearing functionality and category-based display. |
| **`Components.kt`** | Reusable UI components: `OuterCard`, `InnerListHost`, `TactileSwitch`, `SectionTitle`, and other styled building blocks. |
| **`MainViewModel.kt`** | MVVM ViewModel managing all UI state via `StateFlow`. Handles permission checks, feature toggle persistence, service lifecycle coordination, and state restoration. |
| **`CamouflageActivity.kt`** | A disguised entry point used when the main launcher icon is hidden. Redirects to native Wi-Fi settings to maintain the camouflage. |

---

### 3.4. `data/` — Persistence & Models

| File | Responsibility |
|:---|:---|
| **`PrefsManager.kt`** | Centralized `SharedPreferences` manager using Kotlin property delegates (`StringPref`, `BooleanPref`) for clean, type-safe configuration persistence. |
| **`TelegramModels.kt`** | Kotlin data classes representing the Telegram Bot API schema (`UpdateResponse`, `Message`, `CallbackQuery`, etc.). |

---

### 3.5. `theme/` — Design System

| File | Responsibility |
|:---|:---|
| **`Theme.kt`** | Defines the complete Material 3 design system: custom color tokens (dark theme), typography scales, and the `SuperiorMonitorTheme` composable wrapper. |

---

### 3.6. `util/` — Utilities & Telemetry

| File | Responsibility |
|:---|:---|
| **`LogManager.kt`** | Centralized logging utility using a Kotlin `StateFlow` to push real-time internal logs directly to the Compose UI. Supports categorized log entries. |
| **`TelemetryCollector.kt`** | Gathers live system metrics: battery health, CPU frequency, thermal zones, network signal strength (RSRP), and connectivity stats. |

---

### 3.7. `receiver/` & `service/` — System Hooks

| File | Responsibility |
|:---|:---|
| **`BootReceiver.kt`** | Listens for `ACTION_BOOT_COMPLETED` to restart the background service and trigger `NetworkEnforcer` evaluation after a device reboot. |
| **`DialerCodeReceiver.kt`** | Intercepts the secret dialer code (`*#*#677#*#*`) to unhide the application icon or launch the interface when hidden. |
| **`MonitorDeviceAdminReceiver.kt`** | Device Administrator receiver enabling advanced device management capabilities. |
| **`MonitorAccessibilityService.kt`** | Accessibility service hook for screen capture and advanced interaction capabilities. |
| **`MonitorNotificationListenerService.kt`** | Notification listener service for capturing and forwarding push notifications. |

---

## 4. Basic Call Recorder (BCR) Integration

The core call recording engine is integrated directly from the open-source [Basic Call Recorder (BCR)](https://github.com/chenxiaolong/BCR) repository.

> **Credits**: A huge thanks to [chenxiaolong](https://github.com/chenxiaolong) for developing the original BCR project, which provides the robust native call recording foundation used here.

- **Workflow**: When a call is recorded, BCR routes the output `.opus`/`.m4a` file. Superior Monitor intercepts this via an intent broadcast (`ACTION_UPLOAD_RECORDING`) in `BotService`, which then instantly uploads the recording to Telegram before moving it to persistent storage.

---

## 5. Advanced Fallback & Offline Queue Mechanics

Superior Monitor handles intermittent network connectivity gracefully through a multi-phase strategy:

| Phase | Strategy |
|:---|:---|
| **Detection** | `BotService` uses `ConnectivityManager.NetworkCallback` to detect outages. |
| **Queuing** | Media goes to `offline/` hierarchy; text logs append to persistent files. |
| **Recovery** | Verification of DNS and Telegram API health before re-establishing sync. |
| **Trickle-Sync** | Sequential uploads with rate-limit mitigation (`HTTP 429`). |

### Recovery Sequence

1. **Detection**: `BotService` actively monitors network state via `ConnectivityManager.NetworkCallback`. When offline, the polling loop enters deep sleep.
2. **Offline Queuing**:
   - Media (snapshots, camera shots) are routed to a structured `offline/` folder hierarchy.
   - Text logs (calls, SMS, WhatsApp) are appended to persistent text files (e.g., `offline_calls.txt`).
   - Call recordings (BCR) are routed to offline folders.
3. **Recovery Sequence**: Upon network restoration, `BotService` ensures DNS reachability and Telegram API stability before initiating a trickle-sync.
4. **Trickle-Sync Strategy**:
   - Lightweight text logs are merged and uploaded immediately.
   - Media files like call recordings are uploaded immediately when a connection is established. Scheduled snapshots prompt the owner for permission to upload or cancel. If accepted, they are synced sequentially with intentional delays to prevent API rate limits (`HTTP 429`).
   - Once successfully synced, offline files are moved to `permanent/` storage and text logs (SMS, calls, WhatsApp) are permanently deleted.

---

<p align="center">
  <sub>Built with ❤️ by <a href="https://github.com/sandeshsahu1">@sandeshsahu1</a></sub>
</p>
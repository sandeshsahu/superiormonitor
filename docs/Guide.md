<h1 align="center">
  Installation & Configuration Guide
</h1>

<p align="center">
  <strong>Installing, Building & Configuring SuperiorMonitor</strong>
</p>

---

## 🙋🏻‍♂️ User Installation

### Prerequisites
- A rooted Android device (Magisk, KernelSU, APatch, or similar)
- A Telegram Bot token (create one via  [@BotFather](https://t.me/BotFather))
- User and Chat ID (get them from [ MissRose_bot ](https://t.me/MissRose_bot) using `/id` command)

### Step 1 — Download the Root Module
1. **Download** the latest Module from [Github Releases](https://github.com/sandeshsahu/superiormonitor/releases)
2. **Extract** the .apk file from Module directory `/system/priv-app/SuperiorMonitor/`
3. Keep it alongside Root module

### Step 2 — Flash the Module
 
1. **Flash** the Downloaded Module (`SuperiorMonitor-MagiskModule.zip`) using your root manager:
   - **Magisk** → Modules → Install from storage
   - **KernelSU** → Module → Install from storage
   - **APatch** → Module → Install from storage
2. **Reboot** your device
3. **Open** the extracted `SuperiorMonitor.apk` and Install again the app (it will ask for update the app) 
4. **Configure** your **Bot Token**, **Chat ID**, and **Owner User ID** in the Settings screen
5. **Grant** all requested permissions and start the service

> [!WARNING]
> **Compliance Warning**: It is your strict responsibility to comply with all local, state, and federal regulations regarding privacy, surveillance, and call recording in your jurisdiction before using this software. The creator is not responsible for any misuse of this application.

> [!WARNING]
**Google Play Protect**: While installing `.apk` manually, you may see Play Protect Warning/Installation blocked. Our application is not meant to bypass any security measures, if you still want to install it's up to you.

> [!WARNING]
> **KernelSU users**: You may need the [OverlayFS MetaModule](https://github.com/KernelSU-Modules-Repo/meta-overlayfs) installed for system partition modifications to work correctly. Without it, the module may fail to place the app in `/system/priv-app`.

> [!IMPORTANT]
> This app **requires flashing as a root module** to work. Installing the APK directly will not grant the required system-level privileges (priv-app, root access). Always flash the module for installation.

> [!NOTE]
> If you experience issues after flashing (crashes, permission errors), extract the `.apk` from inside the module ZIP and install it manually as a fallback. See [Notes & Disclaimers](docs/Notes.md) for more details.

---

## 🏗️ Building from Source

### Prerequisites
- Android Studio Ladybug or later
- JDK 21+
- Python 3.x (for module generation)
- A rooted Android device (Magisk, KernelSU, APatch, or similar)
- A Telegram Bot token (create one via  [@BotFather](https://t.me/BotFather))
- User and Chat ID (get them from [ MissRose_bot ](https://t.me/MissRose_bot) using `/id` command)


### Step 1 — Build the APK

```bash
# Clone the repository
git clone https://github.com/sandeshsahu/superiormonitor.git
cd SuperiorMonitor

# Build the debug APK
./gradlew assembleDebug
```

### Step 2 — Generate the Root Module

The app must be installed as a **privileged system app** via a root module to function correctly. The included Python script packages the built APK into a flashable module ZIP compatible with Magisk, KernelSU, APatch, and other root managers.

```bash
# Generate the root module (requires the APK from Step 1)
python ModuleBuilder/generate.py --debug
```

This will output `ModuleBuilder/SuperiorMonitor-MagiskModule_Debug.zip`.

### Step 3 — Flash & Configure

1. Transfer `SuperiorMonitor-MagiskModule_Debug.zip` to your device
2. Flash the module using your root manager:
   - **Magisk** → Modules → Install from storage
   - **KernelSU** → Module → Install from storage
   - **APatch** → Module → Install from storage
3. **Reboot** your device
4. Open Superior Monitor and configure your **Bot Token**, **Chat ID**, and **Owner User ID** in the Settings screen
5. Grant all requested permissions and start the service

> [!IMPORTANT]
> This app **requires flashing as a root module** to work. Installing the APK directly will not grant the required system-level privileges (priv-app, root access). Always flash the module for installation.

> [!WARNING]
> **KernelSU users**: You may need the [OverlayFS MetaModule](https://github.com/KernelSU-Modules-Repo/meta-overlayfs) installed for system partition modifications to work correctly. Without it, the module may fail to place the app in `/system/priv-app`.

> [!NOTE]
> If you experience issues after flashing (crashes, permission errors), extract the `.apk` from inside the module ZIP and install it manually as a fallback. See [Notes & Disclaimers](docs/Notes.md) for more details.

---

<p align="center">
  <sub>Built with ❤️ by <a href="https://gitlab.com/sandeshsahu">@sandeshsahu</a></sub>
</p>
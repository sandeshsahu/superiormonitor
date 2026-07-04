<h1 align="center">
  Notes & Disclaimers
</h1>

<p align="center">
  <strong>Important Disclaimers, Known Limitations & Legal Warnings</strong>
</p>

---

> [!CAUTION]
> Please read this document carefully before utilizing the Superior Monitor application.

---

## 1. Development Background

This application was entirely developed with the assistance of Google's Antigravity AI. I am not a professional software developer and do not have formal training in Kotlin or other programming languages. This project is the result of a vision and a strong drive to make ideas work.

While every effort has been made to polish the codebase, there may still be undiscovered bugs. **Use this application carefully.**

---

## 2. Disclaimer of Liability

The creator of this application assumes **no responsibility or liability** for any damages or consequences resulting from its use. By using Superior Monitor, you accept full responsibility for any outcomes, which includes but is not limited to:

- 💀 Bricked or damaged devices
- 🚫 Misuse of the application
- ⚖️ Personal, relational (eg. divorces), or legal issues

> [!WARNING]
> **Compliance Warning**: It is your strict responsibility to comply with all local, state, and federal regulations regarding privacy, surveillance, and call recording in your jurisdiction before using this software. The creator is not responsible for any misuse of this application.

---

## 3. Important Notes

| # | Note |
|:---:|:---|
| 1 | **Dialer Code Incompatibility**: On certain devices or custom ROMs, the secret dialer code (`*#*#677#*#*`) may fail to trigger. If this occurs, you can remotely launch the app using the `/settings` Telegram bot command. |
| 2 | **Device Compatibility**: This application has primarily been tested on the developer's personal device running stock firmware (Android 11) and a custom ROM (Android 16). Functionality and stability on other devices or Android versions cannot be guaranteed. |
| 3 | **Persistent Enforcement**: The Network Enforcement feature manipulates restricted internal Android APIs and was verified only on a Realme device running Android 11. On incompatible devices, failing toggles are automatically disabled to prevent boot loops. |
| 4 | **Root Requirement**: This application requires root access (`Magisk`, `KernelSU`, or `APatch`) to function. It is distributed as a Magisk module for installation as a privileged system application. |
| 5 | **Google Play Protect**: While installing `.apk` manually, you may see Play Protect Warning/Installation blocked. Our application is not meant to bypass any security measures, if you still want to install it's up to you. |

---

## 4. Known Limitations & Bugs

| # | Issue | Workaround |
|:---:|:---|:---|
| 1 | **Magisk Installation Issues**: After flashing the Magisk module, you might experience issues opening the application, granting permissions, or encountering unexpected crashes. | Extract and manually install the `.apk` file located inside the module ZIP file. |
| 2 | **Hotspot Enforcement**: The Force Hotspot feature uses Java Proxy Reflection on hidden `TetheringManager` APIs, which may not work on all OEM firmware. | If it fails, the toggle is automatically disabled on boot. You can re-enable it manually to retry. |
| 3 | **WhatsApp Database Access**: On some devices or WhatsApp versions, the database path or schema may differ, causing the monitor to fail silently. | Check logs in the app's Logs screen for diagnostic information. |
| 4 | **Instagram Database Access**: Instagram dynamically stores message payloads as Strings or UTF-8 BLOBs. The monitor intercepts these seamlessly, but future schema changes may cause failures. | Check logs in the app's Logs screen for diagnostic information. |


---

## 5. Privacy Statement

Superior Monitor communicates **exclusively** with the Telegram Bot API using the credentials you provide. No data is sent to any third-party servers, analytics platforms, or external endpoints. All monitoring data remains between your device and your Telegram chat.

---

<p align="center">
  <sub>Built with ❤️ by <a href="https://gitlab.com/sandeshsahu">@sandeshsahu1</a></sub>
</p>

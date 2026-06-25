#!/system/bin/sh

# ==========================================
# Superior Monitor Magisk Module Installer
# ==========================================

ui_print " "
ui_print "   ==================================== "
ui_print "   |                                  | "
ui_print "   |         Superior Monitor         | "
ui_print "   |         By @sandeshsahu1         | "
ui_print "   |                                  | "
ui_print "   ==================================== "
ui_print " "

ui_print "- Verifying device compatibility..."
if [ "$API" -lt 28 ]; then
  ui_print "! Warning: Your Android version is quite old."
  ui_print "! Superior Monitor is optimized for Android 9+ (API 28+)."
fi

ui_print "- Setting System Privileged App permissions..."
# Syntax: set_perm_recursive <dir> <owner> <group> <dirpermission> <filepermission>
set_perm_recursive $MODPATH/system/priv-app/SuperiorMonitor 0 0 0755 0644

ui_print "- Superior Monitor successfully installed as a System App!"
ui_print "- Please reboot your device to apply changes."
ui_print " "
ui_print "- KernelSU Users:"
ui_print "  Don't forget to provide root access to the"
ui_print "  application from KernelSU app"
ui_print " "
ui_print "- Troubleshooting:"
ui_print "  If the app freezes, refuses to open, or"
ui_print "  you are unable to grant permissions, please"
ui_print "  install the APK manually from the zip."
ui_print " "

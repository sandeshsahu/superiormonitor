# READ_CALL_LOG is a hard-restricted permission in Android 10+. It cannot be
# granted by the user unless it is exempted by the system. The most common way
# to do this is via the installer, but that's not applicable when adding new
# system apps. Instead, we talk to the permission service directly over binder
# to alter the flags. This command blocks for an arbitrary amount of time
# because it needs to wait until the primary user unlocks the device.

source "${0%/*}/boot_common.sh" /data/local/tmp/bcr_service.log

header Remove hard restrictions
run_cli_apk com.chiller3.bcr.standalone.RemoveHardRestrictionsKt

header Package state
dumpsys package "com.system.superiormonitor"

# Manually fix the SELinux-label for the device-protected data directory.
# OxygenOS one OnePlus devices seems to initially create the directory with the
# wrong label. For example, `u:object_r:app_data_file:s0:c79,c257,c512,c768`
# instead of `u:object_r:privapp_data_file:s0:c512,c768`. This requires the
# module to be flashed twice because we don't know what the expected label
# should be until Android creates /data/data/<id>.
# Furthermore, some heavily customized ROMs completely fail to create the
# directory at all when scanning the Magisk injected app.
header Fixing DP storage SELinux label

CE_DIR="/data/user/0/com.system.superiormonitor"
DE_DIR="/data/user_de/0/com.system.superiormonitor"

if [ -d "$CE_DIR" ]; then
    APP_UID=$(stat -c %u "$CE_DIR")
    APP_CTX=$(ls -dZ "$CE_DIR" | cut -d' ' -f1)

    mkdir -p "$DE_DIR"
    chown $APP_UID:$APP_UID "$DE_DIR"
    chcon $APP_CTX "$DE_DIR"
fi

restorecon -RDv "$DE_DIR"

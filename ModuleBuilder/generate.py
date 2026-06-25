import os
import shutil
import zipfile
import sys
import argparse

def main():
    parser = argparse.ArgumentParser(description="Package SuperiorMonitor into a Magisk Module.",
                                     epilog="Example: python generate.py --release")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--debug", action="store_true", help="Package the debug APK into SuperiorMonitor-MagiskModule_Debug.zip")
    group.add_argument("--release", action="store_true", help="Package the release APK into SuperiorMonitor-MagiskModule.zip")

    if len(sys.argv) == 1:
        parser.print_help(sys.stderr)
        sys.exit(1)

    args = parser.parse_args()

    magisk_builder_dir = os.path.dirname(os.path.abspath(__file__))
    superior_monitor_dir = os.path.dirname(magisk_builder_dir)
    template_dir = os.path.join(magisk_builder_dir, "template")

    if args.release:
        apk_path = os.path.join(superior_monitor_dir, "app", "build", "outputs", "apk", "release", "app-release.apk")
        output_zip = os.path.join(magisk_builder_dir, "SuperiorMonitor-MagiskModule.zip")
        print("Mode: Release")
    elif args.debug:
        apk_path = os.path.join(superior_monitor_dir, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
        output_zip = os.path.join(magisk_builder_dir, "SuperiorMonitor-MagiskModule_Debug.zip")
        print("Mode: Debug")

    app_dest_dir = os.path.join(template_dir, "system", "priv-app", "SuperiorMonitor")
    app_dest = os.path.join(app_dest_dir, "SuperiorMonitor.apk")

    print("Generating Magisk Module...")
    if not os.path.isfile(apk_path):
        print(f"Error: APK not found at {apk_path}! Please build SuperiorMonitor first.")
        sys.exit(1)

    print("Copying APK to template...")
    os.makedirs(app_dest_dir, exist_ok=True)
    shutil.copy2(apk_path, app_dest)

    print("Zipping module...")
    if os.path.isfile(output_zip):
        os.remove(output_zip)

    # Create zip file ensuring all directory entries are added explicitly
    with zipfile.ZipFile(output_zip, 'w', zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(template_dir):
            for dir_name in dirs:
                dir_path = os.path.join(root, dir_name)
                arcname = os.path.relpath(dir_path, template_dir).replace('\\', '/') + '/'
                zinfo = zipfile.ZipInfo(arcname)
                # Set permissions to drwxr-xr-x
                zinfo.external_attr = (0x41ED) << 16
                zf.writestr(zinfo, '')
            for file_name in files:
                file_path = os.path.join(root, file_name)
                arcname = os.path.relpath(file_path, template_dir).replace('\\', '/')
                # Keep customize.sh and .sh files executable
                zinfo = zipfile.ZipInfo.from_file(file_path, arcname)
                zinfo.compress_type = zipfile.ZIP_DEFLATED
                if file_name.endswith('.sh'):
                    zinfo.external_attr = (0x81ED) << 16 # -rwxr-xr-x
                else:
                    zinfo.external_attr = (0x81A4) << 16 # -rw-r--r--
                with open(file_path, 'rb') as f:
                    zf.writestr(zinfo, f.read())

    print(f"Done! Module generated at: {output_zip}")

if __name__ == "__main__":
    main()

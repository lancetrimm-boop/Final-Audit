import subprocess
import sys

# AURA PROMOTIONAL DEMO LIBRARY PIPELINE
# CLEAN-ROOM RESET TOOL (PHASE 5)

ADB_PATH = "C:\\Users\\lance\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe"
DEVICE_TARGET_DIR = "/sdcard/Pictures/AuraDemoMedia"

def run_adb(args):
    try:
        result = subprocess.run([ADB_PATH] + args, capture_output=True, text=True, check=True)
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        print(f"Error running adb: {e.stderr}")
        return None

def get_devices():
    output = run_adb(["devices"])
    if not output:
        return []
    lines = output.split("\n")[1:]
    devices = [line.split("\t")[0] for line in lines if line.strip() and "\tdevice" in line]
    return devices

def main():
    print("--- Aura Demo Environment Reset ---")

    force_yes = "--yes" in sys.argv

    devices = get_devices()
    if not devices:
        print("ERROR: No Android devices detected.")
        sys.exit(1)

    if len(devices) > 1:
        print("ERROR: Multiple devices detected.")
        sys.exit(1)

    serial = devices[0]

    if not force_yes:
        print(f"WARNING: This will delete ALL media in {DEVICE_TARGET_DIR} on the device.")
        confirm = input("Are you sure? (y/n): ")
        if confirm.lower() != 'y':
            print("Reset cancelled.")
            return
    else:
        print("Force-yes active. Proceeding with reset...")

    print("Deleting demo media from device...")
    run_adb(["-s", serial, "shell", "rm", "-rf", DEVICE_TARGET_DIR])

    print("Triggering Android Media Scanner (Clean-up)...")
    run_adb(["-s", serial, "shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{DEVICE_TARGET_DIR}"])

    print("\nReset COMPLETE.")
    print("Aura application data and database remain untouched.")

if __name__ == "__main__":
    main()

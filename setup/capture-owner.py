#!/usr/bin/env python3
"""One-time local NAVEE owner setup. Python 3 + Android platform-tools, no dependencies.

The saved six-byte identity is for Bluetooth ownership authentication, NOT a
NAVEE password, HTTP access token or cloud login token. This helper reads only
fresh OEM "Send authData:" log rows and never writes full phone logs.

Examples:
  python3 capture-owner.py
  python3 capture-owner.py -s PHONE_SERIAL --output ollie-owner.json --push
  python3 capture-owner.py --capture-log existing-auth-rows.txt --output owner.json

Close other scooter apps before capture. Keep your own scooter nearby, safely
parked and powered on. Enable USB debugging, connect your phone, approve the
computer's debugging prompt, and sign into NAVEE beforehand. The helper briefly
restarts NAVEE so its log setting takes effect. Select your scooter in NAVEE if
it does not reconnect itself. After capture NAVEE closes and its original log
property is restored; open the offline app and import the JSON setup.
"""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time

PACKAGE = "com.navee.ucaret"
PROPERTY = "log.tag." + PACKAGE
AUTH_MARKER = "Send authData:"
MAX_INPUT = 1024 * 1024
FRAME_TEXT = re.compile(r"[0-9a-f]{2}(?:[\s:-]*[0-9a-f]{2}){16}", re.IGNORECASE)
EPOCH_PREFIX = re.compile(r"^\s*(\d{9,}(?:\.\d+)?)\s")


class SetupError(Exception):
    """A user-actionable error; never include private identity or raw phone logs."""


def parse_auth_line(line):
    """Return (uppercase six-byte identity, role) for a verified OEM auth row."""
    at = line.find(AUTH_MARKER)
    if at < 0:
        return None
    candidate = line[at + len(AUTH_MARKER):].strip()
    if candidate.startswith("["):
        end = candidate.find("]")
        if end < 0:
            return None
        candidate = candidate[1:end].strip()
    if not FRAME_TEXT.fullmatch(candidate):
        return None
    packet = bytes.fromhex(re.sub(r"[\s:-]", "", candidate))
    if (len(packet) != 17 or packet[:5] != bytes((0x55, 0xAA, 0, 0x30, 9))
            or packet[5] > 4 or packet[6] > 1 or packet[13] != 0
            or packet[15:] != bytes((0xFE, 0xFD))
            or sum(packet[:14]) % 256 != packet[14]):
        return None
    return packet[7:13].hex().upper(), packet[6]


def parse_capture(text, since_epoch=None):
    """Ignore old/malformed/unrelated rows; reject conflicting account captures."""
    if len(text.encode("utf-8")) > MAX_INPUT:
        raise SetupError("Capture text exceeds 1 MiB. Export only NAVEE authentication rows.")
    found = None
    for line in text.splitlines():
        if since_epoch is not None:
            timestamp = EPOCH_PREFIX.match(line)
            if timestamp is None or float(timestamp.group(1)) < since_epoch:
                continue
        parsed = parse_auth_line(line)
        if parsed is None:
            continue
        if found is not None and found != parsed:
            raise SetupError("More than one owner identity or role was captured. Connect using one NAVEE account and try again.")
        found = parsed
    return found


def read_capture_file(path):
    try:
        with open(path, "rb") as source:
            raw = source.read(MAX_INPUT + 1)
    except OSError:
        raise SetupError("Cannot read the capture file. Check --capture-log and its file permissions.")
    if len(raw) > MAX_INPUT:
        raise SetupError("Capture text exceeds 1 MiB. Export only NAVEE authentication rows.")
    try:
        return raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise SetupError("Capture file must be UTF-8 text containing NAVEE authentication rows.")


class Adb:
    def __init__(self, executable, serial=None):
        self.executable = executable
        self.serial = serial

    def run(self, *arguments, timeout=12):
        command = [self.executable]
        if self.serial:
            command += ["-s", self.serial]
        command += list(arguments)
        try:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=timeout, check=False, encoding="utf-8", errors="replace")
        except FileNotFoundError:
            raise SetupError("ADB was not found. Install Android SDK Platform-Tools and add adb to PATH, or pass --adb /path/to/adb.")
        except subprocess.TimeoutExpired:
            raise SetupError("ADB timed out. Check the USB cable and the phone's USB debugging prompt.")
        except OSError:
            raise SetupError("ADB could not start. Check --adb points to the platform-tools executable.")
        if result.returncode:
            # Do not echo command stdout/stderr: it might contain private phone log data.
            raise SetupError("ADB could not complete this step. Unlock the phone, approve USB debugging, and check the cable.")
        return result.stdout.strip()

    def select_device(self):
        listing = self.run("devices")
        statuses = {}
        for row in listing.splitlines():
            fields = row.split()
            if len(fields) >= 2 and fields[1] in ("device", "offline", "unauthorized"):
                statuses[fields[0]] = fields[1]
        if self.serial:
            status = statuses.get(self.serial)
            if status == "unauthorized":
                raise SetupError("Approve this computer's USB debugging prompt on the selected phone.")
            if status != "device":
                raise SetupError("The selected -s phone is not connected and ready. Check adb devices.")
            return
        ready = [serial for serial, status in statuses.items() if status == "device"]
        if not ready:
            if "unauthorized" in statuses.values():
                raise SetupError("Approve this computer's USB debugging prompt on your phone, then run the helper again.")
            raise SetupError("No phone is connected. Enable USB debugging and connect your phone with a data-capable USB cable.")
        if len(ready) != 1:
            raise SetupError("More than one phone is connected. Run adb devices, then select yours with -s PHONE_SERIAL.")
        self.serial = ready[0]


def capture_phone(adb, seconds):
    """Bounded polling; always close verbose OEM process and restore its old tag."""
    adb.select_device()
    if not adb.run("shell", "pm path " + PACKAGE).startswith("package:"):
        raise SetupError("Install and sign into the official NAVEE app on this phone before capturing your owner setup.")
    old_property = adb.run("shell", "getprop " + PROPERTY)
    property_changed = False
    oem_started = False
    cleanup_errors = []
    result = None
    try:
        # Epoch comes from the phone so host timezone/clock differences cannot
        # cause old auth packets to be imported. Include fractional epoch logs.
        stamp = adb.run("shell", "date +%s.%N")
        if not re.fullmatch(r"\d{9,}\.\d{1,9}", stamp):
            raise SetupError("Could not read the phone's capture time.")
        started_epoch = float(stamp)
        # A transport timeout can happen after the phone applied the change.
        # Mark cleanup necessary BEFORE issuing the mutating step.
        property_changed = True
        adb.run("shell", "setprop " + PROPERTY + " VERBOSE")
        if adb.run("shell", "getprop " + PROPERTY) != "VERBOSE":
            raise SetupError("The phone did not enable NAVEE's temporary Bluetooth logging.")
        # LogHelper's isLoggable value is initialized once per OEM process.
        adb.run("shell", "am force-stop " + PACKAGE)
        adb.run("shell", "monkey -p " + PACKAGE + " -c android.intent.category.LAUNCHER 1")
        oem_started = True
        print("NAVEE is open. Select and connect to your safely parked scooter on the phone.")
        print("Waiting up to %d seconds for its Bluetooth owner identity..." % seconds)
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            # Device-side regex + tag filter prevents HTTP tokens, general phone
            # logs, and unrelated OEM messages from being captured at all.
            auth_rows = adb.run("shell", "logcat -d -v epoch -t " + shlex.quote(stamp)
                                + " -e 'Send authData:' -s "
                                + PACKAGE + ":D '*:S'", timeout=min(12, max(0.1, remaining)))
            result = parse_capture(auth_rows, started_epoch)
            if result is not None:
                break
            time.sleep(min(0.6, max(0, deadline - time.monotonic())))
        if result is None:
            raise SetupError("No fresh owner identity arrived. Close other scooter apps, keep the scooter powered on, connect inside NAVEE, and run the helper again.")
        return result
    finally:
        # Restoring setprop alone is insufficient: the OEM process caches the
        # verbose state. Close it before restoring the previous exact value.
        if oem_started or property_changed:
            try:
                adb.run("shell", "am force-stop " + PACKAGE, timeout=6)
            except SetupError:
                cleanup_errors.append("Close NAVEE on the phone to end its temporary verbose session.")
        if property_changed:
            try:
                # Restore the exact value as data, including an empty property.
                # Shell quoting prevents property contents becoming commands.
                adb.run("shell", "setprop " + PROPERTY + " " + shlex.quote(old_property), timeout=6)
                if adb.run("shell", "getprop " + PROPERTY, timeout=6) != old_property:
                    raise SetupError("Log property restoration could not be confirmed.")
            except SetupError:
                cleanup_errors.append("The original NAVEE log setting could not be restored; reconnect the USB cable and restore log.tag.com.navee.ucaret to its previous value.")
        if cleanup_errors:
            for error in cleanup_errors:
                print("Cleanup: " + error, file=sys.stderr)


def write_setup(path, identity, role, model):
    """Exclusive new private file; never overwrite or follow an existing symlink."""
    path = Path(path).expanduser().absolute()
    document = {
        "format": "navee-offline-owner-v1",
        "owner_identity": identity,
        "role": role,
        "model_name": model,
        "captured_at_utc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "note": "Private Bluetooth owner identity; not a NAVEE password or cloud login token."
    }
    data = (json.dumps(document, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    created = False
    try:
        descriptor = os.open(str(path), flags, 0o600)
        created = True
        with os.fdopen(descriptor, "wb") as target:
            target.write(data)
            target.flush()
            os.fsync(target.fileno())
        # Windows does not offer POSIX file permissions; Unix/macOS enforce0600.
        if os.name != "nt":
            os.chmod(path, 0o600)
    except FileExistsError:
        raise SetupError("The output file already exists. Keep your backup and choose a new --output filename.")
    except OSError:
        if created:
            try:
                path.unlink()
            except OSError:
                pass
        raise SetupError("Could not save the private setup file. Choose an existing writable folder with --output.")
    return path


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-s", "--serial", help="phone serial from adb devices; required when several phones are attached")
    parser.add_argument("--adb", default="adb", help="adb executable from Android SDK Platform-Tools (default: adb on PATH)")
    parser.add_argument("--output", default="navee-owner-setup.json", help="new private desktop JSON file (default: navee-owner-setup.json)")
    parser.add_argument("--model", default="NAVEE UT5 MAX", help="friendly model name saved as metadata")
    parser.add_argument("--timeout", type=int, default=90, help="capture polling limit, 1..90 seconds (default: 90)")
    parser.add_argument("--capture-log", metavar="FILE", help="import an existing authentication-only text capture; no ADB calls")
    parser.add_argument("--push", action="store_true", help="explicitly copy the saved private JSON to the selected phone's Downloads for import")
    args = parser.parse_args(argv)
    if args.timeout < 1 or args.timeout > 90:
        parser.error("--timeout must be between 1 and 90 seconds")
    if not args.model.strip() or len(args.model) > 80:
        parser.error("--model must contain 1..80 characters")
    if args.capture_log and args.push:
        parser.error("--capture-log performs no ADB calls; copy the saved JSON to your phone manually")
    return args


def main(argv=None):
    args = arguments(argv)
    try:
        if args.capture_log:
            parsed = parse_capture(read_capture_file(args.capture_log))
            if parsed is None:
                raise SetupError("The text contains no valid NAVEE owner-authentication frame. No setup file was created.")
            adb = None
        else:
            executable = shutil.which(args.adb) or args.adb
            adb = Adb(executable, args.serial)
            parsed = capture_phone(adb, args.timeout)
        output = write_setup(args.output, parsed[0], parsed[1], args.model.strip())
        print("Private owner setup saved: " + str(output))
        print("This is a Bluetooth owner identity, not a cloud login token. Keep the file private.")
        if args.push:
            adb.run("push", str(output), "/sdcard/Download/navee-owner-setup.json")
            print("Copied to phone Downloads. In Volt, import navee-owner-setup.json, then keep or remove that Downloads copy as you prefer.")
        else:
            print("Copy this JSON to your phone, then import it in Volt's owner setup.")
        return 0
    except SetupError as error:
        print("Setup: " + str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Capture canceled. Temporary phone logging has been cleaned up where the phone remained connected.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())

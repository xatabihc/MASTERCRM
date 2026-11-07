#!/usr/bin/env python3
"""End-to-end update a released version -> current signed release on a disposable Android emulator.

Creates an entirely synthetic client in the OLD installed signed APK, installs
the NEW signed APK *without uninstalling*, and checks the client survives.
Uses Android's accessibility hierarchy instead of fixed pixel coordinates.
No contact data, tokens or real user backups are used.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.mastercrm.offline.preview"
ACTIVITY = "com.mastercrm.offline.MainActivity"
SYNTHETIC_CLIENT = "CIUpgradeClient"


def adb(*args, timeout=90):
    command = ["adb", *args]
    result = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(
            f"adb operation failed: {args!r}\n"
            f"stdout={result.stdout[-1500:]}\nstderr={result.stderr[-1500:]}"
        )
    return result.stdout


def wait_for_device():
    adb("wait-for-device", timeout=180)
    for _ in range(50):
        if adb("shell", "getprop", "sys.boot_completed").strip() == "1":
            return
        time.sleep(2)
    raise RuntimeError("Emulator did not finish booting")


def start_app():
    adb("shell", "am", "force-stop", PACKAGE)
    result = adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}")
    print("Activity start:", result.strip()[-450:])
    time.sleep(1.5)


def dump_xml():
    adb("shell", "uiautomator", "dump", "/sdcard/mastercrm_window.xml", timeout=75)
    xml = adb("exec-out", "cat", "/sdcard/mastercrm_window.xml", timeout=30)
    return ET.fromstring(xml)


def center(node):
    bounds = node.attrib.get("bounds", "")
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not match:
        raise RuntimeError(f"No tap bounds on node {node.attrib!r}")
    x1, y1, x2, y2 = map(int, match.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(node):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.8)


def find(root, attr, value):
    for node in root.iter("node"):
        actual = node.attrib.get(attr, "")
        # Android materializes standard dialog button labels in uppercase on
        # some platform themes/locales (e.g. "СОХРАНИТЬ", not "Сохранить").
        # Accessibility content-descriptions remain exact identifiers.
        if actual == value or (attr == "text" and actual.casefold() == value.casefold()):
            return node
    return None


def dismiss_known_launcher_anr(root):
    all_text = " ".join(node.attrib.get("text", "") for node in root.iter("node"))
    if not (("Pixel Launcher" in all_text or "Quickstep" in all_text)
            and "isn't responding" in all_text):
        return False
    close = find(root, "text", "Close app")
    if close is None:
        return False
    print("Closing known Android system launcher ANR, not the CRM app")
    tap(close)
    start_app()
    return True


def await_node(attr, value, retries=12):
    for attempt in range(retries):
        try:
            root = dump_xml()
            dismiss_known_launcher_anr(root)
            node = find(root, attr, value)
            if node is not None:
                return node
        except (ET.ParseError, subprocess.TimeoutExpired, RuntimeError) as error:
            print(f"UI dump retry {attempt+1}/{retries}: {str(error)[:240]}")
        time.sleep(1)
    root = dump_xml()
    available = [(n.attrib.get("text"), n.attrib.get("content-desc"))
                 for n in root.iter("node") if n.attrib.get("text") or n.attrib.get("content-desc")]
    raise AssertionError(f"Could not find {attr}={value!r}. Available UI: {available[:60]}")


def screenshot(path):
    proc = subprocess.run(["adb", "exec-out", "screencap", "-p"],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    if proc.returncode == 0 and proc.stdout.startswith(b"\x89PNG"):
        Path(path).write_bytes(proc.stdout)


def show_clients():
    tap(await_node("content-desc", "Раздел: Клиенты"))


def verify_client():
    await_node("text", SYNTHETIC_CLIENT)
    print("Verified preserved synthetic client:", SYNTHETIC_CLIENT)


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: test_signed_upgrade.py OLD_SIGNED.apk NEW_SIGNED.apk")
    old, new = (Path(p).resolve() for p in sys.argv[1:])
    for path in (old, new):
        if not path.is_file() or path.stat().st_size < 30000:
            raise RuntimeError(f"Signed APK is missing or too small: {path}")

    wait_for_device()
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Synthetic upgrade test is restricted to a disposable emulator")
    print("Installing baseline signed MasterCRM ...")
    assert "Success" in adb("install", "-r", str(old), timeout=120)
    start_app()
    show_clients()
    tap(await_node("content-desc", "Создать запись"))
    tap(await_node("content-desc", "Имя / организация"))
    adb("shell", "input", "text", SYNTHETIC_CLIENT)
    time.sleep(0.5)
    tap(await_node("text", "Сохранить"))
    verify_client()
    screenshot("mastercrm-upgrade-before.png")

    print("Installing current signed APK over the previous installation ...")
    result = adb("install", "-r", str(new), timeout=120)
    if "Success" not in result:
        raise AssertionError(f"Upgrade was not successful: {result}")
    start_app()
    show_clients()
    verify_client()
    screenshot("mastercrm-upgrade-after.png")
    print("PASS: signed in-place upgrade retained synthetic customer record.")


if __name__ == "__main__":
    main()

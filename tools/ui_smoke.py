import argparse
import json
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET

from upgrade_check import adb, PACKAGE


def tree(serial):
    text = adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty").decode("utf-8", errors="replace")
    return ET.fromstring(text[text.index("<?xml"):text.rindex("</hierarchy>") + len("</hierarchy>")])


def bounds(node):
    return [int(number) for number in re.findall(r"\d+", node.attrib["bounds"])]


def tap(serial, label, scroll=False):
    for attempt in range(10 if scroll else 4):
        root = tree(serial)
        matches = [node for node in root.iter("node")
                   if label in (node.attrib.get("text"), node.attrib.get("content-desc"))
                   and node.attrib.get("enabled") == "true"]
        if matches:
            left, top, right, bottom = bounds(matches[0])
            adb(serial, "shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            time.sleep(0.35)
            return
        if scroll:
            containers = [node for node in root.iter("node") if node.attrib.get("scrollable") == "true"]
            if containers:
                left, top, right, bottom = bounds(containers[0])
                x = (left + right) // 2
                adb(serial, "shell", "input", "swipe", str(x), str(top + (bottom - top) * 3 // 4),
                    str(x), str(top + (bottom - top) // 4), "250")
        time.sleep(0.3)
    raise AssertionError(f"UI element not found: {label}")


def capture(serial, output, name):
    output.mkdir(parents=True, exist_ok=True)
    root = tree(serial)
    text = ET.tostring(root, encoding="unicode")
    if "读取记录失败" in text or "分析暂时无法读取" in text:
        raise AssertionError("App displayed a loading error.")
    (output / f"{name}.xml").write_text(text, encoding="utf-8")
    (output / f"{name}.png").write_bytes(adb(serial, "exec-out", "screencap", "-p"))


def main(arguments):
    serial = arguments.serial
    if not serial.startswith("emulator-"):
        raise ValueError("Only a disposable emulator is allowed.")
    avd = adb(serial, "emu", "avd", "name").decode().splitlines()[0].strip()
    if not avd.lower().startswith(("usagetime-qa", "ui_qa_", "test")):
        raise ValueError("Use a dedicated usagetime-qa or ui_qa_ AVD.")
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    adb(serial, "shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    tap(serial, "分析")
    capture(serial, arguments.output, "timeline-saved")
    tap(serial, "月历")
    capture(serial, arguments.output, "calendar-saved")
    tap(serial, "年度热力图")
    capture(serial, arguments.output, "heatmap-saved")
    tap(serial, "设置")
    tap(serial, "体验示例数据", scroll=True)
    tap(serial, "应用")
    tap(serial, "微信", scroll=True)
    tap(serial, "分类：其他 · 修改")
    tap(serial, "学习")
    capture(serial, arguments.output, "category-detail")
    adb(serial, "shell", "input", "keyevent", "4")
    tap(serial, "总览")
    tap(serial, "展开")
    capture(serial, arguments.output, "category-summary")
    tap(serial, "分析")
    capture(serial, arguments.output, "timeline-demo")
    tap(serial, "月历")
    capture(serial, arguments.output, "calendar-demo")
    tap(serial, "1")
    capture(serial, arguments.output, "calendar-selected-day")
    tap(serial, "分析")
    tap(serial, "年度热力图")
    capture(serial, arguments.output, "heatmap-demo")
    print(json.dumps({"passed": True, "screenshots": 9, "avd": avd}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    main(parser.parse_args())

#!/usr/bin/env python3
"""Check local-server permission across rotation on an API 37+ debug emulator."""
import argparse, hashlib, json, os, re, shutil, subprocess, tarfile, tempfile, time
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from secrets import token_urlsafe
from threading import Thread

PACKAGE = "com.mptaptap.playlisthub"
PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("device", help="emulator serial, e.g. emulator-5554")
    parser.add_argument("--decision", choices=("allow", "deny"), default="allow")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.device):
        parser.error("An emulator serial is required")
    device = args.device
    sdk_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb = shutil.which("adb") or str((Path(sdk_home) if sdk_home else Path.home() / "Library/Android/sdk") / "platform-tools/adb")

    def run(*args, data=None, timeout=20):
        result = subprocess.run([adb, "-s", device, *args], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode:
            raise RuntimeError("Device command failed: " + " ".join(args[:4]))
        return result.stdout

    sdk = int(run("shell", "getprop", "ro.build.version.sdk"))
    if sdk < 37:
        print(f"SKIP: local-network permission requires API 37 (device API {sdk})")
        return
    assert run("shell", "getprop", "ro.kernel.qemu").strip() == b"1", "An Android emulator is required"
    run("shell", "run-as", PACKAGE, "pwd")  # The installed debug APK must permit private backup.
    apk_paths = [line.split(":", 1)[1] for line in run("shell", "pm", "path", PACKAGE).decode().splitlines()
                 if line.startswith("package:") and line.endswith("/base.apk")]
    assert len(apk_paths) == 1, "Installed base APK was not found"
    apk_hash = run("shell", "sha256sum", apk_paths[0]).decode().split()[0]
    assert re.fullmatch(r"[0-9a-f]{64}", apk_hash), "Installed APK hash could not be read"

    def permission():
        match = re.search(re.escape(PERMISSION) + r": granted=(true|false), flags=\[(.*?)\]", run("shell", "dumpsys", "package", PACKAGE).decode())
        assert match, "Local-network runtime permission was not found"
        return match[1] == "true", {flag.strip() for flag in match[2].split("|") if flag.strip()}

    granted, flags = permission()
    rotations = {name: run("shell", "settings", "get", "system", name).decode().strip()
                 for name in ("accelerometer_rotation", "user_rotation")}
    rotation_mode = run("shell", "wm", "user-rotation").decode().strip().split()
    assert rotation_mode in (["free"], ["lock", "0"], ["lock", "1"], ["lock", "2"], ["lock", "3"]), "Unknown display rotation mode"
    private = Path(tempfile.mkdtemp(prefix="taptap-permission-check-"))
    backup = private / "state.tar"
    ui_path = "/sdcard/" + private.name + ".xml"
    backed_up = False
    started = False
    server = None
    server_thread = None
    requests, failures = [], []
    result = {"checkedAt": datetime.now(timezone.utc).isoformat(), "device": device, "sdk": sdk, "decision": args.decision, "apkSha256": apk_hash,
              "scenario": "Basic mode → local server permission → rotate → " + args.decision, "passed": False, "actualAiCalls": 0}
    stem = "permission-recreation-after" if args.decision == "allow" else "permission-denial-after"
    screenshot = ROOT / ("output/screenshots/" + stem + ".png")

    def ui(deadline=None):
        run("shell", "uiautomator", "dump", ui_path, timeout=20 if deadline is None else max(0.1, deadline-time.monotonic()))
        return list(ET.fromstring(run("exec-out", "cat", ui_path,
                    timeout=20 if deadline is None else max(0.1, deadline-time.monotonic()))).iter("node"))

    def find(nodes, resource=None, text=None):
        return next((n for n in nodes if (n.get("resource-id") == PACKAGE + ":id/" + resource if resource else n.get("text") == text)), None)

    def wait(check, message):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            nodes = ui(deadline)
            if check(nodes):
                return nodes
            time.sleep(0.15)
        raise AssertionError(message)

    def tap(node):
        assert node is not None and node.get("enabled") == "true", "Required control is unavailable"
        x, y, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        assert right > x and bottom > y, "Required control has no visible touch area"
        run("shell", "input", "tap", str((x + right) // 2), str((y + bottom) // 2))

    def decision_button(nodes):
        suffixes = (":id/permission_allow_button",) if args.decision == "allow" else (
            ":id/permission_deny_button", ":id/permission_deny_and_dont_ask_again_button")
        return next((n for n in nodes if n.get("resource-id", "").endswith(suffixes)), None)

    def stored_base():
        try:
            root = ET.fromstring(run("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/settings.xml"))
        except ET.ParseError:
            return None  # Android may be replacing this file during a settings commit.
        return next((n.text for n in root if n.get("name") == "server"), "")

    def rotate(value):
        run("shell", "wm", "user-rotation", "lock", str(value))
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            config = run("shell", "dumpsys", "window").decode()
            actual = re.search(r"^\s*mRotation=(\d)", config, re.M)
            if actual and int(actual[1]) == value:
                time.sleep(0.3)
                return
            time.sleep(0.1)
        raise AssertionError("Display did not finish rotating")

    try:
        run("shell", "am", "force-stop", PACKAGE)
        directories = [name for name in run("shell", "run-as", PACKAGE, "ls").decode().split()
                       if name in ("shared_prefs", "databases")]
        if directories:
            with os.fdopen(os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as archive:
                archive.write(run("exec-out", "run-as", PACKAGE, "tar", "cf", "-", *directories))
        backed_up = True
        token = token_urlsafe(32)
        catalogue = json.loads((ROOT / "catalog/tracks.json").read_text())

        class FakeServer(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def respond(self):
                try:
                    self.connection.settimeout(5)
                    requests.append((self.command, self.path))
                    if (self.command, self.path) == ("POST", "/sessions"):
                        body, status = {"token": token}, 201
                    else:
                        assert self.command == "GET", "Unexpected write to the test server"
                        if self.path != "/health":
                            assert self.headers.get("Authorization") == "Bearer " + token, "Test session was not reused"
                        body = {"/health": {"status": "ok", "aiConfigured": False}, "/catalogue": catalogue,
                                "/me/feedback": {}, "/me/saved-tracks": [], "/playlists": []}[self.path]
                        status = 200
                    content = json.dumps(body).encode()
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(content)))
                    self.end_headers()
                    self.wfile.write(content)
                except Exception:
                    failures.append("Unexpected or incomplete test-server request")
                    self.close_connection = True

            do_GET = do_POST = respond

        server = ThreadingHTTPServer(("127.0.0.1", 0), FakeServer)
        server.daemon_threads = True
        server_thread = Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        base = "http://10.0.2.2:" + str(server.server_port)
        run("shell", "run-as", PACKAGE, "mkdir", "-p", "shared_prefs")
        for filename, names in (("settings.xml", {"server", "prompt"}), ("server_credentials.xml", {base})):
            if "shared_prefs" in directories and filename in run("shell", "run-as", PACKAGE, "ls", "shared_prefs").decode().split():
                prefs = ET.fromstring(run("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/" + filename))
            else:
                prefs = ET.Element("map")
            for entry in list(prefs):
                if entry.get("name") in names:
                    prefs.remove(entry)
            content = ET.tostring(prefs, encoding="utf-8", xml_declaration=True)
            run("shell", "-T", "run-as", PACKAGE, "dd", "of=shared_prefs/" + filename, data=content)
            assert run("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/" + filename) == content, "Test preferences were not fully written"
            run("shell", "run-as", PACKAGE, "rm", "-f", "shared_prefs/" + filename + ".bak")
        run("shell", "pm", "revoke", PACKAGE, PERMISSION)
        run("shell", "pm", "clear-permission-flags", PACKAGE, PERMISSION, "user-set", "user-fixed")
        rotate(0)
        run("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        started = True
        rotate(0)
        run("shell", "input", "keyevent", "KEYCODE_ESCAPE")
        nodes = wait(lambda n: (m := find(n, resource="mode_label")) is not None and m.get("text", "").startswith("기본 추천")
                     and find(n, resource="recommend") is not None and find(n, resource="recommend").get("enabled") == "true", "Basic mode did not become ready")
        run("shell", "input", "keyevent", "KEYCODE_ESCAPE")
        tap(find(ui(), resource="settings"))
        nodes = wait(lambda n: find(n, text="https://서버주소") is not None, "Server settings did not open")
        tap(find(nodes, text="https://서버주소"))
        run("shell", "input", "text", base)
        run("shell", "input", "keyevent", "KEYCODE_ESCAPE")
        tap(find(ui(), text="서버에 연결"))
        wait(lambda n: decision_button(n) is not None, "Local-network permission dialog did not open")
        rotate(1)
        nodes = wait(lambda n: decision_button(n) is not None and max(int(re.findall(r"\d+", x.get("bounds"))[2]) for x in n)
                     > max(int(re.findall(r"\d+", x.get("bounds"))[3]) for x in n), "Permission dialog did not rotate to landscape")
        time.sleep(0.5)
        tap(decision_button(ui()))
        nodes = wait(lambda n: find(n, text="서버에 연결하지 않았어요") is not None or stored_base() == base,
                     "Permission decision did not finish")
        denied = find(nodes, text="서버에 연결하지 않았어요") is not None
        result.update(permissionActuallyGranted=permission()[0], serverConnectionSaved=stored_base() == base,
                      falseDeniedDialogVisible=args.decision == "allow" and denied, deniedDialogVisible=denied)
        if args.decision == "allow":
            assert result["permissionActuallyGranted"] and result["serverConnectionSaved"] and not denied, "Granted permission did not resume the server connection"
        else:
            assert not result["permissionActuallyGranted"] and stored_base() == "" and denied, "Denied permission did not keep basic mode"
            tap(find(nodes, text="확인"))
        rotate(0)
        expected_mode = "온라인 추천" if args.decision == "allow" else "기본 추천"
        nodes = wait(lambda n: (m := find(n, resource="mode_label")) is not None and m.get("text", "").startswith(expected_mode)
                     and find(n, resource="recommend") is not None and find(n, resource="recommend").get("enabled") == "true",
                     "Recommendation did not become ready after returning to portrait")
        assert find(nodes, text="서버에 연결하지 않았어요") is None, "Permission-denied dialog is still open"
        result["recommendEnabled"] = True
        counts = Counter(requests)
        if args.decision == "allow":
            assert counts[("POST", "/sessions")] == 1 and all(counts[("GET", path)] for path in ("/health", "/catalogue", "/me/feedback", "/me/saved-tracks")), "Connection metadata was not fully read"
        else:
            assert stored_base() == "" and not requests, "Denied connection must not save a server or send HTTP"
        result["fakeServerRequests"] = len(requests)
        assert not failures and not counts[("POST", "/recommendations")], "Unexpected test-server request"
        result["passed"] = True
    finally:
        cleanup_errors = []
        try:
            if started:
                screenshot.parent.mkdir(parents=True, exist_ok=True)
                screenshot.write_bytes(run("exec-out", "screencap", "-p"))
                result["screenshot"] = screenshot.name
        except Exception:
            pass
        try:
            run("shell", "am", "force-stop", PACKAGE)
            if backed_up:
                run("shell", "run-as", PACKAGE, "rm", "-rf", "shared_prefs", "databases")
                if backup.exists():
                    run("shell", "-T", "run-as", PACKAGE, "tar", "xf", "-", data=backup.read_bytes())
                    with tarfile.open(backup) as archive:
                        for entry in archive:
                            if entry.isfile():
                                expected = hashlib.sha256(archive.extractfile(entry).read()).hexdigest()
                                actual = run("shell", "run-as", PACKAGE, "sha256sum", entry.name).decode().split()[0]
                                assert actual == expected, "Restored private file differs from its backup"
        except Exception:
            cleanup_errors.append("Private state restore failed; backup retained at " + str(private))
        for name, value in rotations.items():
            try:
                run("shell", "settings", "delete", "system", name) if value == "null" else run("shell", "settings", "put", "system", name, value)
            except Exception:
                cleanup_errors.append("Rotation restore failed")
        try:
            run("shell", "wm", "user-rotation", *rotation_mode)
        except Exception:
            cleanup_errors.append("Display rotation mode restore failed")
        try:
            run("shell", "pm", "grant" if granted else "revoke", PACKAGE, PERMISSION)
            run("shell", "pm", "clear-permission-flags", PACKAGE, PERMISSION, "user-set", "user-fixed")
            original_flags = [name.lower().replace("_", "-") for name in ("USER_SET", "USER_FIXED") if name in flags]
            if original_flags:
                run("shell", "pm", "set-permission-flags", PACKAGE, PERMISSION, *original_flags)
            restored_grant, restored_flags = permission()
            assert restored_grant == granted and restored_flags & {"USER_SET", "USER_FIXED"} == flags & {"USER_SET", "USER_FIXED"}
        except Exception:
            cleanup_errors.append("Local-network permission restore failed")
        try:
            run("shell", "rm", "-f", ui_path)
        except Exception:
            cleanup_errors.append("Temporary UI dump cleanup failed")
        if server:
            server.shutdown()
            server.server_close()
            server_thread.join(timeout=5)
        result["stateRestored"] = not cleanup_errors
        result["fakeServerRequests"] = len(requests)
        report = ROOT / ("output/checks/" + stem + ".json")
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        if not cleanup_errors:
            shutil.rmtree(private)
        else:
            raise RuntimeError("; ".join(cleanup_errors))
    print("PASS: local-network permission " + args.decision + " survives rotation; private state, permission and orientation restored")


if __name__ == "__main__":
    main()

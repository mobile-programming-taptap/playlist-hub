#!/usr/bin/env python3
"""Check the packaged server's default file database across two JVM processes."""
import json, os, re, subprocess, sys, tempfile, time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 tools/check_server.py <server.jar>")
    jar = Path(sys.argv[1]).resolve(strict=True)
    java_home = os.environ.get("JAVA_HOME")
    java = Path(java_home) / "bin/java" if java_home else None
    java = str(java) if java and java.is_file() else "java"
    env = {k: v for k, v in os.environ.items() if not k.startswith("SPRING_")
           and k not in ("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD", "OPENAI_API_KEY", "OPENAI_ENDPOINT", "OPENAI_MODEL",
                         "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")}
    command = [java, "-jar", str(jar), "--spring.config.import=", "--taptap.openai.key=",
               "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
               "--logging.level.root=INFO"]
    opener = build_opener(ProxyHandler({}))
    processes = []

    def api(base, method, path, token=None, body=None, status=200, timeout=3):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = Request(base + path, None if body is None else json.dumps(body).encode(), headers, method=method)
        try:
            response = opener.open(request, timeout=timeout)
        except HTTPError as error:
            response = error
        with response:
            assert response.status == status, f"{method} {path}: expected {status}, got {response.status}"
            data = response.read()
            return json.loads(data) if data else None

    def start(folder):
        log_path = folder / f"server-{len(processes)}.log"
        log_path.touch(mode=0o600)
        with log_path.open("wb") as log:
            process = subprocess.Popen(command, cwd=folder, env=env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            assert process.poll() is None, "Packaged server exited before becoming ready"
            match = re.search(r"Tomcat started on port (\d+)", log_path.read_text(errors="replace"))
            if match:
                base = "http://127.0.0.1:" + match.group(1)
                try:
                    health = api(base, "GET", "/health", timeout=max(0.001, min(1, deadline - time.monotonic())))
                    assert health["status"] == "ok" and health["aiConfigured"] is False
                    assert (folder / "data/taptap.mv.db").is_file(), "Default file DB was not created"
                    return process, base
                except (URLError, TimeoutError):
                    pass
            time.sleep(0.1)
        raise AssertionError("Packaged server did not become ready within 15 seconds")

    with tempfile.TemporaryDirectory(prefix="taptap-server-check-") as directory:
        try:
            process, base = start(Path(directory))
            token = api(base, "POST", "/sessions", status=201)["token"]
            other = api(base, "POST", "/sessions", status=201)["token"]
            tracks = api(base, "GET", "/catalogue", token)[:2]
            assert len(tracks) == 2
            feedback = {tracks[0]["id"]: 1, tracks[1]["id"]: -1}
            for track_id, reaction in feedback.items():
                api(base, "PUT", "/me/feedback/" + track_id, token, {"reaction": reaction})
            playlist = api(base, "POST", "/playlists", token, {"title": "재시작 검사"}, 201)
            path = "/playlists/" + playlist["id"]
            items = [api(base, "POST", path + "/items", token, {"trackId": t["id"]}) for t in tracks]
            api(base, "PUT", path + "/items/order", token, {"itemIds": [i["id"] for i in reversed(items)]}, 204)
            api(base, "GET", path, other, status=404)
            api(base, "POST", path + "/items", token, {"trackId": tracks[0]["id"]})
            snapshot = api(base, "GET", path, token)
            assert snapshot["title"] == "재시작 검사"
            assert [i["track"] for i in snapshot["items"]] == list(reversed(tracks))
            assert api(base, "GET", "/me/feedback", token) == feedback
            process.terminate()
            process.wait(timeout=10)
            restarted, base = start(Path(directory))
            assert restarted.pid != process.pid
            assert api(base, "GET", "/me/feedback", token) == feedback
            assert api(base, "GET", path, token) == snapshot
            api(base, "GET", path, other, status=404)
            api(base, "POST", path + "/items", token, {"trackId": tracks[0]["id"]})
            assert api(base, "GET", path, token) == snapshot
            restarted.terminate()
            restarted.wait(timeout=10)
        finally:
            for child in processes:
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=5)
    print("PASS: packaged server, default file DB, process restart, ownership, feedback, track snapshots and duplicates")


if __name__ == "__main__":
    main()

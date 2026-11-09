#!/usr/bin/env python3
"""Check the production Compose stack through local HTTPS using only disposable data."""
import argparse, hashlib, json, os, secrets, ssl, subprocess, tempfile, time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, HTTPSHandler, HTTPRedirectHandler, ProxyHandler
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="taptap-deployment-check:local", help="Already-built backend image")
    args = parser.parse_args()
    project = "taptap-https-check-" + uuid4().hex[:12]
    proof = {"checkedAt": datetime.now(timezone.utc).isoformat(), "passed": False,
             "runtime": "Production Compose, Caddy 2, PostgreSQL 15, local HTTPS with hostname and private CA verification",
             "actualPublicHttpsVerified": False, "actualProviderOAuthVerified": False,
             "hostTrustedCertificatesChanged": False, "checks": []}
    destination = ROOT / "output/checks/https-local-full.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=project + "-") as folder:
        private = Path(folder)
        (private / "app.env").touch(mode=0o600)
        # Keep the user's Docker context/config; override only this stack's interpolation inputs.
        env = dict(os.environ, DOMAIN="localhost", DATABASE_USER="taptap_check",
                   DATABASE_PASSWORD=secrets.token_urlsafe(30), TAPTAP_ENV_FILE=str(private / "app.env"))
        check_env = private / "check.env"
        check_env.touch(mode=0o600)
        check_env.write_text("\n".join(key + "=" + env[key] for key in
                            ("DOMAIN", "DATABASE_USER", "DATABASE_PASSWORD", "TAPTAP_ENV_FILE")) + "\n")
        override = private / "override.yaml"
        override.write_text("services:\n  app:\n    image: " + json.dumps(args.image) + "\n    restart: \"no\"\n"
                            "  database:\n    restart: \"no\"\n  caddy:\n    restart: \"no\"\n"
                            "    ports: !override\n      - \"127.0.0.1::80\"\n      - \"127.0.0.1::443\"\n")
        command = ["docker", "compose", "-p", project, "--env-file", str(check_env),
                   "-f", str(ROOT / "deploy/compose.yaml"), "-f", str(override)]

        def run(command, data=None):
            result = subprocess.run(command, env=env, cwd=ROOT, input=data, capture_output=True, text=True, timeout=180)
            if result.returncode:
                # Do not print settings, request bodies, tokens or container logs on failure.
                raise RuntimeError("Docker operation failed: " + " ".join(command[:3]))
            return result.stdout.strip()

        def docker(*arguments, data=None):
            return run(["docker", *arguments], data)

        def compose(*arguments):
            return run(command + list(arguments))

        def port(number):
            return compose("port", "caddy", str(number)).rsplit(":", 1)[1]

        token = None
        password = secrets.token_urlsafe(25)
        client_names = []

        def api(method, path, body=None, expected=200, html=False, extra_headers=None):
            headers = {"Content-Type": "application/json", **(extra_headers or {})}
            if token:
                headers["Authorization"] = "Bearer " + token
            try:
                response = client.open(Request(base + path, None if body is None else json.dumps(body).encode(),
                                               headers, method=method), timeout=5)
            except HTTPError as error:
                response = error
            with response:
                assert response.status == expected, f"{method} {path}: expected {expected}, got {response.status}"
                value = response.read()
                return (value.decode(), dict(response.headers)) if html else json.loads(value) if value else None

        def ready():
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                try:
                    health = api("GET", "/health")
                    if health["status"] == "ok":
                        return health
                except (URLError, TimeoutError, AssertionError, ConnectionError):
                    time.sleep(.2)
            raise AssertionError("HTTPS application did not become ready")

        try:
            proof["imageId"] = docker("image", "inspect", args.image, "--format", "{{.Id}}")
            compose("config", "--quiet")
            compose("up", "-d", "--no-build")
            containers = {name: compose("ps", "-q", name) for name in ("app", "database", "caddy")}
            certificate = private / "root.crt"
            for attempt in range(100):
                try:
                    docker("cp", containers["caddy"] + ":/data/caddy/pki/authorities/local/root.crt", str(certificate))
                    break
                except RuntimeError:
                    time.sleep(.2)
            else:
                raise AssertionError("Caddy did not produce its local CA certificate")
            context = ssl.create_default_context(cafile=str(certificate))
            client = build_opener(ProxyHandler({}), HTTPSHandler(context=context))
            base = "https://localhost:" + port(443)
            health = ready()
            assert health["catalogueSize"] == 40 and health["aiConfigured"] is False
            home, home_headers = api("GET", "/", html=True)
            privacy, privacy_headers = api("GET", "/privacy", html=True)
            assert "TapTap" in home and 'href="/privacy"' in home
            assert "OpenAI" in privacy and "YouTube" in privacy and "30일" in privacy
            for headers in (home_headers, privacy_headers):
                assert headers.get("Content-Security-Policy") and headers.get("X-Content-Type-Options") == "nosniff"
                assert not any(name.lower() == "set-cookie" for name in headers)
            assert docker("inspect", containers["app"], "--format", "{{.HostConfig.ReadonlyRootfs}}") == "true"
            assert docker("exec", containers["app"], "id", "-u") == "10001"
            docker("cp", containers["app"] + ":/app/taptap.jar", str(private / "container.jar"))
            proof["containerJarSha256"] = hashlib.sha256((private / "container.jar").read_bytes()).hexdigest()
            host_jar = ROOT / "output/server/taptap-backend.jar"
            proof["hostJarSha256"] = hashlib.sha256(host_jar.read_bytes()).hexdigest() if host_jar.is_file() else None
            if proof["hostJarSha256"] is not None:
                assert proof["hostJarSha256"] == proof["containerJarSha256"], "The image does not match the current packaged host JAR"
            for name in ("app", "database"):
                network_settings = json.loads(docker("inspect", containers[name], "--format", "{{json .NetworkSettings}}"))
                assert not any(network_settings["Ports"].values()), name + " exposes a host port"
            account = api("POST", "/accounts/register", {"email": project + "@example.invalid", "password": password, "name": "HTTPS check"}, 201)
            token = account["token"]
            recommendation = api("POST", "/recommendations", {"prompt": "음악 추천"})
            assert recommendation["mode"] == "basic" and len(recommendation["tracks"]) == 8
            list_id = str(uuid4())
            api("POST", "/playlists", {"id": list_id, "title": "HTTPS persistence"}, 201)
            api("POST", "/playlists/" + list_id + "/items", {"trackId": recommendation["tracks"][0]["track"]["id"]})
            publication = api("PUT", "/playlists/" + list_id + "/publication", {"public": True})
            share_id = publication["shareId"]
            original_token, token = token, None
            api("GET", "/playlists/" + list_id, expected=401)
            content, headers = api("GET", publication["sharePath"], html=True)
            assert "HTTPS persistence" in content and headers.get("Content-Security-Policy")
            token = original_token
            plain = build_opener(ProxyHandler({}), NoRedirect())
            try:
                plain.open("http://localhost:" + port(80) + "/health", timeout=5)
            except HTTPError as redirect:
                assert redirect.code == 308 and redirect.headers["Location"] == "https://localhost/health"
            else:
                raise AssertionError("HTTP was not redirected to HTTPS")
            before = api("GET", "/playlists/" + list_id)
            original_certificate = certificate.read_bytes()
            compose("restart", "app", "database", "caddy")
            base = "https://localhost:" + port(443)
            ready()
            docker("cp", containers["caddy"] + ":/data/caddy/pki/authorities/local/root.crt", str(private / "restart-root.crt"))
            assert original_certificate == (private / "restart-root.crt").read_bytes()
            assert api("GET", "/playlists/" + list_id) == before
            assert api("GET", "/public/playlists/" + share_id)["size"] == 1
            api("PUT", "/playlists/" + list_id + "/publication", {"public": False})
            api("GET", "/share/" + share_id, expected=404)
            api("DELETE", "/accounts/me", {"password": password}, 204)
            token = None
            email = project + "-rate@example.invalid"
            account = api("POST", "/accounts/register", {"email": email, "password": password, "name": "Rate check"}, 201)
            token = account["token"]
            for attempt in range(40):
                api("POST", "/accounts/login", {"email": f"missing-{attempt}@example.invalid", "password": password}, 401)
            login = {"email": email, "password": password}
            api("POST", "/accounts/login", login, 429, extra_headers={"X-Forwarded-For": "198.51.100.231"})
            caddy = json.loads(docker("inspect", containers["caddy"], "--format", "{{json .NetworkSettings}}"))
            network = project + "_edge"
            address = caddy["Networks"][network]["IPAddress"]

            def second_client(method, path, body, expected, bearer=None):
                name = project + "-client-" + uuid4().hex[:8]
                client_names.append(name)
                settings = {"url": "https://localhost" + path, "resolve": "localhost:443:" + address,
                            "cacert": "/tmp/root.crt", "request": method, "output": "/dev/null",
                            "write-out": "%{http_code}", "max-time": "15", "data": json.dumps(body)}
                config = "silent\nshow-error\n" + "\n".join(key + " = " + json.dumps(value) for key, value in settings.items())
                config += '\nheader = "Content-Type: application/json"\n'
                if bearer:
                    config += "header = " + json.dumps("Authorization: Bearer " + bearer) + "\n"
                status = docker("run", "--rm", "-i", "--name", name, "--network", network, "--mount", "type=bind,src=" + str(certificate) + ",dst=/tmp/root.crt,readonly",
                                "--entrypoint", "/usr/bin/curl", "eclipse-temurin:21-jdk-jammy", "--config", "-", data=config)
                client_names.remove(name)
                assert status == str(expected), f"Independent HTTPS client {method} {path}: expected {expected}, got {status}"

            second_client("POST", "/accounts/login", login, 200)
            second_client("DELETE", "/accounts/me", {"password": password}, 204, token)
            proof["checks"] = ["Production Compose validates without printing private settings",
                               "App and database expose no host ports", "App uses a read-only filesystem and UID 10001",
                               "Container JAR hash and available packaged host JAR match", "TLS hostname and private CA verification", "Anonymous app introduction and privacy pages without cookies",
                               "HTTP redirects to HTTPS", "Bearer auth, recommendation, playlist and anonymous public share through TLS",
                               "App, database and Caddy restart retain session, playlist, share and CA",
                               "Unpublication and account deletion", "Spoofed X-Forwarded-For cannot bypass login IP limits",
                               "A different client can log in after the first client reaches its IP limit"]
            proof["passed"] = True
        finally:
            try:
                for name in client_names:
                    try:
                        subprocess.run(["docker", "rm", "-f", name], env=env, cwd=ROOT, capture_output=True, timeout=30)
                    except subprocess.TimeoutExpired:
                        pass  # Compose cleanup reports failure if a timed-out client still holds this network.
                compose("down", "-v", "--remove-orphans")
                proof["temporaryStackRemoved"] = True
            except Exception:
                proof["passed"] = False
                proof["temporaryStackRemoved"] = False
                proof["cleanupProject"] = project
                raise
            finally:
                destination.write_text(json.dumps(proof, indent=2) + "\n")
    print(json.dumps(proof), flush=True)


if __name__ == "__main__":
    main()

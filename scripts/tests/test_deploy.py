"""Exercise deployment failure boundaries without touching a real server."""
import json
import os
from pathlib import Path
import shutil
import shlex
import subprocess
import tarfile
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "deploy.sh"
SHA = "a" * 40
FAKE_TOOL = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ["CALL_LOG"], "a") as log:
    log.write(json.dumps([name, *args]) + "\n")
mode = os.environ.get("RUNTIME", "none")
repo = os.environ["TEST_REPO"]
if name == "git":
    print(os.environ.get("CHECKOUT_SHA", "a" * 40))
elif name == "docker":
    if args[0] == "inspect":
        if mode != "compose": sys.exit(1)
        if "--format" in args:
            template = args[args.index("--format") + 1]
            if "config_files" in template: print(repo + "/custom-compose.yml")
            elif "working_dir" in template: print(os.environ.get("COMPOSE_REPO", repo))
            else: print("existing-blog-project")
    elif "build" in args and os.environ.get("BUILD_FAIL") == "1":
        sys.exit(42)
elif name == "pm2":
    if mode != "pm2": sys.exit(1)
    if args[0] == "jlist":
        path = os.environ.get("PM2_SCRIPT", repo + "/blog-backend-node/src/index.js")
        print(json.dumps([{"name": "blog-backend", "pm2_env": {"pm_exec_path": path}}]))
elif name == "npm":
    if os.environ.get("NPM_FAIL") == "1": sys.exit(43)
elif name == "curl":
    if os.environ.get("HEALTH_FAIL") == "1": sys.exit(22)
    print(os.environ.get("API_BODY", '{"success":true,"data":{}}'))
'''


class DeployTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.repo = self.root / "repo"
        (self.repo / "scripts").mkdir(parents=True)
        shutil.copy2(SCRIPT, self.repo / "scripts/deploy.sh")
        (self.repo / "blog-backend-node/src").mkdir(parents=True)
        (self.repo / "blog-backend-node/src/index.js").write_text("// backend\n")
        (self.repo / "custom-compose.yml").write_text("services: {}\n")
        (self.repo / ".env.prod").write_text("BLOG_ADMIN_TOKEN=preserve-me\n")
        self.frontend = self.root / "www"
        (self.frontend / "assets").mkdir(parents=True)
        (self.frontend / "index.html").write_text("old-index")
        (self.frontend / "assets/old.js").write_text("old-asset")
        self.bundle = self.root / "bundle"
        self.bundle.mkdir()
        (self.bundle / "commit").write_text(SHA + "\n")
        built = self.root / "dist"
        (built / "assets").mkdir(parents=True)
        (built / "index.html").write_text("new-index")
        (built / "assets/new.js").write_text("new-asset")
        with tarfile.open(self.bundle / "frontend.tar.gz", "w:gz") as archive:
            archive.add(built, arcname=".")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("docker", "pm2", "npm", "curl", "git"):
            tool = self.bin / name
            tool.write_text(FAKE_TOOL)
            tool.chmod(0o755)
        self.log = self.root / "calls.jsonl"
        self.env = {
            **os.environ,
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "CALL_LOG": str(self.log),
            "TEST_REPO": str(self.repo),
            "DEPLOY_FRONTEND_DIR": str(self.frontend),
            "DEPLOY_HEALTHCHECK_URL": "http://localhost:1234/",
        }

    def run_deploy(self, mode="none", **env):
        return subprocess.run(
            ["bash", str(self.repo / "scripts/deploy.sh"), str(self.bundle), SHA],
            env={**self.env, "RUNTIME": mode, **env},
            capture_output=True, text=True, timeout=20,
        )

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_compose_keeps_existing_project_and_config(self):
        result = self.run_deploy("compose")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls()
        build = next(call for call in calls if call[0] == "docker" and "build" in call)
        update = next(call for call in calls if call[0] == "docker" and "up" in call)
        self.assertIn("existing-blog-project", build)
        self.assertIn(str(self.repo / "custom-compose.yml"), build)
        self.assertIn(".env.prod", build)
        self.assertLess(calls.index(build), calls.index(update))
        self.assertIn("--no-build", update)
        self.assertNotIn("down", [arg for call in calls for arg in call])
        self.assertIn("preserve-me", (self.repo / ".env.prod").read_text())

    def test_failed_image_build_never_replaces_containers(self):
        result = self.run_deploy("compose", BUILD_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any("up" in call for call in self.calls()))
        self.assertFalse(any(call[0] == "curl" for call in self.calls()))

    def test_compose_from_another_checkout_is_rejected(self):
        result = self.run_deploy("compose", COMPOSE_REPO=str(self.root))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("different checkout", result.stderr)
        self.assertFalse(any("build" in call for call in self.calls()))

    def test_pm2_publishes_new_assets_and_preserves_old_assets(self):
        result = self.run_deploy("pm2")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.frontend / "index.html").read_text(), "new-index")
        self.assertEqual((self.frontend / "assets/new.js").read_text(), "new-asset")
        self.assertEqual((self.frontend / "assets/old.js").read_text(), "old-asset")
        self.assertIn(["pm2", "reload", "blog-backend"], self.calls())
        self.assertFalse(any("delete" in call or "--update-env" in call for call in self.calls()))

    def test_failed_dependency_install_does_not_publish(self):
        result = self.run_deploy("pm2", NPM_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.frontend / "index.html").read_text(), "old-index")
        self.assertNotIn(["pm2", "reload", "blog-backend"], self.calls())

    def test_pm2_from_another_checkout_is_rejected(self):
        other_script = self.root / "another-app.js"
        other_script.write_text("// another app\n")
        result = self.run_deploy("pm2", PM2_SCRIPT=str(other_script))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.frontend / "index.html").read_text(), "old-index")
        self.assertNotIn(["pm2", "reload", "blog-backend"], self.calls())

    def test_unknown_runtime_does_not_replace_services(self):
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No existing blog", result.stderr)
        self.assertEqual((self.frontend / "index.html").read_text(), "old-index")

    def test_wrong_bundle_is_rejected_before_service_changes(self):
        (self.bundle / "commit").write_text("b" * 40)
        result = self.run_deploy("compose")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_wrong_checkout_is_rejected(self):
        result = self.run_deploy("compose", CHECKOUT_SHA="b" * 40)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(call[0] == "docker" for call in self.calls()))

    def test_failed_health_check_marks_deployment_failed(self):
        result = self.run_deploy("compose", HEALTH_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("Successfully deployed", result.stdout)

    def test_https_health_check_uses_the_live_virtual_host(self):
        (self.repo / "blog-frontend").mkdir()
        (self.repo / "blog-frontend/nginx.conf").write_text(
            "server {\n listen 443 ssl;\n server_name blog.example.test;\n}\n"
        )
        result = self.run_deploy("compose", DEPLOY_HEALTHCHECK_URL="")
        self.assertEqual(result.returncode, 0, result.stderr)
        curls = [call for call in self.calls() if call[0] == "curl"]
        self.assertIn("https://blog.example.test/", curls[0])
        self.assertIn("blog.example.test:443:127.0.0.1", curls[0])
        self.assertFalse(any("--insecure" in call or "-k" in call for call in curls))

    def test_java_api_success_is_accepted(self):
        result = self.run_deploy("compose", API_BODY='{"code":200,"message":"success","data":{}}')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Successfully deployed", result.stdout)

    def test_api_application_error_is_rejected_even_with_http_200(self):
        result = self.run_deploy("compose", API_BODY='{"code":500,"message":"error","data":{"code":200}}')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("Successfully deployed", result.stdout)

    def test_spa_fallback_is_not_mistaken_for_a_healthy_api(self):
        result = self.run_deploy("compose", API_BODY='<html>site shell</html>')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("Successfully deployed", result.stdout)


class CheckoutTests(unittest.TestCase):
    """Use real Git to verify local production configuration survives a fast-forward."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Deployment Test")
        self.git("config", "user.email", "deployment-test@example.test")
        (self.repo / "blog-frontend/src").mkdir(parents=True)
        (self.repo / "blog-frontend/nginx.conf").write_text("original nginx\n")
        (self.repo / "docker-compose.yml").write_text("original compose\n")
        (self.repo / "blog-frontend/src/app.js").write_text("original app\n")
        self.git("add", ".")
        self.git("commit", "-m", "base")
        workflow = SCRIPT.parents[1] / ".github/workflows/deploy-articles.yml"
        line = next(line.strip() for line in workflow.read_text().splitlines()
                    if line.strip().startswith("if ! git diff --quiet HEAD --"))
        self.guard = shlex.split(line.removeprefix("if ! ").removesuffix("; then"))

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, check=True,
                              capture_output=True, text=True)

    def test_local_tls_configuration_survives_fast_forward(self):
        self.git("checkout", "-b", "release")
        (self.repo / "workflow.txt").write_text("new deployment workflow\n")
        self.git("add", ".")
        self.git("commit", "-m", "release")
        self.git("checkout", "main")
        (self.repo / "blog-frontend/nginx.conf").write_text("live HTTPS settings\n")
        (self.repo / "docker-compose.yml").write_text("live certificate mounts\n")
        guard = subprocess.run(self.guard, cwd=self.repo, capture_output=True)
        self.assertEqual(guard.returncode, 0)
        self.git("merge", "--ff-only", "release")
        self.assertEqual((self.repo / "blog-frontend/nginx.conf").read_text(), "live HTTPS settings\n")
        self.assertEqual((self.repo / "docker-compose.yml").read_text(), "live certificate mounts\n")
        self.assertEqual(self.git("rev-parse", "HEAD").stdout,
                         self.git("rev-parse", "release").stdout)

    def test_uncommitted_application_code_still_stops_deployment(self):
        (self.repo / "blog-frontend/src/app.js").write_text("uncommitted feature\n")
        guard = subprocess.run(self.guard, cwd=self.repo, capture_output=True)
        self.assertEqual(guard.returncode, 1)


if __name__ == "__main__":
    unittest.main()

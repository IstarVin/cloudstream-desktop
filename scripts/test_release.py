"""Release behavior tests using disposable Git repositories and a local bare remote."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from release import compare, parse

SCRIPT = Path(__file__).with_name("release.py").resolve()


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / "repo"
        self.remote = Path(self.temp.name) / "origin.git"
        self.repo.mkdir()
        self.git("init", "-b", "main")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "user.name", "Release Test")
        self.git("commit", "--allow-empty", "-m", "Initial")
        subprocess.run(["git", "init", "--bare", str(self.remote)], check=True, capture_output=True)
        self.git("remote", "add", "origin", str(self.remote))

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, text=True, stderr=subprocess.DEVNULL).strip()

    def run_script(self, *args, success=True):
        result = subprocess.run([sys.executable, str(SCRIPT), *args], cwd=self.repo, text=True, capture_output=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(result.stdout)
        self.assertNotEqual(result.returncode, 0)
        return result.stderr

    def test_independent_versions_and_numeric_order(self):
        for tag in ("android-v0.1.9", "android-v0.1.10", "server-v2.3.0", "android-vgarbage"):
            self.git("tag", tag)
        items = self.run_script("plan", "both", "--android", "patch", "--server", "minor")
        self.assertEqual([i["version"] for i in items], ["0.1.11", "2.4.0"])
        self.assertEqual(items[0]["previous_tag"], "android-v0.1.10")

    def test_first_release_and_preserved_notes(self):
        items = self.run_script("prepare", "android")
        self.assertEqual(items[0]["version"], "0.0.1")
        notes = self.repo / items[0]["notes"]
        notes.write_text("Finished notes\n")
        self.run_script("prepare", "android")
        self.assertEqual(notes.read_text(), "Finished notes\n")
        self.assertEqual(self.git("tag"), "")

    def test_semver_and_prerelease_promotion(self):
        self.assertLess(compare("1.0.0-beta.2", "1.0.0-beta.10"), 0)
        self.assertLess(compare("1.0.0-rc.1", "1.0.0"), 0)
        for invalid in ("01.0.0", "1.0", "1.0.0-01"):
            with self.assertRaises(ValueError):
                parse(invalid)
        self.git("tag", "android-v1.0.0-rc.1")
        self.assertEqual(self.run_script("plan", "android", "1.0.0")[0]["version"], "1.0.0")
        self.run_script("plan", "android", "0.9.0", success=False)

    def test_publish_preflight_has_no_tag_side_effects(self):
        self.run_script("publish", "both", success=False)
        items = self.run_script("prepare", "both", "0.1.0")
        # An unfinished second component must prevent even the first tag being created.
        (self.repo / items[0]["notes"]).write_text("Finished Android notes\n")
        self.git("add", ".")
        self.git("commit", "-m", "Prepare")
        self.assertIn("finished release notes", self.run_script("publish", "both", "0.1.0", success=False))
        self.assertEqual(self.git("tag"), "")

    def test_publish_both_to_same_commit_and_reject_duplicates(self):
        items = self.run_script("prepare", "both", "--android", "0.1.0", "--server", "0.2.0")
        for item in items:
            (self.repo / item["notes"]).write_text("Reviewed release changes\n")
        self.git("add", ".")
        self.git("commit", "-m", "Prepare releases")
        self.run_script("publish", "both", "--android", "0.1.0", "--server", "0.2.0")
        for item in items:
            remote_commit = subprocess.check_output(
                ["git", "--git-dir", str(self.remote), "rev-parse", f"{item['tag']}^{{commit}}"], text=True).strip()
            self.assertEqual(remote_commit, self.git("rev-parse", "HEAD"))
        self.assertIn("must be newer", self.run_script("publish", "android", "0.1.0", success=False))

    def prepare_and_commit(self, **versions):
        args = [a for k, v in versions.items() for a in (f"--{k}", v)]
        items = self.run_script("prepare", "both", *args)
        for item in items:
            (self.repo / item["notes"]).write_text("Reviewed release changes\n")
        self.git("add", ".")
        self.git("commit", "-m", "Prepare")
        return args

    def test_plan_sees_tags_only_on_origin(self):
        other = Path(self.temp.name) / "other"
        subprocess.run(["git", "clone", str(self.remote), str(other)], check=True, capture_output=True)
        subprocess.run(["git", "-C", str(other), "commit", "--allow-empty", "-m", "x"], check=True, capture_output=True,
                       env={**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@e",
                            "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@e"})
        subprocess.run(["git", "-C", str(other), "tag", "android-v1.4.0"], check=True)
        subprocess.run(["git", "-C", str(other), "push", "origin", "android-v1.4.0"], check=True, capture_output=True)
        self.assertEqual(self.run_script("plan", "android")[0]["version"], "1.4.1")

    def test_publish_rejects_main_behind_origin(self):
        args = self.prepare_and_commit(android="0.1.0", server="0.2.0")
        self.git("push", "origin", "HEAD:refs/heads/main")
        self.git("commit", "--allow-empty", "-m", "ahead")
        self.git("push", "origin", "HEAD:refs/heads/main")
        self.git("reset", "--hard", "HEAD~1")
        self.assertIn("behind", self.run_script("publish", "both", *args, success=False))
        self.assertEqual(self.git("tag"), "")

    def test_failed_push_rolls_back_local_tags(self):
        args = self.prepare_and_commit(android="0.1.0", server="0.2.0")
        hook = self.remote / "hooks" / "pre-receive"
        hook.write_text("#!/bin/sh\nexit 1\n")
        hook.chmod(0o755)
        self.run_script("publish", "both", *args, success=False)
        self.assertEqual(self.git("tag"), "")

    def test_existing_local_tag_blocks_publish_without_tagging(self):
        args = self.prepare_and_commit(android="0.1.0", server="0.2.0")
        self.git("tag", "server-v0.2.0")
        self.assertIn("must be newer", self.run_script("publish", "both", *args, success=False))
        self.assertEqual(self.git("tag"), "server-v0.2.0")


if __name__ == "__main__":
    unittest.main()

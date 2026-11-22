"""Use real local git repositories to exercise moving branches and explicit pair selection."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from freeze_pair import freeze, git, validate_ref


class FreezePairTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        subprocess.run(["git", "init", "-q", "-b", "main", str(self.source)], check=True)
        git(self.source, "config", "user.email", "pair-test@example.invalid")
        git(self.source, "config", "user.name", "Pair test")
        self.front = self.commit("frontend.txt", "first")
        git(self.source, "checkout", "-q", "-b", "backend-main")
        self.back = self.commit("backend.txt", "backend")
        self.repo = self.root / "runner"
        subprocess.run(["git", "clone", "-q", str(self.source), str(self.repo)], check=True)
        self.mini = self.root / "mini"

    def commit(self, name, contents):
        (self.source / name).write_text(contents)
        git(self.source, "add", name)
        git(self.source, "commit", "-qm", name)
        return git(self.source, "rev-parse", "HEAD")

    def test_branch_moves_after_freeze_do_not_change_the_pair(self):
        pair = freeze(self.repo, self.mini, "main", "backend-main")
        git(self.source, "checkout", "-q", "main")
        later = self.commit("frontend.txt", "later")
        self.assertNotEqual(later, pair["frontendCommit"])
        self.assertEqual(pair, {"frontendCommit": self.front, "backendCommit": self.back})
        self.assertEqual(git(self.mini, "rev-parse", "HEAD"), self.front)
        self.assertEqual(git(self.repo, "rev-parse", "HEAD"), self.back)

    def test_explicit_frontend_commit_is_not_replaced_by_branch_tip(self):
        git(self.source, "checkout", "-q", "main")
        self.commit("frontend.txt", "unselected")
        pair = freeze(self.repo, self.mini, self.front, self.back)
        self.assertEqual(pair["frontendCommit"], self.front)
        self.assertEqual((self.mini / "frontend.txt").read_text(), "first")

    def test_incorrect_backend_checkout_stops_before_build(self):
        with self.assertRaisesRegex(ValueError, "Backend checkout"):
            freeze(self.repo, self.mini, "main", self.front)
        self.assertFalse(self.mini.exists())

    def test_only_known_branch_or_full_sha_is_accepted(self):
        for invalid in ("HEAD", "deadbeef", "--upload-pack=bad", "main\nBAD=1", "refs/heads/main"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                validate_ref(invalid, "main")


if __name__ == "__main__":
    unittest.main()

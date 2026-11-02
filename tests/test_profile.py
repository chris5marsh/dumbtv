"""scripts/profile.sh against a pretend Fire TV: saving lists the right apps, applying adds
what is missing, removes only what the owner agreed to, and never touches dumbtv's own apps,
Amazon's, or anything on a never-disable list.

Run: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CONF_ON_TV = "/sdcard/Android/data/tv.dumbtv.home/files/home.conf"


def app(installer="com.amazon.venezia", launchable=True, system=False, enabled=True):
    return {"system": system, "enabled": enabled, "launchable": launchable, "installer": installer}


class ProfileTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.name = f"test-{os.getpid()}"
        self.profile = ROOT / "profiles" / self.name
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.addCleanup(shutil.rmtree, self.profile, True)

    def run_profile(self, packages, *args, answers="", files=None, unreadable=()):
        state = self.tmp / "state.json"
        state.write_text(json.dumps({"packages": packages, "sdk": 28, "files": files or {},
                                     "unreadable": list(unreadable), "pushed": str(self.tmp / "pushed")}))
        log = self.tmp / "commands.log"
        log.write_text("")
        env = dict(os.environ, ADB=str(ROOT / "tests" / "fake_tv.py"), ADB_SERIAL="test-tv",
                   FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log), CONF=str(self.tmp / "none.conf"))
        result = subprocess.run(["bash", str(ROOT / "scripts" / "profile.sh"), *args],
                                env=env, input=answers, capture_output=True, text=True, timeout=120)
        self.output = result.stdout + result.stderr
        return result.returncode, log.read_text().splitlines(), json.loads(state.read_text())

    def save(self, packages, **kw):
        code, _, _ = self.run_profile(packages, "save", self.name, **kw)
        self.assertEqual(code, 0, self.output)

    def write_profile(self, apps, home_conf="apps.order=com.netflix.ninja\n"):
        self.profile.mkdir(parents=True)
        (self.profile / "apps.txt").write_text("".join(f"{p} {s}\n" for p, s in apps))
        if home_conf is not None:
            (self.profile / "home.conf").write_text(home_conf)

    def test_save_lists_installed_apps_with_their_source(self):
        self.save({
            "com.netflix.ninja": app(),
            "ar.tvplayer.tv": app(installer="com.android.packageinstaller"),
            "tv.dumbtv.home": app(installer="null"),
            "org.smarttube.stable": app(installer="null"),
            "dnsfilter.android": app(installer="null", launchable=False),
            "com.amazon.tv.settings.v2.overlay": app(installer="null", launchable=False),
            "com.amazon.tv.launcher": app(system=True),
        }, files={CONF_ON_TV: "tiles=small\n"})
        listed = {line.split()[0]: line.split()[1] for line in (self.profile / "apps.txt").read_text().splitlines()
                  if not line.startswith("#")}
        self.assertEqual(listed, {"com.netflix.ninja": "appstore", "ar.tvplayer.tv": "sideload"})
        self.assertEqual((self.profile / "home.conf").read_text(), "tiles=small\n")
        self.assertTrue((self.profile / "apks" / "ar.tvplayer.tv").is_dir(), "a sideloaded app is copied")
        self.assertFalse((self.profile / "apks" / "com.netflix.ninja").exists(), "Appstore apps are not copied")
        self.assertTrue((self.profile / "apks" / "tv.dumbtv.home").is_dir(), "the launcher build is copied")

    def test_save_without_readable_settings_still_saves_the_apps(self):
        self.save({"com.netflix.ninja": app()}, unreadable=[CONF_ON_TV])
        self.assertTrue((self.profile / "apps.txt").is_file())
        self.assertFalse((self.profile / "home.conf").exists())
        self.assertIn("Fire OS 8", self.output)

    def test_apply_adds_missing_apps_removes_extras_and_pushes_settings(self):
        self.write_profile([("com.netflix.ninja", "appstore"), ("ar.tvplayer.tv", "sideload"),
                            ("com.disney.disneyplus", "appstore")])
        (self.profile / "apks" / "ar.tvplayer.tv").mkdir(parents=True)
        (self.profile / "apks" / "ar.tvplayer.tv" / "base.apk").write_text("apk")
        code, log, state = self.run_profile({
            "com.netflix.ninja": app(),
            "com.spotify.tv.android": app(),
            "tv.dumbtv.home": app(installer="null"),
            "org.smarttube.stable": app(installer="null"),
            "com.amazon.tv.launcher": app(system=True),
        }, "apply", self.name, "-y")
        self.assertEqual(code, 0, self.output)
        self.assertIn("ar.tvplayer.tv", state["installed"])
        self.assertIn("am start -a android.intent.action.VIEW -d amzn://apps/android?p=com.disney.disneyplus", log)
        self.assertIn("pm uninstall --user 0 com.spotify.tv.android", log)
        self.assertNotIn("com.spotify.tv.android", state["packages"])
        for kept in ("tv.dumbtv.home", "org.smarttube.stable", "com.amazon.tv.launcher", "com.netflix.ninja"):
            self.assertIn(kept, state["packages"])
        self.assertEqual((self.tmp / "pushed" / "home.conf").read_text(), "apps.order=com.netflix.ninja\n")

    def test_apply_keeps_extras_when_the_owner_says_no(self):
        self.write_profile([("com.netflix.ninja", "appstore")])
        code, log, state = self.run_profile({
            "com.netflix.ninja": app(), "com.spotify.tv.android": app(),
            "tv.dumbtv.home": app(installer="null"),
        }, "apply", self.name, answers="y\nn\n")
        self.assertEqual(code, 0, self.output)
        self.assertIn("com.spotify.tv.android", state["packages"])
        self.assertFalse([c for c in log if "uninstall" in c])

    def test_dry_run_changes_nothing(self):
        self.write_profile([("com.disney.disneyplus", "appstore")])
        code, log, state = self.run_profile({
            "com.spotify.tv.android": app(), "tv.dumbtv.home": app(installer="null"),
        }, "apply", self.name, "--dry-run")
        self.assertEqual(code, 0, self.output)
        self.assertIn("com.spotify.tv.android", state["packages"])
        self.assertFalse([c for c in log if c.startswith(("pm uninstall", "am start", "mkdir"))])
        self.assertFalse((self.tmp / "pushed").exists())

    def test_apply_needs_dumbtv_home_or_a_copy_of_it(self):
        self.write_profile([("com.netflix.ninja", "appstore")])
        code, _, _ = self.run_profile({"com.netflix.ninja": app()}, "apply", self.name, "-y")
        self.assertNotEqual(code, 0)
        self.assertIn("all.sh", self.output)

    def test_a_never_disable_package_is_not_offered_for_removal(self):
        guarded = next(line.split("#")[0].split()[0] for line in
                       (ROOT / "data" / "fireos" / "never-disable.txt").read_text().splitlines()
                       if line.split("#")[0].strip())
        self.write_profile([])
        code, log, state = self.run_profile({
            guarded: app(), "tv.dumbtv.home": app(installer="null"),
        }, "apply", self.name, "-y")
        self.assertEqual(code, 0, self.output)
        self.assertIn(guarded, state["packages"])

    def test_a_bad_profile_name_is_refused(self):
        code, _, _ = self.run_profile({}, "save", "../escape")
        self.assertNotEqual(code, 0)


if __name__ == "__main__":
    unittest.main()

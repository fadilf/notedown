import unittest

from release_version import read_version, should_release


def source(version, code):
    return f'versionCode = {code}; versionName = "{version}"'


class ReleaseVersionTest(unittest.TestCase):
    def test_minor_increase(self):
        self.assertTrue(should_release(source("1.0.2", 3), source("1.1.0", 4)))

    def test_major_rollover(self):
        self.assertTrue(should_release(source("1.9.2", 3), source("2.0.0", 4)))

    def test_numeric_comparison(self):
        self.assertTrue(should_release(source("1.9.0", 3), source("1.10.0", 4)))

    def test_patch_unchanged_and_downgrade_skip(self):
        for version in ("1.2.4", "1.2.3", "1.1.9", "0.9.0"):
            with self.subTest(version=version):
                self.assertFalse(should_release(source("1.2.3", 3), source(version, 4)))

    def test_release_requires_increased_code(self):
        for code in (2, 3):
            with self.subTest(code=code), self.assertRaises(ValueError):
                should_release(source("1.0.2", 3), source("1.1.0", code))

    def test_invalid_version_fails(self):
        with self.assertRaises(ValueError):
            read_version(source("1.1.0-beta", 4))


if __name__ == "__main__":
    unittest.main()

"""Safety checks for creating a disposable benchmark environment file."""

import os
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("new_bench_env.py")


def unused_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


class BenchEnvTest(unittest.TestCase):
    def test_creates_private_unique_values_and_refuses_overwrite(self):
        ports = set()
        while len(ports) < 4:
            ports.add(unused_port())
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / ".env.bench"
            cmd = ["python3", str(SCRIPT), "--project", "peergrab-bench-env-test",
                   "--output", str(output)]
            for option, port in zip(("--api-port", "--mysql-port", "--redis-port", "--mq-port"), ports):
                cmd.extend((option, str(port)))
            first = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual(0o600, os.stat(output).st_mode & 0o777)
            values = dict(line.split("=", 1) for line in output.read_text().splitlines()
                          if "=" in line and not line.startswith("#"))
            self.assertEqual("peergrab-bench-env-test", values["COMPOSE_PROJECT_NAME"])
            self.assertEqual("jwt", values["PEERGRAB_AUTH_MODE"])
            self.assertGreaterEqual(len(values["PEERGRAB_AUTH_JWT_SECRET"]), 32)
            passwords = [values[f"PEERGRAB_AUTH_DEMO_PASSWORD_{user}"]
                         for user in (1001, 2001, 2002, 9001)]
            self.assertEqual(4, len(set(passwords)))
            self.assertNotIn("replace-with", output.read_text())
            second = subprocess.run(cmd, capture_output=True, text=True)
            self.assertNotEqual(0, second.returncode)

    def test_rejects_production_project(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / ".env.bench"
            result = subprocess.run(["python3", str(SCRIPT), "--project", "peergrab-prod",
                                     "--output", str(output)], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()

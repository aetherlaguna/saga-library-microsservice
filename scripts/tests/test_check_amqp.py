"""Testes do diagnóstico com respostas simuladas, sem acessar Docker ou rede."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "check-amqp.sh"


@unittest.skipUnless(shutil.which("jq"), "jq é obrigatório para o diagnóstico")
class AmqpDiagnosticTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="library-amqp-test-")
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        (root / "docker").write_text('''#!/bin/sh
case "$*" in
  info) exit "${MOCK_DOCKER_EXIT:-0}" ;;
  'compose config --quiet') exit 0 ;;
  'compose ps --status running --services') printf 'loans\\nrabbitmq\\n' ;;
  'compose exec -T loans printenv SPRING_RABBITMQ_USERNAME') printf 'library\\n' ;;
  'compose exec -T rabbitmq rabbitmqctl -q list_connections --no-table-headers user vhost state')
    printf '%s\\t/\\trunning\\n' "${MOCK_USER:-library}" ;;
  *) exit 99 ;;
esac
''')
        (root / "curl").write_text('''#!/bin/sh
printf '{"status":"UP","components":{"db":{"status":"UP"},"rabbit":{"status":"%s"}}}\\n' "${MOCK_RABBIT:-UP}"
''')
        for tool in root.iterdir():
            tool.chmod(0o755)
        self.env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"])

    def check(self, expected, **changes):
        result = subprocess.run(
            ["bash", str(SCRIPT)], env=dict(self.env, **changes),
            capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)

    def test_healthy(self):
        self.check(0)

    def test_broker_down(self):
        self.check(1, MOCK_RABBIT="DOWN")

    def test_different_user(self):
        self.check(1, MOCK_USER="other")

    def test_docker_unavailable(self):
        self.check(2, MOCK_DOCKER_EXIT="1")


if __name__ == "__main__":
    unittest.main()

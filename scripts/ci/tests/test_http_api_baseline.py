"""Exercise HTTP baseline generation and read-only checks against temporary controller sources."""
from pathlib import Path
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class HttpApiBaselineTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.script = self.root / 'scripts/generate-http-api-baseline.py'
        self.script.parent.mkdir(parents=True)
        self.script.write_bytes((ROOT / 'scripts/generate-http-api-baseline.py').read_bytes())
        self.baseline = self.root / 'deploy/smoke-tests/src/test/resources/architecture/http-api-baseline.txt'
        self.baseline.parent.mkdir(parents=True)
        self.controller = self.root / ('tradepass-module-trade/tradepass-module-trade-server/src/main/java/'
                                       'com/tradepass/module/trade/controller/app/sample/SampleController.java')
        self.controller.parent.mkdir(parents=True)
        self.source = '''
            @RequestMapping("/api/sample")
            class SampleController {
                @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_PDF_VALUE)
            }
        '''
        self.controller.write_text(self.source, encoding='utf-8')
        probe = self.root / ('tradepass-framework/tradepass-spring-boot-starter-web/src/main/java/'
                             'com/tradepass/framework/web/core/controller/ProbeController.java')
        probe.parent.mkdir(parents=True)
        probe.write_text('@GetMapping("/tcb_probe")\n@GetMapping("/CqVT3HIuWt.txt")\n', encoding='utf-8')

    def run_script(self, *args):
        return subprocess.run([sys.executable, str(self.script), *args], cwd=self.root,
                              text=True, capture_output=True)

    def generate(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_check_accepts_matching_baseline_without_writing_it(self):
        self.generate()
        os.utime(self.baseline, ns=(1000000000, 1000000000))
        original = self.baseline.read_bytes()
        modified = self.baseline.stat().st_mtime_ns

        result = self.run_script('--check')

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('all 3 controller mappings', result.stdout)
        self.assertEqual(original, self.baseline.read_bytes())
        self.assertEqual(modified, self.baseline.stat().st_mtime_ns)

    def test_same_count_path_method_or_media_changes_fail_without_updating_baseline(self):
        self.generate()
        original = self.baseline.read_bytes()
        os.utime(self.baseline, ns=(1000000000, 1000000000))
        modified = self.baseline.stat().st_mtime_ns
        for before, after in (('/{id}', '/{id}/download'), ('@GetMapping', '@PostMapping'),
                              ('APPLICATION_PDF_VALUE', 'APPLICATION_OCTET_STREAM_VALUE')):
            with self.subTest(change=after):
                self.controller.write_text(self.source.replace(before, after), encoding='utf-8')

                result = self.run_script('--check')

                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn('Missing from baseline (new/changed mappings)', result.stderr)
                self.assertIn('No longer present in controllers (removed/changed mappings)', result.stderr)
                self.assertIn('python3 scripts/generate-http-api-baseline.py', result.stderr)
                self.assertEqual(original, self.baseline.read_bytes())
                self.assertEqual(modified, self.baseline.stat().st_mtime_ns)

    def test_default_generation_updates_changed_contract_and_then_check_passes(self):
        self.generate()
        self.controller.write_text(self.source.replace('@GetMapping', '@PostMapping'), encoding='utf-8')

        self.generate()

        content = self.baseline.read_text(encoding='utf-8')
        self.assertIn('{POST [/api/sample/{id}], produces [application/pdf]}', content)
        self.assertNotIn('{GET [/api/sample/{id}]', content)
        result = self.run_script('--check')
        self.assertEqual(0, result.returncode, result.stderr)

    def test_check_rejects_missing_baseline_without_creating_it(self):
        result = self.run_script('--check')

        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn('baseline is missing', result.stderr)
        self.assertIn('python3 scripts/generate-http-api-baseline.py', result.stderr)
        self.assertFalse(self.baseline.exists())


class PipelineApiBaselineTest(unittest.TestCase):
    def test_baseline_drift_aborts_before_tests_and_maven(self):
        pipeline = (ROOT / 'Jenkinsfile.core').read_text()
        stage = pipeline.split("stage('Test and package selected applications')", 1)[1]
        script = re.search(r"sh '''(.*?)'''", stage, re.S).group(1)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trace = root / 'commands.jsonl'
            runner = (f'#!{sys.executable}\n'
                      'import json, os, pathlib, sys\n'
                      'with pathlib.Path(os.environ["COMMAND_TRACE"]).open("a") as stream:\n'
                      '    stream.write(json.dumps([pathlib.Path(sys.argv[0]).name, *sys.argv[1:]]) + "\\n")\n'
                      'raise SystemExit(int(os.environ["BASELINE_STATUS"]) if "--check" in sys.argv else 0)\n')
            for name in ('python3', 'mvn'):
                executable = root / name
                executable.write_text(runner, encoding='utf-8')
                executable.chmod(0o755)
            env = {**os.environ, 'PATH': directory + os.pathsep + os.defpath,
                   'SERVICE': 'all', 'COMMAND_TRACE': str(trace)}
            for status in (0, 7):
                with self.subTest(baseline_status=status):
                    trace.unlink(missing_ok=True)
                    result = subprocess.run(['bash', '-c', script], cwd=root,
                                            env={**env, 'BASELINE_STATUS': str(status)},
                                            text=True, capture_output=True)
                    calls = [json.loads(line) for line in trace.read_text().splitlines()]
                    self.assertEqual(status, result.returncode, result.stderr)
                    self.assertEqual(['python3', 'scripts/generate-http-api-baseline.py', '--check'], calls[0])
                    if status:
                        self.assertEqual(1, len(calls), calls)
                    else:
                        self.assertEqual('mvn', calls[-1][0])


if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
"""Run the backend acceptance scenarios in an isolated Compose project.
Never uses the development .env, databases, ports or volumes.
Requires Docker, Python 3 and network access for image/dependency downloads.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent


def main():
    project = 'library-acceptance-' + uuid.uuid4().hex[:10]
    password = uuid.uuid4().hex
    environment = dict(os.environ, GRAFANA_PASSWORD=password)
    development_env = ROOT / '.env'
    original_hash = hashlib.sha256(development_env.read_bytes()).hexdigest() if development_env.exists() else None
    trace_id = uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix='library-acceptance-') as directory:
        folder = Path(directory)
        services = {}
        for name in ('loans', 'catalog'):
            services[name + '-db'] = {
                'image': 'postgres:17',
                'environment': {'POSTGRES_USER': name, 'POSTGRES_DB': name, 'POSTGRES_PASSWORD': password},
                'healthcheck': {'test': ['CMD-SHELL', f'pg_isready -U {name} -d {name}'],
                                'interval': '2s', 'timeout': '3s', 'retries': 40}}
            services[name] = {
                'build': {'context': str(ROOT / name)}, 'ports': ['127.0.0.1::8080'],
                'environment': {'SPRING_DATASOURCE_URL': f'jdbc:postgresql://{name}-db:5432/{name}',
                                'SPRING_DATASOURCE_USERNAME': name, 'SPRING_DATASOURCE_PASSWORD': password,
                                'SPRING_RABBITMQ_HOST': 'rabbitmq', 'SPRING_RABBITMQ_USERNAME': 'acceptance',
                                'SPRING_RABBITMQ_PASSWORD': password, 'JAVA_TOOL_OPTIONS': '-Xmx256m',
                                'LOANS_SAGA_TIMEOUT': '8s', 'LOANS_SAGA_RECOVERY_INTERVAL_MS': '500'},
                'depends_on': {name + '-db': {'condition': 'service_healthy'},
                               'rabbitmq': {'condition': 'service_healthy'}}}
        services['rabbitmq'] = {
            'image': 'rabbitmq:4.3.6-management',
            'environment': {'RABBITMQ_DEFAULT_USER': 'acceptance', 'RABBITMQ_DEFAULT_PASS': password},
            'healthcheck': {'test': ['CMD', 'rabbitmq-diagnostics', '-q', 'ping'],
                            'interval': '3s', 'timeout': '5s', 'retries': 40}}
        base = folder / 'base.json'
        base.write_text(json.dumps({'services': services}))
        prefix = ['docker', 'compose', '-p', project, '--env-file', os.devnull, '--project-directory', str(ROOT)]
        resolved = json.loads(subprocess.check_output(prefix + ['-f', str(base), '-f', str(ROOT / 'compose.observability.yaml'),
                                                                'config', '--format', 'json'], env=environment))
        for service in resolved['services'].values():
            for port in service.get('ports', []):
                port.pop('published', None)
                port['host_ip'] = '127.0.0.1'
        config = folder / 'compose.json'
        config.write_text(json.dumps(resolved))
        command = prefix + ['-f', str(config)]

        def compose(*args):
            return subprocess.check_output(command + list(args), env=environment, stderr=subprocess.STDOUT).decode().strip()

        def sql(service, query):
            return compose('exec', '-T', service + '-db', 'psql', '-U', service, '-d', service, '-tAc', query)

        def wait(predicate, description, seconds=120):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                try:
                    value = predicate()
                    if value:
                        return value
                except (OSError, ValueError, subprocess.CalledProcessError):
                    pass
                time.sleep(1)
            raise AssertionError('Timeout: ' + description)

        def get(service, path):
            with urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{ports[service]}{path}', headers={'Accept': 'application/json'}), timeout=5) as response:
                return json.load(response)

        def post(service, path, data):
            request = urllib.request.Request(f'http://127.0.0.1:{ports[service]}{path}', json.dumps(data).encode(),
                                            {'Content-Type': 'application/json',
                                             'traceparent': f'00-{trace_id}-0123456789abcdef-01'})
            with urllib.request.urlopen(request, timeout=10) as response:
                assert response.status == 201
                return json.load(response)

        def create_loan(book):
            return post('loans', '/loans', {'memberId': str(uuid.uuid4()), 'registeredBy': str(uuid.uuid4()),
                        'bookIds': [book], 'dueAt': (datetime.datetime.now(datetime.timezone.utc) +
                        datetime.timedelta(days=7)).isoformat()})['id']

        try:
            print('Building disposable project:', project, flush=True)
            subprocess.run(command + ['up', '--build', '-d'], env=environment, check=True)
            ports = {name: compose('port', name, str(port)).rsplit(':', 1)[1]
                     for name, port in [('loans', 8080), ('catalog', 8080), ('prometheus', 9090),
                                        ('tempo', 3200), ('grafana', 3000), ('alertmanager', 9093)]}
            for name in ('loans', 'catalog'):
                wait(lambda n=name: get(n, '/actuator/health')['status'] == 'UP', name + ' healthy')
            book = post('catalog', '/books', {'title': 'Acceptance', 'totalCopies': 1})['id']
            active = create_loan(book)
            wait(lambda: get('loans', '/loans/' + active)['status'] == 'ACTIVE', 'reservation success')
            rejected = create_loan(book)
            wait(lambda: get('loans', '/loans/' + rejected)['status'] == 'CANCELLED', 'reservation rejection')
            assert sql('catalog', f"select available_copies from books where id='{book}'") == '0'
            assert trace_id in sql('loans', f"select trace_headers::text from outbox_events where loan_id='{active}'")
            wait(lambda: trace_id in sql('catalog', f"select trace_headers::text from outbox_events where loan_id='{active}'"),
                 'trace context propagated through the two outboxes')
            print('PASS: ACTIVE/CANCELLED, stock and durable trace context.', flush=True)

            # Hold all result consumers, allowing catalog to reserve before loans times out.
            resolved['services']['loans']['environment']['LOANS_CONSUMER_ENABLED'] = 'false'
            config.write_text(json.dumps(resolved))
            compose('up', '-d', '--no-deps', 'loans')
            ports['loans'] = compose('port', 'loans', '8080').rsplit(':', 1)[1]
            wait(lambda: get('loans', '/actuator/health')['status'] == 'UP', 'loans restarted')
            recovery_book = post('catalog', '/books', {'title': 'Compensated', 'totalCopies': 1})['id']
            pending = create_loan(recovery_book)
            wait(lambda: sql('catalog', f"select available_copies from books where id='{recovery_book}'") == '0', 'stock reserved')
            wait(lambda: sql('catalog', f"select available_copies from books where id='{recovery_book}'") == '1', 'compensation restores stock')
            assert get('loans', '/loans/' + pending)['status'] == 'PENDING'
            assert sql('loans', f"select state from loan_sagas where loan_id='{pending}'") == 'WAITING_RELEASE'
            resolved['services']['loans']['environment']['LOANS_CONSUMER_ENABLED'] = 'true'
            config.write_text(json.dumps(resolved))
            compose('up', '-d', '--no-deps', 'loans')
            ports['loans'] = compose('port', 'loans', '8080').rsplit(':', 1)[1]
            wait(lambda: get('loans', '/loans/' + pending)['status'] == 'CANCELLED', 'late results cannot reactivate')
            compose('restart', 'loans', 'catalog')
            for name in ('loans', 'catalog'):
                ports[name] = compose('port', name, '8080').rsplit(':', 1)[1]
            wait(lambda: get('loans', '/loans/' + pending)['status'] == 'CANCELLED', 'restart preserves cancellation')
            print('PASS: timeout, compensation, late results, restart and stock restoration.', flush=True)

            wait(lambda: len(get('prometheus', '/api/v1/query?query=library_outbox_pending')['data']['result']) == 2,
                 'Prometheus collects both services')
            wait(lambda: get('grafana', '/api/health').get('database') == 'ok', 'Grafana healthy')
            wait(lambda: get('alertmanager', '/api/v2/status').get('versionInfo'), 'Alertmanager healthy')
            wait(lambda: bool(get('tempo', '/api/traces/' + trace_id)), 'trace available in Tempo')
            print('PASS: Prometheus, Grafana, Alertmanager and distributed trace in Tempo.', flush=True)
        except Exception:
            print(compose('logs', '--tail=15', 'loans', 'catalog', 'tempo'), flush=True)
            raise
        finally:
            subprocess.run(command + ['down', '--volumes'], env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if original_hash:
                assert hashlib.sha256(development_env.read_bytes()).hexdigest() == original_hash
            print('Disposable project removed; development .env preserved.', flush=True)


if __name__ == '__main__':
    main()

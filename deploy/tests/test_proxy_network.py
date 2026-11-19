"""Proxy topology, disposable address generation and bounded collision retries (#94)."""
import copy
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import unittest
from unittest.mock import Mock, patch

from proxy_network import (DEFAULT_SUBNET, disposable_proxy_environment, start_with_proxy_retry,
                           validate_proxy_topology)

ROOT = Path(__file__).resolve().parents[2]


def fixture():
    return {"networks": {"vod_edge": {}, "vod_internal": {"internal": True},
                         "vod_proxy": {"driver": "bridge", "enable_ipv6": False,
                                       "ipam": {"config": [{"subnet": "10.94.250.0/29"}]}}},
            "services": {
                "nginx": {"networks": {"vod_edge": {}, "vod_proxy": {"ipv4_address": "10.94.250.2"}},
                          "ports": [{"target": 80}]},
                "backend": {"networks": {"vod_internal": {}, "vod_proxy": {"ipv4_address": "10.94.250.3"}},
                            "environment": {"FORWARDED_TRUSTED_PROXIES": "10.94.250.2/32"}},
                "frontend": {"networks": {"vod_edge": {}}},
                "postgres": {"networks": {"vod_internal": {}}},
                "production-config-check": {"network_mode": "none"}}}


class ProxyNetworkTests(unittest.TestCase):
    def assert_triple(self, values):
        subnet = ipaddress.ip_network(values["VOD_PROXY_SUBNET"])
        self.assertEqual(subnet.version, 4)
        self.assertEqual(subnet.prefixlen, 29)
        self.assertNotEqual(subnet, DEFAULT_SUBNET)
        self.assertTrue(subnet.subnet_of(ipaddress.ip_network("10.94.0.0/16")))
        addresses = {ipaddress.ip_address(values[key]) for key in ("VOD_PROXY_NGINX_IP", "VOD_PROXY_BACKEND_IP")}
        self.assertEqual(len(addresses), 2)
        for address in addresses:
            self.assertIn(address, subnet)
            self.assertNotIn(address, {subnet.network_address, subnet[1], subnet.broadcast_address})

    def test_generated_triples_are_valid_and_never_the_deployment_default(self):
        for _ in range(200):
            self.assert_triple(disposable_proxy_environment())
        with patch("proxy_network.secrets.randbelow", side_effect=[8000, 1]):
            self.assert_triple(disposable_proxy_environment())  # 8000 selects the default /29; skip it.

    def test_pool_overlap_retries_after_cleanup_with_a_new_link(self):
        env = {"unrelated": "preserved", **disposable_proxy_environment()}
        start = Mock(side_effect=[subprocess.CompletedProcess([], 1, "", "Pool overlaps with other one"),
                                  subprocess.CompletedProcess([], 0, "", "")])
        cleanup = Mock()
        result = start_with_proxy_retry(start, cleanup, env)
        self.assertEqual(result.returncode, 0)
        cleanup.assert_called_once_with()
        self.assertEqual(start.call_count, 2)
        self.assertEqual(env["unrelated"], "preserved")
        self.assert_triple(env)

    def test_health_or_application_failure_is_not_retried(self):
        cleanup = Mock()
        failure = subprocess.CompletedProcess([], 1, "", "container unhealthy")
        start = Mock(return_value=failure)
        self.assertIs(start_with_proxy_retry(start, cleanup, {}), failure)
        start.assert_called_once_with()
        cleanup.assert_not_called()

    def test_overlap_retry_is_bounded_and_leaves_final_cleanup_to_the_owner(self):
        cleanup = Mock()
        start = Mock(return_value=subprocess.CompletedProcess([], 1, "", "Pool overlaps"))
        self.assertEqual(start_with_proxy_retry(start, cleanup, {}, attempts=3).returncode, 1)
        self.assertEqual(start.call_count, 3)
        self.assertEqual(cleanup.call_count, 2)

    def test_valid_topology_is_accepted_without_docker(self):
        validate_proxy_topology(fixture())

    def test_unsafe_memberships_addresses_ports_and_trust_are_rejected(self):
        changes = [
            lambda c: c["networks"]["vod_proxy"].update(internal=True),
            lambda c: c["networks"]["vod_proxy"].update(enable_ipv6=True),
            lambda c: c["services"]["frontend"]["networks"].update(vod_proxy={}),
            lambda c: c["services"]["backend"]["networks"].update(vod_edge={}),
            lambda c: c["services"]["backend"].update(ports=[{"target": 8080}]),
            lambda c: c["services"]["backend"]["environment"].update(FORWARDED_TRUSTED_PROXIES="10.0.0.0/8"),
        ]
        for address in ("10.94.250.0", "10.94.250.1", "10.94.250.7", "10.94.251.3", "10.94.250.2"):
            changes.append(lambda c, address=address: c["services"]["backend"]["networks"]["vod_proxy"].update(
                ipv4_address=address))
        for change in changes:
            config = copy.deepcopy(fixture())
            change(config)
            with self.subTest(config=config), self.assertRaises((ValueError, KeyError)):
                validate_proxy_topology(config)


@unittest.skipUnless(shutil.which("docker"), "docker compose is needed to render effective configs")
class ComposeProxyNetworkTests(unittest.TestCase):
    def render(self, production=False, overrides=None):
        files = ["docker-compose.yml"] + (["docker-compose.prod.yml"] if production else [])
        env = dict(os.environ)
        for file in files:
            text = (ROOT / "deploy" / file).read_text(encoding="utf-8")
            for name in re.findall(r"\$\{([A-Z0-9_]+)", text):
                env.pop(name, None)
            for name in re.findall(r"\$\{([A-Z0-9_]+):\?", text):
                env[name] = "0.0.0.0:18099" if name == "NGINX_HTTP_PORT" else "render-only-" + name.lower()
        env.update(overrides or {})
        command = ["docker", "compose", "--env-file", os.devnull]
        for file in files:
            command += ["-f", str(ROOT / "deploy" / file)]
        result = subprocess.run(command + ["config", "--format", "json"], env=env,
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, "Compose render failed (output withheld)")
        return json.loads(result.stdout)

    def test_base_and_production_defaults_have_exact_nginx_only_trust(self):
        for production in (False, True):
            config = self.render(production)
            validate_proxy_topology(config)
            self.assertEqual(config["networks"]["vod_proxy"]["ipam"]["config"][0]["subnet"], "10.94.250.0/29")
            self.assertEqual(config["services"]["backend"]["environment"]["FORWARDED_TRUSTED_PROXIES"],
                             "10.94.250.2/32")

    def test_both_overlays_derive_trust_from_the_overridden_nginx_address(self):
        values = disposable_proxy_environment()
        for production in (False, True):
            config = self.render(production, values)
            validate_proxy_topology(config)
            self.assertEqual(config["services"]["backend"]["environment"]["FORWARDED_TRUSTED_PROXIES"],
                             values["VOD_PROXY_NGINX_IP"] + "/32")


if __name__ == "__main__":
    unittest.main()

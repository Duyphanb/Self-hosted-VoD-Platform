"""Disposable IPv4 proxy links and the effective Compose topology contract (#94)."""
import ipaddress
import secrets

DEFAULT_SUBNET = ipaddress.ip_network("10.94.250.0/29")
POOL = ipaddress.ip_network("10.94.0.0/16")


def disposable_proxy_environment():
    """Choose a /29 outside the deployment default. Startup retries actual Docker overlaps."""
    while True:
        subnet = ipaddress.ip_network((int(POOL.network_address) + secrets.randbelow(8192) * 8, 29))
        if subnet != DEFAULT_SUBNET:
            return {"VOD_PROXY_SUBNET": str(subnet), "VOD_PROXY_NGINX_IP": str(subnet[2]),
                    "VOD_PROXY_BACKEND_IP": str(subnet[3])}


def start_with_proxy_retry(start, cleanup, env, attempts=5):
    """Callers own a fresh project. Remove only its partial resources before changing its link.

    Return the original command result for non-overlap failures so callers retain their own safe
    diagnostics. Never retry an application/health failure or delete another project's network.
    """
    for attempt in range(attempts):
        result = start()
        if result.returncode == 0 or "pool overlaps" not in (result.stdout + result.stderr).lower():
            return result
        if attempt + 1 == attempts:
            return result
        cleanup()
        env.update(disposable_proxy_environment())
    raise ValueError("attempts must be positive")


def validate_proxy_topology(config):
    """Validate rendered base or base+prod config; no Docker dependency or credential output."""
    services, networks = config["services"], config["networks"]
    proxy = networks["vod_proxy"]
    if (proxy.get("internal") or proxy.get("enable_ipv6") or proxy.get("driver") != "bridge"
            or not networks["vod_internal"].get("internal") or networks["vod_edge"].get("internal")):
        raise ValueError("network isolation flags")
    members = {name for name, service in services.items() if "vod_proxy" in (service.get("networks") or {})}
    if members != {"nginx", "backend"}:
        raise ValueError("proxy network members")
    for name, service in services.items():
        attached = set(service.get("networks") or {})
        expected = {"nginx": {"vod_edge", "vod_proxy"}, "backend": {"vod_proxy", "vod_internal"},
                    "frontend": {"vod_edge"}}.get(name, {"vod_internal"})
        if name == "production-config-check":
            expected = set()
            if service.get("network_mode") != "none":
                raise ValueError("preflight network")
        if attached != expected or (name != "nginx" and service.get("ports")):
            raise ValueError("service network membership or published port")
    ipam, = proxy["ipam"]["config"]
    subnet = ipaddress.ip_network(ipam["subnet"])
    if subnet.version != 4:
        raise ValueError("proxy link must be IPv4")
    gateway = ipaddress.ip_address(ipam.get("gateway", str(subnet[1])))
    addresses = [ipaddress.ip_address(services[name]["networks"]["vod_proxy"]["ipv4_address"])
                 for name in ("nginx", "backend")]
    if (len(set(addresses)) != 2 or any(address not in subnet or address in {
            subnet.network_address, gateway, subnet.broadcast_address} for address in addresses)):
        raise ValueError("proxy static addresses")
    if services["backend"]["environment"].get("FORWARDED_TRUSTED_PROXIES") != str(addresses[0]) + "/32":
        raise ValueError("backend must trust exactly Nginx")

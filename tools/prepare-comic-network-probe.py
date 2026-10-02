"""Prepare isolated local listeners using existing user-configured outbounds.

Private proxy configuration stays outside the repository and is deleted after the audit.
"""
import os
import pathlib
import yaml

root = pathlib.Path(os.environ["LOCALAPPDATA"]) / "CodexSourceAudit" / "comic-network-20261001"
source = pathlib.Path.home() / "AppData/Roaming/io.github.clash-verge-rev.clash-verge-rev/clash-verge.yaml"
configuration = yaml.safe_load(source.read_text(encoding="utf-8"))
regions = {"US": ["美国", "洛杉矶"], "EU": ["德国", "法国", "英国", "荷兰"], "SG": ["新加坡"],
           "HK": ["香港"], "TW": ["台湾"], "JP": ["日本"]}
selected = []
listeners = []
for index, (region, names) in enumerate(regions.items()):
    candidates = [p for p in configuration.get("proxies", []) if any(n in p.get("name", "") for n in names)]
    preferred_type = "vless" if region in {"HK", "JP"} else "hysteria2"
    candidate = next((p for p in candidates if p.get("type") == preferred_type), candidates[0] if candidates else None)
    if candidate is None:
        continue
    if candidate.get("dialer-proxy"):
        raise RuntimeError("Dependent outbound requires explicit resolution")
    selected.append(candidate)
    listeners.append({"name": region, "type": "mixed", "listen": "127.0.0.1", "port": 17901 + index, "proxy": candidate["name"]})
root.mkdir(parents=True, exist_ok=True)
output = {"mode": "rule", "allow-lan": False, "log-level": "silent", "ipv6": False,
          "listeners": listeners, "proxies": selected, "rules": ["MATCH,DIRECT"],
          "dns": {"enable": False}, "tun": {"enable": False}, "profile": {"store-selected": False}}
original_dns = configuration.get("dns", {})
output["dns"] = {"enable": False, "ipv6": False,
                 "nameserver": original_dns.get("nameserver", ["https://dns.alidns.com/dns-query"]),
                 "default-nameserver": original_dns.get("default-nameserver", ["223.5.5.5"])}
(root / "probe.yaml").write_text(yaml.safe_dump(output, allow_unicode=True), encoding="utf-8")
for listener in listeners:
    print(f"Prepared {listener['name']} test listener: {listener['port']}")

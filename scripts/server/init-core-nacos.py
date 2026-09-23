#!/usr/bin/env python3
"""Create missing core Data IDs after the administrator has set a private Nacos password."""
import json
import os
import urllib.error
import urllib.parse
import urllib.request


def main():
    address = os.environ.get("NACOS_SERVER_ADDR", "127.0.0.1:8848")
    if "/" in address or "," in address:
        raise ValueError("Use one Nacos host:port")
    base = "http://" + address + "/nacos/v1"
    password = os.environ["NACOS_PASSWORD"]
    if not password or password == "nacos":
        raise ValueError("Set a private Nacos administrator password first")
    login = urllib.parse.urlencode({"username": os.environ.get("NACOS_USERNAME", "nacos"), "password": password}).encode()
    with urllib.request.urlopen(base + "/auth/login", login, timeout=10) as response:
        token = json.load(response)["accessToken"]
    common = {"accessToken": token, "group": os.environ.get("NACOS_GROUP", "TRADEPASS_CORE"),
              "tenant": os.environ.get("NACOS_NAMESPACE", "")}
    for role in ("common", "gateway", "identity", "business"):
        query = {**common, "dataId": "tradepass-" + role + ".yaml"}
        try:
            with urllib.request.urlopen(base + "/cs/configs?" + urllib.parse.urlencode(query), timeout=10):
                print("Kept existing " + query["dataId"])
                continue
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise RuntimeError("Nacos config lookup failed: HTTP " + str(error.code)) from None
        content = "tradepass:\n  configuration-version: 1\n"
        body = urllib.parse.urlencode({**query, "type": "yaml", "content": content}).encode()
        with urllib.request.urlopen(base + "/cs/configs", body, timeout=10) as response:
            if response.read().strip() != b"true":
                raise RuntimeError("Nacos rejected " + query["dataId"])
        print("Created " + query["dataId"])


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        # HTTPError embeds its URL, which may contain an access token.
        raise SystemExit("Nacos request failed: HTTP " + str(error.code)) from None

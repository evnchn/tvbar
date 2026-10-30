"""Run: python3 server/test_tvbar_server.py (no network)."""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tvbar_server as s


def boom():
    raise OSError("feed down")


# A feed that dies keeps serving its last good arrivals, but /data.json must say how old they are,
# or the page shows "6 分鐘" for as long as the feed is down.
s.cache.clear()
s.cache["bus"] = (time.time() - 600, [{"route": "1A", "dest": "Town", "mins": [{"m": 6, "sched": False}]}])
s.bus = s.weather = boom
d = json.loads(s.data_json())
assert d["buses"][0]["mins"][0]["m"] == 6, d
assert 595 <= d["age"]["bus"] <= 605, d["age"]
assert d["age"]["weather"] is None, d["age"]  # never fetched
print("ok")

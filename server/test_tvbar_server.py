"""Run: python3 server/test_tvbar_server.py (no network)."""
import json
import os
import sys
import threading
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

# A feed that hangs (accepts, never answers) must not hold /data.json: the page gives up after 8 s, so one hung
# upstream would freeze every section, including arrivals from a feed that is fine.
gate = threading.Event()


def hung():
    gate.wait()
    return [{"route": "1A", "dest": "Town", "mins": [{"m": 3, "sched": False}]}]


s.cache.clear()
s.cache["bus"] = (time.time() - 600, [{"route": "1A", "dest": "Town", "mins": [{"m": 6, "sched": False}]}])
s.bus = hung
out = []
t = threading.Thread(target=lambda: out.append(json.loads(s.data_json())), daemon=True)
t.start()
t.join(5)
assert out, "/data.json waited on a hung feed; the TV page gives up after 8 s"
assert out[0]["buses"][0]["mins"][0]["m"] == 6 and out[0]["age"]["bus"] >= 595, out[0]  # last good value, still marked old
gate.set()
s.busy["bus"].join(1)
d = json.loads(s.data_json())
assert d["buses"][0]["mins"][0]["m"] == 3 and d["age"]["bus"] < 5, d  # the refresh landed once the feed answered

# The age must belong to the value served: if the bus refresh lands while a later section is still running,
# the response carries the old arrivals, so it must also carry their old age, not 0.
gate = threading.Event()
s.cache.clear()
s.cache["bus"] = (time.time() - 600, [{"route": "1A", "dest": "Town", "mins": [{"m": 6, "sched": False}]}])
s.busy.clear()


def late_nodes():
    gate.set()
    s.busy["bus"].join(1)
    return []


s.nodes = late_nodes
d = json.loads(s.data_json())
assert d["buses"][0]["mins"][0]["m"] == 6 and d["age"]["bus"] >= 595, d
print("ok")

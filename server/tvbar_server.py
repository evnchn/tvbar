"""TV bar data server: serves the bar pages, /data.json (weather, optional arrivals and status) and /epg.json.

Stdlib only. Run: python3 tvbar_server.py [port]
Optional environment:
  TVBAR_BIND    address to listen on (default 0.0.0.0; the TV must reach it)
  TVBAR_BUS_URL a text feed of lines like "1A→Town  6·(14)·(22)m" (parentheses = scheduled, not live); unset = no arrivals
  TVBAR_NODES   JSON list of [label, host, port] to probe over TCP for the status section; unset = none
"""
import json
import os
import socket
import sys
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

HERE = os.path.dirname(os.path.abspath(__file__))
HKO = "https://data.weather.gov.hk/weatherAPI/opendata/weather.php?lang=tc&dataType="
BUS = os.environ.get("TVBAR_BUS_URL")
EPG_URL = "https://epg.pw/xmltv/epg_HK.xml"
HKT = timezone(timedelta(hours=8))
# HOY publishes its own guide (what hoy.tv/program_guide loads via api2.hoy.tv/api/v3/a/channel): one XML per channel and day,
# local HK times, ~3 weeks per file.
HOY_URL = "https://epg-file.hoy.tv/hoy/OTT{num}{ymd}.xml"
# TV channel number -> (name as the TV shows it, epg.pw channel id or None)
CHANNELS = [(81, "翡翠台", "368366"), (82, "TVB Plus", "368361"), (83, "無綫新聞台", "368359"), (84, "明珠台", "368369"),
            (85, "鳳凰衛視香港台", None), (31, "港台電視 31", "368550"), (32, "港台電視 32", "368551"),
            (34, "CGTN 紀錄頻道", "368553"), (76, "HOY 76", "hoy"), (77, "HOY 77", "hoy"), (78, "HOY 78", "hoy"),
            (96, "ViuTVsix", "565551"), (99, "ViuTV", "565569")]
epg = {"channels": [], "t": 0}
NODES = [tuple(n) for n in json.loads(os.environ.get("TVBAR_NODES", "[]"))]
# HKO weather icon codes -> short Chinese label
ICONS = {50: "晴", 51: "間中有陽光", 52: "短暫陽光", 53: "間有驟雨", 54: "短暫陽光有驟雨", 60: "多雲", 61: "密雲",
         62: "微雨", 63: "雨", 64: "大雨", 65: "雷暴", 70: "天色良好", 71: "天色良好", 72: "天色良好", 73: "天色良好",
         74: "天色良好", 75: "天色良好", 76: "大致多雲", 77: "天色大致良好", 80: "大風", 81: "乾燥", 82: "潮濕",
         83: "霧", 84: "薄霧", 85: "煙霞", 90: "炎熱", 91: "溫暖", 92: "清涼", 93: "寒冷"}
TTL = {"weather": 300, "bus": 20, "nodes": 60}
cache = {}


def get(url, timeout=8):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "tvbar/1"}), timeout=timeout) as r:
        return r.read().decode("utf-8")


def weather():
    now = json.loads(get(HKO + "rhrread"))
    forecast = json.loads(get(HKO + "fnd"))["weatherForecast"]
    fnd = forecast[0]
    warn = json.loads(get(HKO + "warnsum"))
    days = [{"week": d["week"].replace("星期", ""), "hi": d["forecastMaxtemp"]["value"], "lo": d["forecastMintemp"]["value"],
             "text": ICONS.get(d.get("ForecastIcon"), "")} for d in forecast]
    temp = next((t["value"] for t in now["temperature"]["data"] if t["place"] == "香港天文台"), None)
    icon = (now.get("icon") or [None])[0]
    return {"temp": temp, "text": ICONS.get(icon, ""), "hi": fnd["forecastMaxtemp"]["value"],
            "lo": fnd["forecastMintemp"]["value"], "rain": "降雨機會" + fnd.get("PSR", ""),
            "warnings": [w["name"] for w in warn.values()], "days": days}


def bus():
    return parse_bus(get(BUS)) if BUS else []


def parse_bus(text):
    # Lines look like "1A→Town  6·(14)·(22)m"; parentheses mark scheduled (not live) times.
    out = []
    for line in text.splitlines():
        if "→" not in line:
            continue
        label, _, val = line.partition(" ")
        route, _, dest = label.partition("→")
        mins = []
        for p in val.strip().rstrip("m").split("·"):
            p = p.strip()
            if p.strip("()").lstrip("-").isdigit():
                mins.append({"m": int(p.strip("()")), "sched": p.startswith("(")})
        out.append({"route": route, "dest": dest, "mins": mins})
    return out


def xmltv_time(s):
    # epg.pw labels Hong Kong local time as "+0000" (its 7:30 pm news reads 19:30 +0000), so treat it as UTC+8.
    return int(datetime.strptime(s[:14], "%Y%m%d%H%M%S").replace(tzinfo=HKT).timestamp())


def refresh_epg():
    # epg.pw is ~19 MB; keep only our channels, from 1 h ago to 24 h ahead.
    ids = {cid: [] for _, _, cid in CHANNELS if cid and cid != "hoy"}
    now = time.time()
    with urllib.request.urlopen(urllib.request.Request(EPG_URL, headers={"User-Agent": "tvbar/1"}), timeout=60) as r:
        for _, el in ET.iterparse(r):
            if el.tag == "programme":
                cid = el.get("channel")
                if cid in ids:
                    start, stop = xmltv_time(el.get("start")), xmltv_time(el.get("stop"))
                    if stop > now - 3600 and start < now + 86400:
                        ids[cid].append({"start": start, "stop": stop, "title": el.findtext("title") or "",
                                         "desc": (el.findtext("desc") or "")[:300]})
                el.clear()
    def progs(n, cid):
        if cid == "hoy":
            try:
                return hoy_progs(n, now)
            except Exception as e:
                print("hoy", n, "failed:", e, flush=True)
                return []
        return sorted(ids.get(cid, []), key=lambda p: p["start"]) if cid else []
    epg["channels"] = [{"num": n, "name": name, "progs": progs(n, cid)} for n, name, cid in CHANNELS]
    epg["t"] = int(now)


def hoy_progs(num, now):
    url = HOY_URL.format(num=num, ymd=datetime.fromtimestamp(now, HKT).strftime("%Y%m%d"))
    root = ET.fromstring(get(url, timeout=60))
    out = []
    for it in root.iter("EpgItem"):
        start = int(datetime.strptime(it.findtext("EpgStartDateTime"), "%Y-%m-%d %H:%M:%S").replace(tzinfo=HKT).timestamp())
        stop = int(datetime.strptime(it.findtext("EpgEndDateTime"), "%Y-%m-%d %H:%M:%S").replace(tzinfo=HKT).timestamp())
        if stop > now - 3600 and start < now + 86400:
            title = (it.findtext("ProgramInfo/ProgramTitle") or "").strip() or (it.findtext("EpisodeInfo/EpisodeShortDescription") or "").strip()
            out.append({"start": start, "stop": stop, "title": title, "desc": (it.findtext("EpisodeInfo/EpisodeLongDescription") or "")[:300]})
    return sorted(out, key=lambda p: p["start"])


def epg_loop():
    while True:
        try:
            refresh_epg()
        except Exception as e:
            print("epg refresh failed:", e, flush=True)
        time.sleep(3 * 3600)


def probe(node):
    name, host, port = node
    try:
        socket.create_connection((host, port), 2).close()
        return {"name": name, "ok": True}
    except OSError:
        return {"name": name, "ok": False}


def nodes():
    if not NODES:
        return []
    with ThreadPoolExecutor(len(NODES)) as ex:
        return list(ex.map(probe, NODES))


def section(key, fn):
    hit = cache.get(key)
    if hit and time.time() - hit[0] < TTL[key]:
        return hit[1]
    try:
        val = fn()
    except Exception as e:  # keep serving the last good value; "age" in /data.json says how old it is
        return hit[1] if hit else {"error": str(e)[:120]}
    cache[key] = (time.time(), val)
    return val


def data_json():
    d = {"weather": section("weather", weather), "buses": section("bus", bus), "nodes": section("nodes", nodes)}
    now = time.time()
    d["age"] = {k: int(now - cache[k][0]) if k in cache else None for k in TTL}  # seconds since the last good fetch
    d["t"] = int(now)
    return json.dumps(d, ensure_ascii=False)


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/data.json"):
            body = data_json().encode()
            ctype = "application/json; charset=utf-8"
        elif self.path.startswith("/epg.json"):
            body = json.dumps(epg, ensure_ascii=False).encode()
            ctype = "application/json; charset=utf-8"
        elif self.path in ("/", "/index.html", "/home.html"):
            body = open(os.path.join(HERE, "home.html" if self.path == "/home.html" else "index.html"), "rb").read()
            ctype = "text/html; charset=utf-8"
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        assert parse_bus("1A→Town  6·(14)·(22)m\n2B→Pier   —\n1A→Park   ✗\n") == [
            {"route": "1A", "dest": "Town", "mins": [{"m": 6, "sched": False}, {"m": 14, "sched": True}, {"m": 22, "sched": True}]},
            {"route": "2B", "dest": "Pier", "mins": []}, {"route": "1A", "dest": "Park", "mins": []}]
        assert xmltv_time("20261020193000 +0000") == 1792495800  # 19:30 HKT
        print(json.dumps({"weather": weather(), "buses": bus(), "nodes": nodes()}, ensure_ascii=False, indent=1))
        sys.exit(0)
    threading.Thread(target=epg_loop, daemon=True).start()
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8093
    ThreadingHTTPServer((os.environ.get("TVBAR_BIND", "0.0.0.0"), port), H).serve_forever()

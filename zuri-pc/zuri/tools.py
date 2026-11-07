"""Ferramentas da Zuri (PC): configurações, clima, notícias, busca no YouTube, lista e lembretes."""
import html
import json
import os
import re
import threading
import time
import urllib.parse
from datetime import datetime, timedelta
from email.utils import parsedate_to_datetime

import requests

APP_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "Zuri")
os.makedirs(APP_DIR, exist_ok=True)
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"


# ============================================================================ configurações

class Settings:
    """Guardadas em %APPDATA%\\Zuri\\settings.json."""
    DEFAULTS = {"ai_key": "", "ai_model": "", "city": "", "morning_music": "", "start_with_windows": True,
                "voice": "pt-BR-FranciscaNeural"}

    def __init__(self):
        self.path = os.path.join(APP_DIR, "settings.json")
        self.data = dict(self.DEFAULTS)
        try:
            with open(self.path, encoding="utf-8") as f:
                self.data.update(json.load(f))
        except Exception:
            pass

    def get(self, key):
        return self.data.get(key, self.DEFAULTS.get(key))

    def set(self, key, value):
        self.data[key] = value
        with open(self.path, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=2)


# ============================================================================ clima (Open-Meteo, grátis)

WMO = {0: "céu limpo", 1: "predominantemente limpo", 2: "parcialmente nublado", 3: "nublado", 45: "neblina",
       48: "neblina", 51: "garoa", 53: "garoa", 55: "garoa", 61: "chuva fraca", 63: "chuva moderada",
       65: "chuva forte", 80: "pancadas de chuva fracas", 81: "pancadas de chuva", 82: "pancadas de chuva fortes",
       95: "tempestade", 96: "tempestade com granizo", 99: "tempestade com granizo"}


def _sky(code):
    return WMO.get(int(code or 0), "tempo variável")


def _geocode(city):
    r = requests.get("https://geocoding-api.open-meteo.com/v1/search",
                     params={"name": city, "count": 1, "language": "pt", "format": "json"}, timeout=10).json()
    res = (r.get("results") or [None])[0]
    if not res:
        return None
    name = ", ".join(x for x in (res.get("name"), res.get("admin1")) if x)
    return name, res["latitude"], res["longitude"]


def _forecast(lat, lon, days):
    return requests.get("https://api.open-meteo.com/v1/forecast", params={
        "latitude": lat, "longitude": lon, "timezone": "auto", "forecast_days": days,
        "current": "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m",
        "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"}, timeout=10).json()


def weather(city, days=3):
    try:
        g = _geocode(city)
        if not g:
            return f'Não encontrei a cidade "{city}".'
        name, lat, lon = g
        j = _forecast(lat, lon, max(1, min(7, int(days or 3))))
        c, d = j["current"], j["daily"]
        out = [f"Cidade: {name}. Agora: {round(c['temperature_2m'])}°C (sensação {round(c['apparent_temperature'])}°C), "
               f"{_sky(c['weather_code'])}, umidade {c['relative_humidity_2m']}%."]
        labels = ["Hoje", "Amanhã", "Depois de amanhã"]
        for i, day in enumerate(d["time"]):
            label = labels[i] if i < 3 else day
            out.append(f"{label}: {_sky(d['weather_code'][i])}, mínima {round(d['temperature_2m_min'][i])}°C, "
                       f"máxima {round(d['temperature_2m_max'][i])}°C, chance de chuva {d['precipitation_probability_max'][i]}%.")
        return " ".join(out)
    except Exception:
        return "Não consegui consultar a previsão agora."


def weather_now_short(city):
    """Para a tela: '32° · garoa'."""
    try:
        name, lat, lon = _geocode(city)
        c = _forecast(lat, lon, 1)["current"]
        return f"{round(c['temperature_2m'])}° · {_sky(c['weather_code'])}"
    except Exception:
        return None


def weather_morning(city):
    try:
        name, lat, lon = _geocode(city)
        j = _forecast(lat, lon, 1)
        c, d = j["current"], j["daily"]
        return (f"Agora faz {round(c['temperature_2m'])} graus. Hoje: {_sky(d['weather_code'][0])}, mínima de "
                f"{round(d['temperature_2m_min'][0])} e máxima de {round(d['temperature_2m_max'][0])}, com "
                f"{d['precipitation_probability_max'][0]} por cento de chance de chuva.")
    except Exception:
        return None


# ============================================================================ notícias (Google Notícias RSS)

def news(topic=None, max_items=8):
    if topic:
        url = "https://news.google.com/rss/search?q=" + urllib.parse.quote(f"{topic} when:3d") + \
              "&hl=pt-BR&gl=BR&ceid=BR:pt-419"
    else:
        url = "https://news.google.com/rss?hl=pt-BR&gl=BR&ceid=BR:pt-419"
    try:
        xml = requests.get(url, headers={"User-Agent": UA}, timeout=10).text
    except Exception:
        return "Não consegui buscar as notícias agora."
    items = []
    for body in re.findall(r"<item>(.*?)</item>", xml, flags=re.S)[:max_items]:
        t = re.search(r"<title>(.*?)</title>", body, flags=re.S)
        d = re.search(r"<pubDate>(.*?)</pubDate>", body)
        title = html.unescape((t.group(1) if t else "").replace("<![CDATA[", "").replace("]]>", "")).strip()
        when = ""
        if d:
            try:
                when = parsedate_to_datetime(d.group(1)).astimezone().strftime("%d/%m %H:%M")
            except Exception:
                pass
        items.append(f"- {title}" + (f" ({when})" if when else ""))
    if not items:
        return "Nenhuma notícia encontrada sobre isso."
    return (f'Manchetes sobre "{topic}":\n' if topic else "Principais manchetes:\n") + "\n".join(items)


# ============================================================================ YouTube (busca)

RENDERER = re.compile(r'"videoRenderer":\{"videoId":"([\w-]{11})"')
TITLE = re.compile(r'"title":\{"runs":\[\{"text":"([^"]{1,120})"')


def youtube_search(term, max_items=10):
    """Primeiros vídeos da busca (sem anúncios): lista de (id, título)."""
    try:
        page = requests.get("https://www.youtube.com/results",
                            params={"search_query": term, "hl": "pt-BR", "gl": "BR"},
                            headers={"User-Agent": UA, "Accept-Language": "pt-BR,pt;q=0.9",
                                     "Cookie": "CONSENT=YES+1; SOCS=CAI"}, timeout=12).text
    except Exception:
        return []
    found = {}
    for m in RENDERER.finditer(page):
        vid = m.group(1)
        if vid in found:
            continue
        t = TITLE.search(page, m.end())
        found[vid] = (t.group(1).replace("\\u0026", "&").replace('\\"', '"') if t else None)
        if len(found) >= max_items:
            break
    return list(found.items())


# ============================================================================ lista de compras e lembretes

class Organizer:
    """Lista de compras e lembretes em %APPDATA%\\Zuri\\organizer.json. Os lembretes tocam pela Zuri."""

    def __init__(self, on_due):
        self.path = os.path.join(APP_DIR, "organizer.json")
        self.lock = threading.Lock()
        self.on_due = on_due
        try:
            with open(self.path, encoding="utf-8") as f:
                self.data = json.load(f)
        except Exception:
            self.data = {"shopping": [], "reminders": [], "next_id": 1}
        threading.Thread(target=self._loop, daemon=True).start()

    def _save(self):
        with open(self.path, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=2)

    # ---- lista
    def add_items(self, items):
        with self.lock:
            cur = self.data["shopping"]
            added = [i.strip() for i in items if i.strip() and i.strip().lower() not in (c.lower() for c in cur)]
            cur += added
            self._save()
        return (f"Adicionado: {', '.join(added)}. " if added else "Esses itens já estavam na lista. ") + \
            f"A lista tem {len(cur)} itens."

    def remove_items(self, items):
        with self.lock:
            cur = self.data["shopping"]
            gone = [c for c in cur if any(i.strip().lower() in c.lower() for i in items if i.strip())]
            self.data["shopping"] = [c for c in cur if c not in gone]
            self._save()
        return f"Removido: {', '.join(gone)}." if gone else "Nenhum desses itens estava na lista."

    def clear_list(self):
        with self.lock:
            self.data["shopping"] = []
            self._save()
        return "Lista de compras apagada."

    def describe_list(self):
        cur = self.data["shopping"]
        return "A lista de compras está vazia." if not cur else f"Lista de compras ({len(cur)}): {', '.join(cur)}."

    # ---- lembretes
    @staticmethod
    def when_text(ts):
        d = datetime.fromtimestamp(ts)
        diff = (d.date() - datetime.now().date()).days
        h = d.strftime("%H:%M")
        return f"hoje às {h}" if diff == 0 else f"amanhã às {h}" if diff == 1 else f"em {d.strftime('%d/%m')} às {h}"

    def add_reminder(self, text, when=None, in_minutes=None):
        if in_minutes:
            ts = time.time() + int(in_minutes) * 60
        else:
            try:
                ts = datetime.strptime((when or "")[:16], "%Y-%m-%dT%H:%M").timestamp()
            except Exception:
                return "Horário não entendido. Pergunte ao usuário o horário."
        if ts <= time.time():
            return "Esse horário já passou. Peça outro horário."
        with self.lock:
            rid = self.data["next_id"]
            self.data["next_id"] += 1
            self.data["reminders"].append({"id": rid, "text": text.strip(), "at": ts})
            self._save()
        return f'Lembrete criado: "{text.strip()}" {self.when_text(ts)}.'

    def cancel_reminder(self, match):
        with self.lock:
            gone = [r for r in self.data["reminders"] if match.lower() in r["text"].lower()]
            self.data["reminders"] = [r for r in self.data["reminders"] if r not in gone]
            self._save()
        return f"Cancelado: {', '.join(r['text'] for r in gone)}." if gone else "Não achei esse lembrete."

    def describe_reminders(self):
        rs = sorted((r for r in self.data["reminders"] if r["at"] > time.time()), key=lambda r: r["at"])
        return "Não há lembretes marcados." if not rs else \
            "Lembretes: " + "; ".join(f"{r['text']} {self.when_text(r['at'])}" for r in rs) + "."

    def today(self):
        end = datetime.now().replace(hour=23, minute=59).timestamp()
        return sorted((r for r in self.data["reminders"] if time.time() < r["at"] <= end), key=lambda r: r["at"])

    def _loop(self):
        while True:
            time.sleep(15)
            due = []
            with self.lock:
                now = time.time()
                due = [r for r in self.data["reminders"] if r["at"] <= now]
                if due:
                    self.data["reminders"] = [r for r in self.data["reminders"] if r not in due]
                    self._save()
            for r in due:
                try:
                    self.on_due(r["text"])
                except Exception:
                    pass

    # para a IA
    def tomorrow_iso_example(self):
        return (datetime.now() + timedelta(days=1)).strftime("%Y-%m-%dT08:00")

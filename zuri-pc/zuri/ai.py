"""
Cérebro de IA da Zuri (PC) — o mesmo do app Android.

Usa IA aberta e grátis (Groq, com gpt-oss) ou OpenRouter / Gemini, conforme a chave colada.
A IA conversa e, quando o pedido é uma ação, devolve a ação (ferramenta) que a Zuri executa.
"""
import json
import re
import time
from datetime import datetime

import requests

from . import tools
from .parser import Cmd

DEFAULT_MODEL = "openai/gpt-oss-120b"
WEEKDAYS = ["segunda-feira", "terça-feira", "quarta-feira", "quinta-feira", "sexta-feira", "sábado", "domingo"]
MONTHS = ["janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro",
          "novembro", "dezembro"]


def provider_for(key: str):
    if key.startswith("sk-or-"):
        return "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter/free"
    if key.startswith("AIza"):
        return "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.5-flash"
    return "Groq", "https://api.groq.com/openai/v1/chat/completions", DEFAULT_MODEL


def _tool(name, desc, props=None, required=None):
    return {"type": "function", "function": {"name": name, "description": desc, "parameters": {
        "type": "object", "properties": props or {}, "required": required or []}}}


def _p(t, d):
    return {"type": t, "description": d}


TOOLS = [
    _tool("play_music", "Tocar música, artista, álbum, playlist ou estilo.",
          {"query": _p("string", "O que buscar"), "artist": _p("string", "Artista, se for por artista")}, ["query"]),
    _tool("pause_music", "Pausar a música."), _tool("resume_music", "Continuar a música."),
    _tool("stop_music", "Parar a música de vez."), _tool("next_track", "Próxima música."),
    _tool("previous_track", "Música anterior."),
    _tool("set_volume", "Volume de 1 (mais baixo) a 10 (máximo).", {"level": _p("integer", "1 a 10")}, ["level"]),
    _tool("volume_up", "Aumentar um pouco o volume."), _tool("volume_down", "Diminuir um pouco o volume."),
    _tool("get_weather", "Clima e previsão do tempo de uma cidade.",
          {"city": _p("string", "Cidade; vazio = cidade da casa"), "days": _p("integer", "1 a 7")}),
    _tool("get_news", "Manchetes recentes do Brasil ou de um assunto/time. Use para notícias e resultados de jogos.",
          {"topic": _p("string", "Assunto; vazio = principais do dia")}),
    _tool("add_reminder", "Criar lembrete (também serve como timer).",
          {"text": _p("string", "O que lembrar"), "when": _p("string", "AAAA-MM-DDTHH:MM, hora local"),
           "in_minutes": _p("integer", "Ou: daqui a quantos minutos")}, ["text"]),
    _tool("list_reminders", "Ler os lembretes."),
    _tool("cancel_reminder", "Cancelar lembretes que contenham um texto.", {"text": _p("string", "Parte do texto")}, ["text"]),
    _tool("shopping_list_add", "Adicionar itens à lista de compras.",
          {"items": {"type": "array", "items": {"type": "string"}}}, ["items"]),
    _tool("shopping_list_remove", "Tirar itens da lista de compras.",
          {"items": {"type": "array", "items": {"type": "string"}}}, ["items"]),
    _tool("shopping_list_read", "Ler a lista de compras."),
    _tool("shopping_list_clear", "Apagar a lista de compras."),
]
INFO_TOOLS = {"get_weather", "get_news", "add_reminder", "list_reminders", "cancel_reminder",
              "shopping_list_add", "shopping_list_remove", "shopping_list_read", "shopping_list_clear"}


class Brain:
    def __init__(self, settings: tools.Settings, organizer: tools.Organizer):
        self.settings = settings
        self.organizer = organizer
        self.history = []
        self.last_use = 0

    @property
    def configured(self):
        return bool(self.settings.get("ai_key").strip())

    def ask(self, user_text: str, situation: str):
        """Devolve (texto, Cmd|None). Lança exceção com mensagem amigável se der erro."""
        if time.time() - self.last_use > 300:
            self.history = []
        self.last_use = time.time()
        self.history.append({"role": "user", "content": user_text})
        self.history = self.history[-12:]
        convo = [{"role": "system", "content": self._system(situation)}] + self.history
        try:
            text, cmd = self._loop(convo)
        except Exception:
            self.history.pop()
            raise
        self.history.append({"role": "assistant", "content": text or f"[ação: {cmd.kind if cmd else ''}]"})
        return text, cmd

    def _loop(self, convo):
        for _ in range(3):
            msg = self._request(convo)
            calls = msg.get("tool_calls") or []
            info = [c for c in calls if c["function"]["name"] in INFO_TOOLS]
            if not info:
                return self._to_answer(msg)
            convo.append({"role": "assistant", "content": msg.get("content") or "", "tool_calls": calls})
            for c in info:
                args = json.loads(c["function"].get("arguments") or "{}")
                convo.append({"role": "tool", "tool_call_id": c.get("id", ""),
                              "content": self._run(c["function"]["name"], args)})
        return "Desculpe, me enrolei para responder isso.", None

    def _request(self, convo):
        key = self.settings.get("ai_key").strip()
        name, url, model = provider_for(key)
        model = self.settings.get("ai_model") or model
        body = {"model": model, "messages": convo, "tools": TOOLS, "tool_choice": "auto",
                "temperature": 0.6, "max_completion_tokens": 400}
        if model.startswith("openai/gpt-oss"):
            body["reasoning_effort"] = "low"
        headers = {"Authorization": f"Bearer {key}", "Content-Type": "application/json", "X-Title": "Zuri"}
        r = requests.post(url, json=body, headers=headers, timeout=25)
        if r.status_code == 401:
            raise RuntimeError(f"chave inválida ({name})")
        if r.status_code == 429:
            raise RuntimeError(f"limite grátis do {name} atingido, tente daqui a pouco")
        if r.status_code >= 300:
            try:
                detail = r.json().get("error", {}).get("message", "")
            except Exception:
                detail = ""
            if r.status_code == 403 and "network" in detail.lower():
                raise RuntimeError(f"o {name} bloqueou esta rede (VPN?). Tente outra IA")
            raise RuntimeError(f"erro {r.status_code} do {name}: {detail[:120]}")
        return r.json()["choices"][0]["message"]

    def _run(self, name, a):
        o = self.organizer
        if name == "get_weather":
            city = (a.get("city") or "").strip() or self.settings.get("city")
            if not city:
                return "Cidade desconhecida. Pergunte ao usuário qual é a cidade dele."
            if not self.settings.get("city"):
                self.settings.set("city", city)
            return tools.weather(city, a.get("days") or 3)
        if name == "get_news":
            return tools.news((a.get("topic") or "").strip() or None)
        if name == "add_reminder":
            return o.add_reminder(a.get("text") or "lembrete", a.get("when"), a.get("in_minutes"))
        if name == "list_reminders":
            return o.describe_reminders()
        if name == "cancel_reminder":
            return o.cancel_reminder(a.get("text") or "")
        items = a.get("items") or []
        if isinstance(items, str):
            items = [i.strip() for i in items.split(",")]
        if name == "shopping_list_add":
            return o.add_items(items)
        if name == "shopping_list_remove":
            return o.remove_items(items)
        if name == "shopping_list_read":
            return o.describe_list()
        if name == "shopping_list_clear":
            return o.clear_list()
        return "Ferramenta desconhecida."

    @staticmethod
    def _to_answer(msg):
        text = msg.get("content") or ""
        text = re.sub(r"[*_#`>]", "", text)
        text = re.sub(r"\s+", " ", text).strip() or None
        cmd = None
        calls = msg.get("tool_calls") or []
        if calls:
            f = calls[0]["function"]
            a = json.loads(f.get("arguments") or "{}")
            n = f["name"]
            if n == "play_music":
                q = a.get("query") or a.get("artist") or ""
                cmd = Cmd("play", query=q, artist=a.get("artist") or None) if q else None
            elif n == "set_volume":
                cmd = Cmd("volume_set", percent=max(0, min(10, int(a.get("level", 5)))) * 10)
            else:
                cmd = {"pause_music": Cmd("pause"), "resume_music": Cmd("resume"), "stop_music": Cmd("stop"),
                       "next_track": Cmd("next"), "previous_track": Cmd("previous"),
                       "volume_up": Cmd("volume_up"), "volume_down": Cmd("volume_down")}.get(n)
        return text, cmd

    def _system(self, situation):
        now = datetime.now()
        when = f"{WEEKDAYS[now.weekday()]}, {now.day} de {MONTHS[now.month - 1]} de {now.year}, {now:%H:%M}"
        city = self.settings.get("city") or "não informada"
        return f"""Você é a Zuri, assistente de voz que fica no computador da casa de uma família no Brasil.
Fale sempre em português do Brasil, de forma simpática, natural e BREVE: no máximo 2 frases curtas,
porque sua resposta será falada em voz alta. Nunca use markdown, listas, asteriscos, emojis ou links.
Quando o pedido for uma ação (tocar música, pausar, volume, lembrete), use a ferramenta certa.
Se a pessoa disser só um nome de artista ou música, toque. Para estilos ("algo animado"), escolha um bom termo de busca.
Volume vai de 1 (mais baixo) a 10 (máximo). Timer = lembrete com in_minutes.
Para clima use get_weather; para notícias e resultados de jogos use get_news e resuma em até 2 frases o que as
manchetes dizem, sem inventar. Para lembretes e lista de compras use as ferramentas e confirme em uma frase.
Agora: {when}.
Cidade da casa: {city}.
Situação: {situation}"""

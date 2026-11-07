"""
Zuri para Windows — assistente de voz com IA.

Fluxo: diga "Zuri" → ela responde "Oi" → faça o pedido → ela repete e executa.
Comandos simples também funcionam de uma vez: "Zuri, volume três", "Zuri, pausa".
"""
import json
import os
import random
import threading
import time
import traceback
from datetime import datetime

import webview

from . import ai, system, tools
from .parser import Cmd, find_wake, parse
from .voice import Ears, Mouth, base_dir

APP_TITLE = "Zuri"


class Engine:
    def __init__(self):
        self.settings = tools.Settings()
        self.organizer = tools.Organizer(on_due=lambda text: self.announce(f"Lembrete: {text}"))
        self.brain = ai.Brain(self.settings, self.organizer)
        self.window = None
        self.ears = Ears(self.on_wake_text, on_level=lambda lv: self.js(f"ui.level({lv:.2f})"))
        self.mouth = Mouth(self.settings.get("voice"))
        self.busy = threading.Lock()
        self.wake_timer = None
        self.player = {"active": False, "playing": False, "title": None}

    # ================================================================ ponte com a tela
    def js(self, code: str):
        try:
            if self.window:
                self.window.evaluate_js(code)
        except Exception:
            pass

    def state(self, s: str, status: str = ""):
        self.js(f"ui.state({json.dumps(s)}, {json.dumps(status)})")

    def heard(self, t: str):
        self.js(f"ui.heard({json.dumps(t)})")

    def show(self, t: str, error=False):
        self.js(f"ui.reply({json.dumps(t)}, {str(error).lower()})")

    def say(self, text: str, error=False):
        self.show(text, error)
        self.ears.pause()
        self.state("speaking")
        self.mouth.say(text)

    def idle(self):
        self.state("idle", "Diga “Zuri”")
        self.ears.wait_for_name()

    # ================================================================ início
    def start(self):
        if getattr(self, "_started", False):
            return
        self._started = True
        try:
            self.ears.start()
            self.idle()
        except Exception as e:
            traceback.print_exc()
            self.show(f"Não consegui abrir o microfone: {e}", True)
        system.set_startup(bool(self.settings.get("start_with_windows")))

    # ================================================================ espera do nome
    def on_wake_text(self, text: str, final: bool):
        rest = find_wake(text)
        if rest is None:
            return
        self.heard("“Zuri”")
        if self.wake_timer:
            self.wake_timer.cancel()
        if not rest.strip():
            # Só "Zuri": espera um instante; se a pessoa continuar ("volume três"), executa direto
            if final:
                self._go(self.greet_and_listen)
            else:
                self.wake_timer = threading.Timer(0.65, lambda: self._go(self.greet_and_listen))
                self.wake_timer.start()
            return
        if not final:
            return
        cmd = parse(rest).cmd
        if cmd.kind in ("play", "unknown", "wake_only"):
            self._go(lambda: self.greet_and_listen("Oi! Qual música?" if cmd.kind == "play" else None))
        else:
            self._go(lambda: self.run_turn(cmd))

    def _go(self, fn, wait=False):
        """Roda um turno de conversa em segundo plano (um de cada vez)."""
        self.ears.pause()

        def work():
            if not self.busy.acquire(blocking=wait):
                return
            try:
                fn()
            except Exception as e:
                traceback.print_exc()
                self.show(f"Ops, deu um erro: {e}", True)
            finally:
                self.busy.release()
                self.idle()
        threading.Thread(target=work, daemon=True).start()

    def tap(self):
        """Toque nas ondas ou no microfone."""
        if self.mouth.speaking:
            self.mouth.stop()
        self._go(lambda: self.listen_and_run(greeted=False))

    # ================================================================ conversa
    @staticmethod
    def greeting():
        h = datetime.now().hour
        period = "Oi, bom dia!" if 5 <= h < 12 else "Oi, boa tarde!" if 12 <= h < 18 else "Oi, boa noite!"
        return random.choice([period, "Oi!"])

    def greet_and_listen(self, text=None):
        self.state("thinking")
        self.say(text or self.greeting())
        self.listen_and_run(greeted=True)

    def listen_and_run(self, greeted: bool):
        restore = None
        if self.player["playing"] or system.get_percent() > 40:
            restore = system.get_percent()
            system.set_percent(min(restore, 25))  # abaixa a música para ouvir melhor
        self.state("listening", "Ouvindo…")
        try:
            audio = self.ears.capture()
        finally:
            if restore is not None:
                system.set_percent(restore)
        if not audio:
            self.show("Não ouvi nada. Diga “Zuri” quando quiser.")
            return
        self.state("thinking", "Entendendo…")
        try:
            text = Ears.recognize(audio)
        except Exception:
            self.say("Estou sem internet para entender o pedido.", True)
            return
        if not text:
            self.say("Não entendi. Pode repetir?")
            return self.listen_and_run(greeted)
        self.heard(f"“{text}”")
        cmd = parse(text).cmd
        if cmd.kind == "unknown" and greeted and not self.brain.configured:
            cmd = Cmd("play", query=text)  # depois do "Oi", um nome solto é música
        self.run_turn(cmd)

    def run_turn(self, cmd: Cmd):
        self.state("thinking", "Entendendo…")
        follow_up = self.execute(cmd)
        if follow_up:
            self.listen_and_run(greeted=True)

    # ================================================================ execução
    def execute(self, c: Cmd) -> bool:
        """Executa e responde. Devolve True se deve continuar ouvindo (a resposta foi uma pergunta)."""
        k = c.kind
        if k == "play":
            return self.play(c)
        if k == "pause":
            self.player_cmd("pause"); self.say("Pausado.")
        elif k == "resume":
            self.say("Continuando."); self.player_cmd("resume")
        elif k == "stop":
            self.player_cmd("stop"); self.say("Música parada.")
        elif k == "next":
            self.say("Próxima."); self.player_cmd("next")
        elif k == "previous":
            self.say("Música anterior."); self.player_cmd("previous")
        elif k == "volume_set":
            system.set_percent(c.percent); self.say(f"Volume {self._lvl(system.get_percent())}.")
        elif k == "volume_up":
            self.say(f"Volume {self._lvl(system.step(+1))}.")
        elif k == "volume_down":
            self.say(f"Volume {self._lvl(system.step(-1))}.")
        elif k == "mute":
            self.say("Silenciando."); system.mute(True)
        elif k == "unmute":
            system.mute(False); self.say("Som ativado.")
        elif k == "volume_query":
            self.say("O som está no mudo." if system.is_muted() else f"O volume está no {system.level()}.")
        elif k == "now_playing":
            t = self.player.get("title")
            self.say(f"Está tocando {t}." if self.player["active"] and t else "Não tem nenhuma música tocando agora.")
        elif k == "time":
            self.say(self.time_sentence())
        elif k == "date":
            d = datetime.now()
            self.say(f"Hoje é {ai.WEEKDAYS[d.weekday()]}, {d.day} de {ai.MONTHS[d.month - 1]}.")
        elif k == "morning":
            self.morning()
        elif k == "greeting":
            self.say(self.greeting().replace("Oi", "Olá") + " Em que posso ajudar?")
        elif k == "thanks":
            self.say(random.choice(["De nada!", "Por nada!", "Imagina!"]))
        elif k in ("cancel", "stop_listening"):
            self.show("Ok.")
        elif k == "wake_only":
            self.say(self.greeting())
            return True
        else:  # unknown → IA
            return self.ask_ai(c.text)
        return False

    @staticmethod
    def _lvl(pct):
        return 0 if pct <= 0 else max(1, min(10, round(pct / 10)))

    @staticmethod
    def time_sentence():
        n = datetime.now()
        h = {0: "É meia-noite", 1: "É uma hora", 12: "É meio-dia"}.get(n.hour, f"São {n.hour} horas")
        return f"{h}." if n.minute == 0 else f"{h} e {n.minute} minutos."

    def ask_ai(self, text: str) -> bool:
        if not self.brain.configured:
            self.say("Não entendi. Para conversar comigo, coloque a chave da IA nas configurações.")
            return False
        self.state("thinking", "Pensando…")
        self.show("Pensando…")
        try:
            answer, cmd = self.brain.ask(text, self.situation())
        except Exception as e:
            self.say(f"Não consegui falar com a IA agora: {e}.", True)
            return False
        if cmd:
            return self.execute(cmd)
        if answer:
            self.say(answer)
            return answer.rstrip().endswith("?")
        self.say("Não entendi. Pode repetir?")
        return True

    def situation(self):
        p = self.player
        playing = f"tocando: {p['title']}" + ("" if p["playing"] else " (pausado)") if p["active"] else \
            "nenhuma música tocando"
        return f"{playing}; volume {system.level()} de 10"

    # ================================================================ música (YouTube na janela da Zuri)
    def play(self, c: Cmd) -> bool:
        what = c.artist or c.query
        self.say(f"Tocando {what}.")
        self.state("thinking", "Procurando…")
        term = f"{c.artist} músicas" if c.artist else c.query
        videos = tools.youtube_search(term, 15 if c.artist else 8)
        if not videos:
            self.say("Não consegui pesquisar no YouTube. Confira a internet.", True)
            return False
        self.js(f"yt.playIds({json.dumps([v[0] for v in videos])}, {json.dumps([v[1] for v in videos])})")
        self.show("▶ " + (videos[0][1] or what))
        return False

    def player_cmd(self, action: str):
        if self.player["active"]:
            self.js(f"yt.{action}()")
        else:  # Spotify ou outro player do Windows
            system.media_key({"pause": "play_pause", "resume": "play_pause", "stop": "stop",
                              "next": "next", "previous": "previous"}[action])

    # ================================================================ avisos e rotina
    def front(self):
        """Traz a janela da Zuri para a frente (ex.: lembrete)."""
        try:
            self.window.restore()
            self.window.on_top = True
            self.window.on_top = False
        except Exception:
            pass

    def announce(self, text: str):
        def work():
            self.front()
            self.say(text)
            time.sleep(0.4)
            self.say(text)
        self._go(work, wait=True)

    def morning(self):
        parts = ["Bom dia! " + self.time_sentence()]
        city = self.settings.get("city")
        if city:
            w = tools.weather_morning(city)
            if w:
                parts.append(w)
        today = self.organizer.today()
        if today:
            parts.append(("Você tem um lembrete hoje: " if len(today) == 1 else f"Você tem {len(today)} lembretes hoje: ")
                         + "; ".join(f"{r['text']}, {self.organizer.when_text(r['at'])}" for r in today) + ".")
        self.say(" ".join(parts))
        music = self.settings.get("morning_music")
        if music:
            self.play(Cmd("play", query=music))


class Api:
    """Funções chamadas pela tela (JavaScript)."""

    def __init__(self, engine: Engine):
        self._e = engine

    def tap(self):
        self._e.tap()

    def get_settings(self):
        s = self._e.settings
        return {"ai_key": s.get("ai_key"), "city": s.get("city"), "morning_music": s.get("morning_music"),
                "start_with_windows": s.get("start_with_windows"), "wake_forms": self._e.ears.wake_forms,
                "volume": system.level()}

    def save_settings(self, data):
        s = self._e.settings
        for k in ("ai_key", "city", "morning_music"):
            if k in data:
                s.set(k, (data[k] or "").strip())
        if "start_with_windows" in data:
            s.set("start_with_windows", bool(data["start_with_windows"]))
            system.set_startup(bool(data["start_with_windows"]))
        return True

    def test_ai(self):
        try:
            text, _ = self._e.brain.ask("Diga oi e se apresente em uma frase curta.", "teste da configuração")
            provider = ai.provider_for(self._e.settings.get("ai_key"))[0]
            return {"ok": True, "text": f"IA funcionando ({provider})! Ela disse: {text}"}
        except Exception as e:
            return {"ok": False, "text": f"A IA não respondeu: {e}"}

    def weather_now(self):
        city = self._e.settings.get("city")
        return tools.weather_now_short(city) if city else None

    def volume(self, delta):
        system.step(int(delta))
        return system.level()

    def media(self, action):
        self._e.player_cmd(action)

    def open_url(self, url):
        import webbrowser
        webbrowser.open(url)

    def fullscreen(self):
        self._e.window.toggle_fullscreen()

    def player_state(self, active, playing, title):
        self._e.player.update(active=bool(active), playing=bool(playing), title=title or None)


def main():
    engine = Engine()
    api = Api(engine)
    ui = os.path.join(base_dir(), "zuri", "ui", "index.html")
    window = webview.create_window(APP_TITLE, url=ui, js_api=api, width=1280, height=800,
                                   min_size=(900, 600), background_color="#06112A")
    engine.window = window
    window.events.loaded += lambda: threading.Thread(target=engine.start, daemon=True).start()
    # http_server: a tela é servida em http://127.0.0.1 (o player do YouTube exige uma origem de verdade)
    webview.start(http_server=True, private_mode=False)


if __name__ == "__main__":
    main()

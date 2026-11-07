"""
Voz da Zuri (PC).

Ouvir: um único fluxo do microfone (16 kHz). Enquanto espera o nome, o áudio vai para o
reconhecedor OFFLINE Vosk, limitado ao nome "Zuri" + comandos simples (rápido, não erra com conversa).
Depois do "Oi", o pedido é gravado e enviado ao reconhecimento do Google (entende nomes de músicas).

Falar: vozes neurais da Microsoft (edge-tts, grátis); sem internet, a voz do próprio Windows.
"""
import array
import asyncio
import io
import json
import math
import os
import queue
import sys
import threading

RATE = 16000
BLOCK = 4000  # 0,25 s

CANDIDATES = ["zuri", "zury", "zúri", "suri", "júri", "juri", "zurique", "zu ri", "su ri", "ei zuri", "oi zuri"]
COMMANDS = ["pausar", "pausa", "pausa a música", "parar", "para", "para a música", "stop", "continuar", "continua",
            "volta a tocar", "próxima", "próxima música", "pula", "pula essa", "anterior", "música anterior",
            "aumentar", "aumenta", "aumenta o volume", "diminuir", "diminui", "diminui o volume", "abaixa",
            "abaixa o volume", "mais alto", "mais baixo", "silenciar", "volume máximo", "que horas são",
            "bom dia", "boa tarde", "boa noite", "tchau", "obrigado", "obrigada"]
for _n in ["um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez"]:
    COMMANDS += [f"volume {_n}", f"volume no {_n}"]


def base_dir():
    return getattr(sys, "_MEIPASS", os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


class Ears:
    """Microfone + reconhecedores. [on_wake_text](texto, final) é chamado no modo de espera."""

    def __init__(self, on_wake_text, on_level=None):
        self.on_wake_text = on_wake_text
        self.on_level = on_level
        self.mode = "mute"           # wake | capture | mute
        self.q = queue.Queue()
        self.wake_forms = []
        self._rec = None
        self._stream = None

    # ------------------------------------------------------------ início
    def start(self):
        import sounddevice as sd
        from vosk import KaldiRecognizer, Model, SetLogLevel
        SetLogLevel(-1)
        model_dir = os.path.join(base_dir(), "model")
        self.model = Model(model_dir)
        vocab = None
        for p in ("graph/words.txt", "words.txt"):
            f = os.path.join(model_dir, p)
            if os.path.exists(f):
                with open(f, encoding="utf-8") as fh:
                    vocab = {line.split(" ")[0] for line in fh}
                break

        def known(phrase):
            return vocab is None or all(w in vocab for w in phrase.split(" "))

        forms = [c for c in CANDIDATES if known(c)]
        self.wake_forms = forms
        cmds = [c for c in COMMANDS if known(c)]
        phrases = forms + [f"{f} {c}" for f in forms if " " not in f for c in cmds]
        self._grammar = json.dumps(phrases + ["[unk]"], ensure_ascii=False) if forms else None
        self._new_rec()
        self._stream = sd.RawInputStream(samplerate=RATE, blocksize=BLOCK, dtype="int16", channels=1,
                                         callback=lambda data, frames, t, status: self.q.put(bytes(data)))
        self._stream.start()
        threading.Thread(target=self._loop, daemon=True).start()
        self.mode = "wake"

    def _new_rec(self):
        from vosk import KaldiRecognizer
        self._rec = KaldiRecognizer(self.model, RATE, self._grammar) if self._grammar else KaldiRecognizer(self.model, RATE)
        self._last_partial = ""

    @staticmethod
    def rms(block: bytes) -> float:
        a = array.array("h", block)
        if not a:
            return 0.0
        return math.sqrt(sum(x * x for x in a[::4]) / (len(a) / 4))

    # ------------------------------------------------------------ espera do nome
    def _loop(self):
        while True:
            block = self.q.get()
            if self.mode != "wake":
                if self.mode == "capture":
                    self._capture_buf.put(block)
                continue
            if self._rec.AcceptWaveform(block):
                text = json.loads(self._rec.Result()).get("text", "")
                self._deliver(text, True)
            else:
                text = json.loads(self._rec.PartialResult()).get("partial", "")
                if text and text != self._last_partial:
                    self._last_partial = text
                    self._deliver(text, False)

    def _deliver(self, text, final):
        words = [w for w in text.split() if w != "[unk]"]
        if words:
            self.on_wake_text(" ".join(words), final)

    def wait_for_name(self):
        self._new_rec()
        self._drain()
        self.mode = "wake"

    def pause(self):
        self.mode = "mute"

    def _drain(self):
        while not self.q.empty():
            try:
                self.q.get_nowait()
            except queue.Empty:
                break

    # ------------------------------------------------------------ gravar o pedido
    def capture(self, max_s=9.0, wait_s=6.0):
        """Grava até a pessoa parar de falar. Devolve os bytes, ou None se ninguém falou."""
        self._capture_buf = queue.Queue()
        self._drain()
        self.mode = "capture"
        frames, noise, speech, quiet, waited = [], [], False, 0, 0.0
        try:
            while True:
                block = self._capture_buf.get(timeout=3)
                level = self.rms(block)
                if self.on_level:
                    self.on_level(min(1.0, level / 3000))
                if len(noise) < 2:
                    noise.append(level)
                threshold = max(450.0, (sum(noise) / len(noise)) * 2.2)
                if level > threshold:
                    speech, quiet = True, 0
                elif speech:
                    quiet += 1
                if speech:
                    frames.append(block)
                else:
                    frames = (frames + [block])[-2:]  # guarda um pedacinho antes da fala
                    waited += BLOCK / RATE
                    if waited > wait_s:
                        return None
                if speech and (quiet >= 4 or len(frames) * BLOCK / RATE > max_s):
                    return b"".join(frames)
        except queue.Empty:
            return None
        finally:
            self.mode = "mute"

    @staticmethod
    def recognize(audio: bytes) -> str | None:
        import speech_recognition as sr
        try:
            return sr.Recognizer().recognize_google(sr.AudioData(audio, RATE, 2), language="pt-BR")
        except sr.UnknownValueError:
            return None


class Mouth:
    """Fala com voz neural (edge-tts) e, sem internet, com a voz do Windows."""

    def __init__(self, voice="pt-BR-FranciscaNeural"):
        self.voice = voice
        self.speaking = False
        import pygame
        pygame.mixer.init(frequency=24000)
        self.pygame = pygame

    def say(self, text: str):
        if not text:
            return
        self.speaking = True
        try:
            audio = self._neural(text)
            if audio:
                self.pygame.mixer.music.load(io.BytesIO(audio), "mp3")
                self.pygame.mixer.music.play()
                while self.pygame.mixer.music.get_busy():
                    self.pygame.time.wait(50)
            else:
                self._windows_voice(text)
        finally:
            self.speaking = False

    def stop(self):
        try:
            self.pygame.mixer.music.stop()
        except Exception:
            pass

    def _neural(self, text):
        try:
            import edge_tts

            async def run():
                buf = bytearray()
                async for chunk in edge_tts.Communicate(text, self.voice, rate="+8%").stream():
                    if chunk["type"] == "audio":
                        buf.extend(chunk["data"])
                return bytes(buf)
            return asyncio.run(run())
        except Exception:
            return None

    @staticmethod
    def _windows_voice(text):
        try:
            import pyttsx3
            eng = pyttsx3.init()
            for v in eng.getProperty("voices"):
                if "pt" in (v.id or "").lower() or "portug" in (v.name or "").lower():
                    eng.setProperty("voice", v.id)
                    break
            eng.say(text)
            eng.runAndWait()
        except Exception:
            pass

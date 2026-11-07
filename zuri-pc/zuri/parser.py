"""
Interpretador de comandos da Zuri (versão PC) — o mesmo jeito de entender do app Android.

Comandos simples (pausar, volume 3, próxima, que horas são, bom dia...) são reconhecidos aqui,
na hora e sem internet. Todo o resto vira Unknown e vai para a IA.
"""
import re
import unicodedata
from dataclasses import dataclass, field


# ----------------------------------------------------------------------------- comandos

@dataclass
class Cmd:
    kind: str                      # play, pause, resume, stop, next, previous, volume_set, volume_up,
                                   # volume_down, mute, unmute, volume_query, now_playing, time, date,
                                   # morning, greeting, thanks, stop_listening, cancel, wake_only, unknown
    query: str = ""                # play: o que buscar
    artist: str | None = None      # play: artista
    percent: int = 0               # volume_set
    text: str = ""                 # unknown: frase original


@dataclass
class Parsed:
    cmd: Cmd
    had_wake: bool = False


# ----------------------------------------------------------------------------- texto

def normalize(s: str) -> str:
    s = unicodedata.normalize("NFD", s.lower())
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9%]", "", s)


def tokenize(raw: str) -> tuple[list[str], list[str]]:
    original = re.findall(r"[\w%]+", raw, flags=re.UNICODE)
    norm = [normalize(t) for t in original]
    # "50%" -> "50", "%" ; "por cento" -> "%"
    o2, n2 = [], []
    i = 0
    while i < len(norm):
        n, o = norm[i], original[i]
        if len(n) > 1 and n.endswith("%"):
            n2 += [n[:-1], "%"]; o2 += [o[:-1], "%"]
        elif n == "por" and i + 1 < len(norm) and norm[i + 1] == "cento":
            n2.append("%"); o2.append("%"); i += 1
        elif n:
            n2.append(n); o2.append(o)
        i += 1
    return o2, n2


ALIASES = {
    "posar": "pausar", "pousar": "pausar", "pauza": "pausa", "pose": "pausa", "pouse": "pausa",
    "estop": "stop", "istop": "stop", "estopi": "stop", "istopi": "stop", "stopi": "stop", "estope": "stop",
    "valume": "volume", "volumes": "volume", "almentar": "aumentar", "almenta": "aumenta",
    "torcar": "tocar", "tokar": "tocar", "brigado": "obrigado", "brigada": "obrigada",
}

# ----------------------------------------------------------------------------- nome "Zuri"

WAKE_TOKEN = re.compile(r"^(zu|su|ju|zo)r[iy]$|^zurique$|^zurich$|^zuris$")
WAKE_JOINED = re.compile(r"^(zu|su)r[iy]$")
WAKE_FILLERS = {"ei", "oi", "ok", "hey", "hei", "e", "o", "ow", "ah"}


def _wake_len(tokens: list[str], i: int) -> int:
    if i >= len(tokens):
        return 0
    if i + 1 < len(tokens) and WAKE_JOINED.match(tokens[i] + tokens[i + 1]):
        return 2
    return 1 if WAKE_TOKEN.match(tokens[i]) else 0


def _wake_end(tokens: list[str]) -> int:
    n = _wake_len(tokens, 0)
    if n:
        return n
    if tokens and tokens[0] in WAKE_FILLERS:
        n = _wake_len(tokens, 1)
        if n:
            return 1 + n
    return -1


def find_wake(raw: str) -> str | None:
    """Procura o nome em qualquer ponto; devolve o que veio depois ('' = só o nome) ou None."""
    original, norm = tokenize(raw)
    for i in range(len(norm)):
        n = _wake_len(norm, i)
        if n:
            return " ".join(original[i + n:])
    return None


# ----------------------------------------------------------------------------- números

UNITS = {"zero": 0, "um": 1, "uma": 1, "dois": 2, "duas": 2, "tres": 3, "quatro": 4, "cinco": 5, "seis": 6,
         "sete": 7, "oito": 8, "nove": 9, "dez": 10, "onze": 11, "doze": 12, "treze": 13, "catorze": 14,
         "quatorze": 14, "quinze": 15, "dezesseis": 16, "dezessete": 17, "dezoito": 18, "dezenove": 19}
TENS = {"vinte": 20, "trinta": 30, "quarenta": 40, "cinquenta": 50, "sessenta": 60, "setenta": 70,
        "oitenta": 80, "noventa": 90, "cem": 100, "cento": 100}


def number_in(tokens: list[str]) -> int | None:
    for t in tokens:
        if t.isdigit():
            return int(t)
    total, found = 0, False
    for i, w in enumerate(tokens):
        v = TENS.get(w, UNITS.get(w))
        if v is not None:
            if w in ("um", "uma") and not found and i + 1 < len(tokens) and tokens[i + 1] not in ("%", "por"):
                continue
            total += v
            found = True
        elif w == "e" and found:
            continue
        elif found:
            break
    return total if found else None


# ----------------------------------------------------------------------------- interpretação

PLAY_PREFIXES = [["eu", "quero", "ouvir"], ["quero", "ouvir"], ["quero", "escutar"], ["toca", "ai"],
                 ["tocar"], ["toca"], ["toque"], ["reproduzir"], ["reproduz"], ["colocar"], ["coloca"],
                 ["coloque"], ["bota"], ["botar"], ["poe"], ["ouvir"], ["escutar"], ["play"]]
NOT_MUSIC = {"volume", "som", "timer", "alarme", "despertador", "lanterna"}
RESUME_ONLY = {"", "musica", "a musica", "de novo", "novamente", "o som", "som"}
NEXT_ONLY = {"a proxima", "proxima", "a proxima musica", "proxima musica", "outra", "outra musica"}


def _has(text: str, *phrases: str) -> bool:
    p = f" {text} "
    return any(f" {x} " in p for x in phrases)


def _starts(tokens: list[str], prefix: list[str]) -> bool:
    return tokens[:len(prefix)] == prefix


def parse(raw: str) -> Parsed:
    original, norm = tokenize(raw)
    norm = [ALIASES.get(t, t) for t in norm]
    end = _wake_end(norm)
    had_wake = end > 0
    start = end if had_wake else 0
    if had_wake and start >= len(norm):
        return Parsed(Cmd("wake_only"), True)
    tokens, orig = norm[start:], original[start:]
    text = " ".join(tokens)
    if not text.strip():
        return Parsed(Cmd("unknown", text=raw), had_wake)
    return Parsed(_match(tokens, orig, text), had_wake)


def _match(tokens: list[str], orig: list[str], text: str) -> Cmd:
    # Música
    for p in PLAY_PREFIXES:
        if _starts(tokens, p):
            rest = tokens[len(p):]
            joined = " ".join(rest)
            if joined in RESUME_ONLY:
                return Cmd("resume")
            if joined in NEXT_ONLY:
                return Cmd("next")
            if any(t in NOT_MUSIC for t in rest[:3]):
                break
            return _play(rest, orig[len(p):])

    if _has(text, "parar de ouvir", "para de ouvir", "pode dormir", "tchau", "ate mais", "desliga a escuta"):
        return Cmd("stop_listening")

    # Volume (escala 1 a 10; com % ou acima de 10 vale como porcentagem)
    if _has(text, "qual o volume", "qual volume", "que volume"):
        return Cmd("volume_query")
    if _has(text, "volume", "som", "nivel"):
        n = number_in(tokens)
        if n is not None:
            pct = n * 10 if ("%" not in tokens and 0 <= n <= 10) else n
            return Cmd("volume_set", percent=max(0, min(100, pct)))
        if _has(text, "maximo", "no talo", "no maximo"):
            return Cmd("volume_set", percent=100)
        if _has(text, "minimo"):
            return Cmd("volume_set", percent=10)
    if _has(text, "tirar do mudo", "tira do mudo", "desmutar", "ativa o som"):
        return Cmd("unmute")
    if _has(text, "silenciar", "silencia", "mudo", "mutar"):
        return Cmd("mute")
    mentions_volume = _has(text, "volume", "som")
    if _has(text, "mais alto", "aumente") or (mentions_volume and _has(text, "aumentar", "aumenta", "sobe")) \
            or text in ("aumentar", "aumenta"):
        return Cmd("volume_up")
    if _has(text, "mais baixo", "abaixe") or (mentions_volume and _has(text, "diminuir", "diminui", "abaixar", "abaixa", "baixa")) \
            or text in ("diminuir", "diminui", "abaixar", "abaixa"):
        return Cmd("volume_down")

    if _has(text, "que musica e essa", "qual musica", "que musica", "o que esta tocando", "o que ta tocando"):
        return Cmd("now_playing")
    if _has(text, "que horas", "que hora", "horas sao") or text in ("horas", "hora"):
        return Cmd("time")
    if _has(text, "que dia e hoje", "que dia hoje", "qual a data", "data de hoje"):
        return Cmd("date")

    if _has(text, "anterior", "volta a musica", "musica de antes"):
        return Cmd("previous")
    if _has(text, "proxima", "proximo", "pula", "pular", "passa", "avanca", "seguinte", "troca a musica", "outra musica"):
        return Cmd("next")
    if _has(text, "despausar", "continuar", "continua", "continue", "retomar", "volta a tocar", "play"):
        return Cmd("resume")
    if _has(text, "pausar", "pausa", "pause"):
        return Cmd("pause")
    if _has(text, "parar", "para a musica", "para de tocar", "para tudo", "stop", "chega", "pare") \
            or text == "para" or (tokens and tokens[0] == "stop"):
        return Cmd("stop")

    if (len(tokens) <= 4 and _has(text, "bom dia")) or _has(text, "rotina da manha", "rotina de bom dia"):
        return Cmd("morning")
    if _has(text, "obrigado", "obrigada", "valeu"):
        return Cmd("thanks")
    if len(tokens) <= 4 and _has(text, "boa tarde", "boa noite", "oi", "ola", "tudo bem", "e ai"):
        return Cmd("greeting")
    if text in ("cancela", "cancelar", "nada", "deixa", "deixa pra la", "esquece", "nao"):
        return Cmd("cancel")

    return Cmd("unknown", text=" ".join(orig))


def _play(rest: list[str], rest_orig: list[str]) -> Cmd:
    for suffix in (["no", "youtube"], ["no", "spotify"], ["pra", "mim"], ["por", "favor"]):
        if len(rest) > len(suffix) and rest[-len(suffix):] == suffix:
            rest, rest_orig = rest[:-len(suffix)], rest_orig[:-len(suffix)]
    for p in (["as", "musicas", "do"], ["as", "musicas", "da"], ["as", "musicas", "de"], ["musicas", "do"],
              ["musicas", "da"], ["musicas", "de"], ["musicas", "dos"], ["algo", "do"], ["algo", "da"],
              ["algo", "de"], ["um", "pouco", "de"]):
        if len(rest) > len(p) and _starts(rest, p):
            artist = " ".join(rest_orig[len(p):])
            return Cmd("play", query=artist, artist=artist)
    for p in (["a", "musica"], ["musica"]):
        if len(rest) > len(p) and _starts(rest, p):
            return Cmd("play", query=" ".join(rest_orig[len(p):]))
    q = " ".join(rest_orig).strip()
    return Cmd("play", query=q) if q else Cmd("resume")

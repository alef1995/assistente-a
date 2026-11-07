from zuri.parser import parse, find_wake

def k(s): return parse(s).cmd

cases = [
    ("Zuri, tocar Alcione", ("play", "Alcione")),
    ("tocar músicas do Henrique e Juliano", ("play", "Henrique e Juliano")),
    ("Zuri volume três", ("volume_set", 30)),
    ("volume 50%", ("volume_set", 50)),
    ("coloca o volume no sete", ("volume_set", 70)),
    ("pausar música", ("pause",)), ("stop", ("stop",)), ("istópi", ("stop",)),
    ("próxima", ("next",)), ("música anterior", ("previous",)), ("continuar", ("resume",)),
    ("que horas são", ("time",)), ("Zuri, bom dia", ("morning",)), ("boa noite", ("greeting",)),
    ("como vai ser o tempo amanhã", ("unknown",)), ("Zuri", ("wake_only",)), ("Zury", ("wake_only",)),
    ("aumentar volume", ("volume_up",)), ("abaixa o som", ("volume_down",)),
]
fail = 0
for s, exp in cases:
    c = k(s)
    got = (c.kind,) + ((c.query,) if exp[0] == "play" else (c.percent,) if exp[0] == "volume_set" else ())
    if got != exp:
        fail += 1; print("FAIL", s, got, exp)
assert find_wake("amor zuri") == ""
assert find_wake("zuri pausa a música") == "pausa a música"
assert find_wake("a vida é bela") is None
assert not parse("Alexa").had_wake
print(len(cases), "casos,", fail, "falhas")

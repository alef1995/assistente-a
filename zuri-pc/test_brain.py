import json, os, tempfile, types, sys
os.environ["APPDATA"] = tempfile.mkdtemp()
import zuri.tools as tools, zuri.ai as ai

calls = []
class FakeResp:
    def __init__(self, j, code=200): self._j, self.status_code = j, code
    def json(self): return self._j
def fake_post(url, json=None, headers=None, timeout=None):
    calls.append(json)
    msgs = json["messages"]
    if msgs[-1]["role"] == "user" and "tempo" in msgs[-1]["content"]:
        return FakeResp({"choices":[{"message":{"content":None,"tool_calls":[{"id":"c1","function":{"name":"get_weather","arguments":"{}"}}]}}]})
    if msgs[-1]["role"] == "tool":
        return FakeResp({"choices":[{"message":{"content":"Agora faz **32 graus** com garoa.","tool_calls":None}}]})
    if "animadas" in msgs[-1]["content"]:
        return FakeResp({"choices":[{"message":{"content":"","tool_calls":[{"id":"c2","function":{"name":"play_music","arguments":"{\"query\":\"pagode anos 90\"}"}}]}}]})
    if "lista" in msgs[-1]["content"]:
        return FakeResp({"choices":[{"message":{"content":"","tool_calls":[{"id":"c3","function":{"name":"shopping_list_add","arguments":"{\"items\":[\"leite\",\"pão\"]}"}}]}}]})
    return FakeResp({"choices":[{"message":{"content":"Oi, eu sou a Zuri!"}}]})
ai.requests.post = fake_post
tools.weather = lambda city, days=3: f"Cidade: {city}. Agora 32°C, garoa."

s = tools.Settings(); s.set("ai_key", "gsk_teste"); s.set("city", "Belo Horizonte")
org = tools.Organizer(on_due=lambda t: None)
b = ai.Brain(s, org)
print(b.ask("como está o tempo?", "nenhuma música"))
print(b.ask("bota umas músicas animadas", "nenhuma música"))
print(b.ask("coloca leite e pão na lista", "x"))
print(org.describe_list())
print(org.add_reminder("buscar o pão", in_minutes=30))
print(org.describe_reminders())
assert calls[0]["model"] == "openai/gpt-oss-120b" and calls[0]["reasoning_effort"] == "low"
assert ai.provider_for("sk-or-x")[0] == "OpenRouter" and ai.provider_for("AIzaX")[0] == "Gemini"
print("ok")

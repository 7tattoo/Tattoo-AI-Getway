import json,random,time,urllib.request,urllib.error
BASE62="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
CHAT="https://opencode.ai/zen/v1/chat/completions"
def b62(n): return "".join(BASE62[random.randrange(62)] for _ in range(n))
def ses_real():
    return "ses_"+int(time.time()*1000).to_bytes(6,"big").hex()+b62(14)
def post(auth, label, extra=None, noauth=False):
    h={"Content-Type":"application/json","Accept":"text/event-stream",
       "User-Agent":"opencode/1.18.31","x-opencode-client":"cli",
       "x-opencode-project":"global","x-opencode-session":ses_real(),
       "x-opencode-request":"req_"+random.randbytes(16).hex()}
    if not noauth: h["Authorization"]=auth
    body={"model":"space-bunny-free","stream":True,"messages":[{"role":"user","content":"hi"}]}
    if extra: body.update(extra)
    r=urllib.request.Request(CHAT,data=json.dumps(body).encode(),headers=h,method="POST")
    try:
        with urllib.request.urlopen(r,timeout=25) as s:
            print(f"  {label:<40} -> 200 {s.read(120)[:100]!r}")
    except urllib.error.HTTPError as e:
        print(f"  {label:<40} -> HTTP {e.code} {e.read().decode()[:110]}")
    except Exception as e:
        print(f"  {label:<40} -> ERR {e}")
post("Bearer public","noauth-header(no Authorization)")
post("Bearer public","public")
post("Bearer sk-"+random.randbytes(24).hex(),"random sk- key")
post("Bearer sk-"+random.randbytes(24).hex(),"sk- + models field",{"models":["space-bunny-free"]})
post("Bearer public","public + X-Session-Id",None)

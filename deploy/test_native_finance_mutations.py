"""Synthetic same-origin Finance mutation tests, zero production writes."""
import json,os,pathlib,socket,subprocess,tempfile,time,urllib.request,urllib.error
ROOT=pathlib.Path(__file__).parent/"wallos-extension"
with tempfile.TemporaryDirectory(prefix="pfs-unified-actions-") as temp:
 root=pathlib.Path(temp)
 (root/"includes").mkdir()
 (root/"includes/header.php").write_text('<?php session_start(); $_SESSION["finance_csrf"]="unit-test-csrf"; ?>')
 for name in ("finance-unified-action.php","finance-unified-common.php"):
  (root/name).write_bytes((ROOT/name).read_bytes())
 (root/"ledger-common.php").write_text('''<?php
 function ledger_escape($v){return htmlspecialchars((string)$v);}
 function ledger_request($method,$path,$body=null){
  file_put_contents("/fixture/calls.jsonl",json_encode(["method"=>$method,"path"=>$path,"body"=>$body])."\\n",FILE_APPEND);
  return ["ok"=>true];
 }
?>''')
 (root/"calls.jsonl").write_text("")
 os.chmod(root,0o777);os.chmod(root/"calls.jsonl",0o666)
 with socket.socket() as sock:sock.bind(("127.0.0.1",0));port=sock.getsockname()[1]
 name="pfs-native-php-action-test"
 subprocess.run(["docker","rm","-f",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 subprocess.run(["docker","run","-d","--rm","--network","host","--name",name,
    "--entrypoint","php","-v",str(root)+":/fixture:rw",
    "finance-wallos-extension:native-ui-staging",
    "-S",f"127.0.0.1:{port}","-t","/fixture"],check=True,stdout=subprocess.DEVNULL)
 try:
  for i in range(30):
   try:
    with socket.create_connection(("127.0.0.1",port),timeout=.2):break
   except OSError:time.sleep(.2)
  def send(payload):
   req=urllib.request.Request(f"http://127.0.0.1:{port}/finance-unified-action.php",
    data=json.dumps(payload).encode(),headers={"Content-Type":"application/json"},method="POST")
   try:
    with urllib.request.urlopen(req,timeout=5) as r:return r.status,json.load(r)
   except urllib.error.HTTPError as e:return e.code,json.load(e)
  ok,body=send({"action":"edit","csrf":"unit-test-csrf","id":17,
    "amount_cents":1900,"merchant":"synthetic","category":"FOOD"})
  assert ok==200,(ok,body)
  ok,body=send({"action":"review","csrf":"unit-test-csrf","id":17,"status":"excluded"})
  assert ok==200,(ok,body)
  ok,body=send({"action":"budget","csrf":"unit-test-csrf","month":"2026-10","amount_cents":240000})
  assert ok==200,(ok,body)
  ok,body=send({"action":"review","csrf":"invalid","id":17,"status":"excluded"})
  assert ok==403,(ok,body)
  ok,body=send({"action":"edit","csrf":"unit-test-csrf","id":17,"amount_cents":0,
    "merchant":"synthetic","category":"FOOD"})
  assert ok==422,(ok,body)
  ok,body=send({"action":"review","csrf":"unit-test-csrf","id":17,"status":"refund"})
  assert ok==422,(ok,body)
  calls=[json.loads(x) for x in (root/"calls.jsonl").read_text().splitlines()]
  assert len(calls)==3,calls
  assert calls[0]["path"]=="integration/transaction/17" and calls[0]["method"]=="PATCH"
  assert calls[1]["path"]=="review/17" and calls[1]["body"]=={"status":"excluded"}
  assert calls[2]["path"]=="budget/2026-10" and calls[2]["body"]=={"amount_cents":240000}
  print("NATIVE_FINANCE_MUTATION_MOCK_PASS: edit, review, budget; rejects CSRF & invalid updates")
 finally:
  subprocess.run(["docker","rm","-f",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

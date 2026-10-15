<?php
/** Synthetic regression: estimated start-month cost is not a real payment. */
require __DIR__.'/wallos-extension/finance-unified-common.php';
function check_plan(bool $ok,string $message):void {
    if(!$ok){fwrite(STDERR,"FAILED: ".$message."\n");exit(20);}
}
$db=new SQLite3(':memory:');
$db->exec('CREATE TABLE currencies(id INTEGER PRIMARY KEY,code TEXT,rate TEXT)');
$db->exec('CREATE TABLE subscriptions(id INTEGER PRIMARY KEY,name TEXT,price REAL,currency_id INTEGER,next_payment TEXT,cycle INTEGER,frequency INTEGER,start_date TEXT,inactive INTEGER,user_id INTEGER,auto_renew INTEGER)');
$db->exec('CREATE TABLE last_exchange_update(user_id INTEGER,date TEXT)');
$db->exec("INSERT INTO currencies VALUES (1,'CNY','1'),(2,'HKD','1.1')");
$db->exec("INSERT INTO subscriptions VALUES
 (1,'Synthetic phone',47,1,'2026-11-01',3,1,'2026-10-09',0,1,1),
 (2,'Synthetic HK card',6,2,'2026-11-01',3,1,'2026-10-09',0,1,1),
 (3,'Synthetic inactive',10,1,'2026-11-01',3,1,'2026-10-09',1,1,1),
 (4,'Synthetic weekly',10,1,'2026-10-08',2,1,'2026-10-09',0,1,1),
 (5,'Synthetic ancient yearly',100,1,'2027-03-15',4,1,'2026-10-09',0,1,1),
 (6,'Synthetic one-time',25,1,'2026-10-01',5,1,'2026-10-09',0,1,0)");
$oct=finance_plan_month($db,1,'2026-10');
$events=[];
foreach($oct['events'] as $e){$events[$e['name']][]=$e;}
check_plan(count($events['Synthetic phone']??[])===1,'October phone inclusion');
check_plan(count($events['Synthetic HK card']??[])===1,'October HK card inclusion');
check_plan(($events['Synthetic phone'][0]['estimated_first_month']??false)===true,'first month estimate flag');
check_plan(($events['Synthetic HK card'][0]['estimated_first_month']??false)===true,'HK estimate flag');
check_plan(!isset($events['Synthetic inactive']),'exclude inactive');
check_plan(!isset($events['Synthetic one-time']),'exclude one-time billed before start date');
check_plan(count($events['Synthetic weekly']??[])===3,'weekly cycles still require exact start day');
check_plan(($oct['due_cny_cents']??0) >= 4700,'October total includes phone');
$sept=finance_plan_month($db,1,'2026-09');
check_plan(count($sept['events'])===0,'no retroactive historical events before start month');
$nov=finance_plan_month($db,1,'2026-11');
$nov2=array_values(array_filter($nov['events'],static fn($x)=>$x['name']==='Synthetic phone'||$x['name']==='Synthetic HK card'));
check_plan(count($nov2)===2,'November recurring payments once each');
check_plan(!($nov2[0]['estimated_first_month']??true)&&!($nov2[1]['estimated_first_month']??true),'November entries are not first-month estimates');
$db->close();
echo "SYNTHETIC_FIRST_MONTH_PLANS_PASS\n";

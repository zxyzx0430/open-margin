<?php
require __DIR__ . '/wallos-extension/finance-unified-common.php';
$base=finance_to_cny_cents(19.99,0.14943);
$newer=finance_to_cny_cents(19.99,0.2);
if($base!==13378 || $newer!==9995 || $newer===$base)exit(11);
if(finance_to_cny_cents(19.99,0.0)!==null)exit(12);
if(abs(finance_subscription_months(4,1)-12.0)>1e-8)exit(13);
if(abs(finance_subscription_months(3,1)-1.0)>1e-8)exit(14);
if(finance_subscription_months(5,1)!==null)exit(15);
echo "SYNTHETIC_DYNAMIC_FX_PASS: changing rate changes CNY amount, unavailable rate flagged, billing cycles computed\n";

# v1.47.8 passive capture evidence and feedback

Baseline: 766f7ef (v1.47.7). Reproduced feedback FB-1126-4110: seven pending entries during Alipay history browsing; user A FB-1125-6CE5: repeated history-like amount candidates. Do not store raw reports in the repository.

## Implemented
- Shared history-page guard for node and passive OCR input; explicit import remains unchanged.
- Passive completion/admission policy: no cancelled/failed/unfinished transaction preview in the review inbox; ambiguous node candidates get OCR verification; preserve independently supported real transactions when OCR fails.
- Page visit identity for short burst duplicate suppression; preserve distinct order IDs, independent visits, known actual times and different merchants.
- Keep the legacy service, Android 7–10 node path and existing privacy/permission rules.
- Feedback success receipt with ID and copy, inline failure/retry. No new decision step for ordinary capture.

## Upstream design references (GPL-3.0, no wholesale code import)
- https://github.com/AldenHe/huayoupu-ledger : payment event identity separate from ledger identity, serialized recognition queue. Do not copy the policy that excludes all historical single details.
- https://github.com/AutoAccountingOrg/AutoAccounting : capture state, structure signature and failure visibility. Do not introduce Root or other permissions.

## Regression gate
Retain all existing fixtures. Add history with success/status links, refund records, genuine detail, service stream, completed beside unfinished, empty OCR recovery, independent order IDs, rapid callback bursts, JSON compatibility. Unit tests do not establish real-device precision or recall: compare on Xiaomi and ColorOS before claiming no regression. Persistent recovery of OCR-only failures and full shadow replay require real sample evidence, not invented bill entries.

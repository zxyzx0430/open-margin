# Live month and unclaimed red packet fix

**False positive:** unclaimed monetary promotions cannot be counted as actual purchases. The Android parser requires evidence of settlement; its WeChat accessibility paths reject pending red packets or unconfirmed transfers.

**Empty Wallos page:** the backend had captured current-month transactions but the PHP UI logged runtime exceptions. The old UI substituted a zero-balance page on failure. Read-only transport now retries transient errors, and the UI displays a clear failure state instead of misleading zero expenditure. Finance HTML is never service-worker cached.

**Existing entries:** An owner-confirmed promotional row may be marked excluded in the server ledger, preserving audit history. Remote corrections cannot silently delete the corresponding private Android Room row. One-time cleanup on the device, using the trash action, is separate.

The finance ledger counts successfully ingested transactions in the current month immediately; it does not wait until the month is complete.

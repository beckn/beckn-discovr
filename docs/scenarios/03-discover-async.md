# 03 — Discover API — Asynchronous (POST)

## Overview
Verify asynchronous discover via POST. Returns ACK immediately, dispatches results via response-dispatcher to bapUri callback.

## Scenarios

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-16 | Async discover | POST `http://localhost:8082/discover` with textSearch | HTTP 200 `{"message":{"status":"ACK","messageId":"..."}}` |
| SC-17 | Response dispatcher consumed response | `docker logs response-dispatcher` | Log entry showing `on_discover` callback attempt to `bapUri` |

## Verification Depth

- SC-16: Verify HTTP 200 (not 202) and nested `{"message":{"status":"ACK","messageId":"..."}}` body
- SC-17: Check response-dispatcher logs for `callback.sent` or `callback.error` event with the BAP's URI
- If BAP URI is unreachable, expect `callback.error` in logs (not a failure — we're verifying the dispatcher tried)

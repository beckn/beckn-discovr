# WireMock registry stubs (S1)

**Do not point WireMock `--root-dir` at `mappings/` for multi-mode drills.**

| Path | Contents |
|------|----------|
| `mappings/registry-keys.json` | Default **200** key lookup (priority 1) — safe if loaded alone |
| `mappings-by-mode/registry-down/` | **503** registry-down stub — load only for that mode |
| `__generated/` | Written by `mock-registry.sh` for the active `--mode` (preferred) |

```bash
# Preferred — writes a single mapping into __generated/ and starts WireMock on it
./reliability/scripts/mock-registry.sh start --mode cache-hit
./reliability/scripts/mock-registry.sh start --mode cache-miss
./reliability/scripts/mock-registry.sh start --mode registry-down
```

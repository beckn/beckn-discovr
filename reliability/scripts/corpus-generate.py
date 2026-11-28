#!/usr/bin/env python3
"""Stream synthetic Beckn v2.0 catalog resources for the corpus ladder.

Emits dual-store payloads for bulk load (ES NDJSON + PG row dicts) or
catalog-push JSON batches. Designed to stream — does not materialize the
full corpus in memory.

Usage:
  corpus-generate.py --profile 1M --format summary
  corpus-generate.py --profile local-smoke --format es-ndjson > /tmp/es.ndjson
  corpus-generate.py --profile local-smoke --format pg-copy > /tmp/item.tsv
  corpus-generate.py --profile local-smoke --format push-batch --out-dir /tmp/push

See reliability/config/corpus-profiles.yaml and Agent Store
docs/corpus-ladder-simulation.md.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator


ROOT = Path(__file__).resolve().parents[2]
PROFILES_PATH = ROOT / "reliability" / "config" / "corpus-profiles.yaml"

# Minimal YAML subset reader (no PyYAML dependency on agent VMs)
def _parse_profiles(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    profiles: dict[str, dict[str, Any]] = {}
    golden: list[dict[str, Any]] = []
    current: str | None = None
    section = None
    in_golden = False
    golden_item: dict[str, Any] | None = None
    network_id = "beckn.one/retail"
    schema_context = "https://schema.org/"
    schema_type = "Product"

    for raw in text.splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        s = line.strip()
        if s.startswith("profiles:"):
            section = "profiles"
            in_golden = False
            continue
        if s.startswith("golden:"):
            section = "golden"
            in_golden = True
            golden_item = None
            continue
        if s.startswith("network_id:"):
            network_id = s.split(":", 1)[1].strip().strip('"')
            continue
        if s.startswith("schema_context:"):
            schema_context = s.split(":", 1)[1].strip().strip('"')
            continue
        if s.startswith("schema_type:"):
            schema_type = s.split(":", 1)[1].strip().strip('"')
            continue
        if section == "profiles" and indent == 2 and s.endswith(":") and not s.startswith("-"):
            current = s[:-1]
            profiles[current] = {}
            continue
        if section == "profiles" and current and indent >= 4 and ":" in s:
            k, v = s.split(":", 1)
            k, v = k.strip(), v.strip()
            if v.startswith("[") or v in ("true", "false") or v == "":
                profiles[current][k] = v
            elif v.startswith('"') and v.endswith('"'):
                profiles[current][k] = v[1:-1]
            else:
                try:
                    profiles[current][k] = int(v) if "." not in v else float(v)
                except ValueError:
                    profiles[current][k] = v
            continue
        if in_golden and s.startswith("- "):
            if golden_item:
                golden.append(golden_item)
            golden_item = {}
            rest = s[2:]
            if ":" in rest:
                k, v = rest.split(":", 1)
                golden_item[k.strip()] = _scalar(v.strip())
            continue
        if in_golden and golden_item is not None and indent >= 4 and ":" in s:
            k, v = s.split(":", 1)
            golden_item[k.strip()] = _scalar(v.strip())
    if golden_item:
        golden.append(golden_item)

    return {
        "profiles": profiles,
        "golden": golden,
        "network_id": network_id,
        "schema_context": schema_context,
        "schema_type": schema_type,
    }


def _scalar(v: str) -> Any:
    if v.startswith('"') and v.endswith('"'):
        return v[1:-1]
    try:
        if "." in v:
            return float(v)
        return int(v)
    except ValueError:
        return v


TEXT_TERMS = [
    "instant coffee powder",
    "organic basmati rice",
    "cold pressed coconut oil",
    "whole wheat atta",
    "fresh farm eggs",
    "almond milk unsweetened",
    "dark chocolate bar",
    "green tea bags",
    "extra virgin olive oil",
    "rolled oats breakfast",
]

CITIES = [
    (12.9716, 77.5946),  # Bengaluru
    (19.0760, 72.8777),  # Mumbai
    (28.6139, 77.2090),  # Delhi
    (13.0827, 80.2707),  # Chennai
    (22.5726, 88.3639),  # Kolkata
]


@dataclass(frozen=True)
class Profile:
    name: str
    resources: int
    catalogs: int
    resources_per_catalog: int
    catalog_id_prefix: str
    es_index: str


def load_profile(name: str) -> tuple[Profile, dict[str, Any]]:
    cfg = _parse_profiles(PROFILES_PATH)
    raw = cfg["profiles"].get(name)
    if not raw:
        known = ", ".join(sorted(cfg["profiles"]))
        raise SystemExit(f"Unknown profile {name!r}. Known: {known}")
    p = Profile(
        name=name,
        resources=int(raw["resources"]),
        catalogs=int(raw["catalogs"]),
        resources_per_catalog=int(raw["resources_per_catalog"]),
        catalog_id_prefix=str(raw["catalog_id_prefix"]),
        es_index=str(raw["es_index"]),
    )
    return p, cfg


def _resource_kind(i: int) -> str:
    r = i % 100
    if r < 40:
        return "T"
    if r < 65:
        return "G"
    if r < 85:
        return "J"
    if r < 95:
        return "GT"
    return "TAIL"


def build_resource(
    *,
    catalog_id: str,
    resource_id: str,
    kind: str,
    idx: int,
    network_id: str,
    schema_context: str,
    schema_type: str,
    golden: bool = False,
    golden_meta: dict[str, Any] | None = None,
) -> dict[str, Any]:
    term = TEXT_TERMS[idx % len(TEXT_TERMS)]
    lat, lon = CITIES[idx % len(CITIES)]
    # jitter ~ few km
    lat += ((idx % 97) - 48) * 0.001
    lon += ((idx % 89) - 44) * 0.001

    if golden and golden_meta:
        if golden_meta.get("path") == "T":
            term = str(golden_meta.get("text", term))
            kind = "T"
        elif golden_meta.get("path") == "G":
            lat = float(golden_meta["lat"])
            lon = float(golden_meta["lon"])
            kind = "G"
        elif golden_meta.get("path") == "J":
            kind = "J"

    name = term if kind in ("T", "GT") else f"sim-resource-{idx}"
    if kind == "J":
        name = "sim-fixture" if not golden else "sim-fixture"

    attrs: dict[str, Any] = {
        "@context": schema_context,
        "@type": schema_type,
        "sku": f"SKU-{idx}",
        "brand": "SimBrand",
    }
    if kind in ("J", "GT") or (golden and golden_meta and golden_meta.get("path") == "J"):
        key = (golden_meta or {}).get("attribute_name", "simLadderMarker")
        val = (golden_meta or {}).get("attribute_value", "corpus-j")
        attrs[str(key)] = val if golden else f"marker-{(idx % 50)}"

    resource: dict[str, Any] = {
        "id": resource_id,
        "descriptor": {
            "name": name,
            "shortDesc": f"{term} — synthetic corpus ladder item {idx}",
            "longDesc": f"Beckn Discovr sim resource for path={kind} idx={idx}",
        },
        "category": {"name": "Grocery", "codeValue": "grocery"},
        "provider": {
            "id": f"prov-{(idx % 100):03d}",
            "descriptor": {"name": f"Sim Provider {(idx % 100)}"},
        },
        "resourceAttributes": attrs,
        "isActive": True,
        "rateable": True,
        "rating": {"ratingValue": 4.0 + (idx % 10) / 10.0, "ratingCount": 10 + (idx % 200)},
    }
    if kind in ("G", "GT", "TAIL") or (golden and golden_meta and golden_meta.get("path") == "G"):
        # Prefer GeoJSON Point (production fixtures) + gps string for extractors
        resource["availableAt"] = [
            {
                "gps": f"{lat:.6f},{lon:.6f}",
                "geo": {"type": "Point", "coordinates": [lon, lat]},
            }
        ]

    offer = {
        "id": f"offer-{resource_id}",
        "descriptor": {"name": f"Offer for {name}"},
        "resourceIds": [resource_id],
        "validity": {
            "startDate": "2025-01-01T00:00:00Z",
            "endDate": "2099-12-31T23:59:59Z",
        },
        "offerAttributes": {
            "@context": schema_context,
            "@type": "Offer",
            "priceCurrency": "INR",
            "price": str(10 + (idx % 90)),
        },
    }

    catalog = {
        "id": catalog_id,
        "descriptor": {"name": f"Sim catalog {catalog_id}"},
        "bppId": "sim-bpp.example.com",
        "bppUri": "https://sim-bpp.example.com/beckn",
        "validity": {
            "startDate": "2025-01-01T00:00:00Z",
            "endDate": "2099-12-31T23:59:59Z",
        },
        "resources": [resource],
        "offers": [offer],
    }

    payload = {
        "context": {
            "version": "2.0.0",
            "action": "catalog/publish",
            "networkId": network_id,
            "messageId": f"00000000-0000-4000-8000-{idx:012d}"[:36],
            "transactionId": f"00000000-0000-4000-8000-{(idx + 1):012d}"[:36],
            "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "bppId": "sim-bpp.example.com",
            "bppUri": "https://sim-bpp.example.com/beckn",
        },
        "message": {"catalogs": [catalog]},
    }

    es_doc = {
        "catalog_id": catalog_id,
        "catalog_name": catalog["descriptor"]["name"],
        "network_id": [network_id],
        "schema_type": schema_type,
        "resource_id": resource_id,
        "resource_name": name,
        "resource_short_desc": resource["descriptor"]["shortDesc"],
        "resource_long_desc": resource["descriptor"]["longDesc"],
        "resource_category_code": "grocery",
        "resource_category_name": "Grocery",
        "resource_provider_id": resource["provider"]["id"],
        "resource_provider_name": resource["provider"]["descriptor"]["name"],
        "resource_attributes": attrs,
        "resource_attributes_type": schema_type,
        "resource_attributes_context": schema_context,
        "resource_is_active": True,
        "offers": [offer],
        "full_text_blob": " ".join(
            [
                name,
                resource["descriptor"]["shortDesc"],
                "Grocery",
                resource["provider"]["descriptor"]["name"],
                term,
            ]
        ),
        "indexed_at": datetime.now(timezone.utc).isoformat(),
    }
    if "availableAt" in resource:
        # Match CatalogDocumentAssembler / GeoShapeExtractor: loc_* + *.geo → geo_shape
        # Discover queries loc_catalogs_resources_availableAt.geo (EsSpatialQueryBuilder).
        es_doc["loc_catalogs_resources_availableAt"] = {
            "geo": {"type": "Point", "coordinates": [lon, lat]},
        }

    pg_item = {
        "id": resource_id,
        "catalog_id": catalog_id,
        "context_url": schema_context,
        "type": schema_type,
        "network_id": [network_id],
        "offer_ids": [offer["id"]],
        "payload": payload["message"],
    }
    pg_loc = None
    if "availableAt" in resource:
        # Wildcard path matches discover ilc.path = ? against request targets
        # (GeometryExtractor stores [*] wildcards, not concrete indexes).
        pg_loc = {
            "item_id": resource_id,
            "catalog_id": catalog_id,
            "path": "$.catalogs[*].resources[*].availableAt[*].geo",
            "lon": lon,
            "lat": lat,
        }

    return {
        "kind": kind,
        "payload": payload,
        "es_id": f"{catalog_id}:{resource_id}",
        "es_doc": es_doc,
        "pg_item": pg_item,
        "pg_loc": pg_loc,
    }


def iter_resources(profile: Profile, cfg: dict[str, Any], limit: int | None = None) -> Iterator[dict[str, Any]]:
    network_id = cfg["network_id"]
    schema_context = cfg["schema_context"]
    schema_type = cfg["schema_type"]
    target = profile.resources if limit is None else min(limit, profile.resources)
    idx = 0

    # Golden first (3 resources in dedicated catalogs)
    for g in cfg.get("golden", []):
        if idx >= target:
            return
        cid = str(g["id"])
        rid = f"{cid}-r0"
        yield build_resource(
            catalog_id=cid,
            resource_id=rid,
            kind=str(g.get("path", "T")),
            idx=idx,
            network_id=network_id,
            schema_context=schema_context,
            schema_type=schema_type,
            golden=True,
            golden_meta=g,
        )
        idx += 1

    # Fill remaining across catalog prefixes
    while idx < target:
        cat_n = (idx // profile.resources_per_catalog) % max(profile.catalogs, 1)
        catalog_id = f"{profile.catalog_id_prefix}-c{cat_n:06d}"
        resource_id = f"r{idx:09d}"
        yield build_resource(
            catalog_id=catalog_id,
            resource_id=resource_id,
            kind=_resource_kind(idx),
            idx=idx,
            network_id=network_id,
            schema_context=schema_context,
            schema_type=schema_type,
        )
        idx += 1


def emit_es_ndjson(profile: Profile, cfg: dict[str, Any], limit: int | None, out) -> int:
    n = 0
    for row in iter_resources(profile, cfg, limit):
        meta = {"index": {"_index": profile.es_index, "_id": row["es_id"]}}
        out.write(json.dumps(meta, separators=(",", ":")) + "\n")
        out.write(json.dumps(row["es_doc"], separators=(",", ":")) + "\n")
        n += 1
    return n


def emit_pg_copy(profile: Profile, cfg: dict[str, Any], limit: int | None, out) -> int:
    """TSV for COPY item (id, catalog_id, context_url, type, network_id, offer_ids, payload)."""
    n = 0
    for row in iter_resources(profile, cfg, limit):
        it = row["pg_item"]
        network = "{" + ",".join(it["network_id"]) + "}"
        offers = "{" + ",".join(it["offer_ids"]) + "}"
        payload = json.dumps(it["payload"], separators=(",", ":"))
        # Escape tabs/newlines for COPY text format
        payload = payload.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        out.write(
            f"{it['id']}\t{it['catalog_id']}\t{it['context_url']}\t{it['type']}\t"
            f"{network}\t{offers}\t{payload}\n"
        )
        n += 1
    return n


def emit_pg_loc_copy(profile: Profile, cfg: dict[str, Any], limit: int | None, out) -> int:
    n = 0
    for row in iter_resources(profile, cfg, limit):
        loc = row["pg_loc"]
        if not loc:
            continue
        # WKT Point for ST_GeomFromText later, or lon/lat columns for loader
        out.write(
            f"{loc['item_id']}\t{loc['catalog_id']}\t{loc['path']}\t"
            f"{loc['lon']}\t{loc['lat']}\n"
        )
        n += 1
    return n


def emit_push_batches(
    profile: Profile,
    cfg: dict[str, Any],
    limit: int | None,
    out_dir: Path,
    batch_size: int,
) -> int:
    out_dir.mkdir(parents=True, exist_ok=True)
    batch: list[dict[str, Any]] = []
    file_n = 0
    total = 0
    network_id = cfg["network_id"]

    def flush():
        nonlocal file_n, batch
        if not batch:
            return
        # Merge resources into fewer catalogs per file for publish realism
        by_cat: dict[str, dict[str, Any]] = {}
        for row in batch:
            cat = row["payload"]["message"]["catalogs"][0]
            cid = cat["id"]
            if cid not in by_cat:
                by_cat[cid] = {
                    "id": cid,
                    "descriptor": cat["descriptor"],
                    "bppId": cat.get("bppId"),
                    "bppUri": cat.get("bppUri"),
                    "validity": cat.get("validity"),
                    "resources": [],
                    "offers": [],
                }
            by_cat[cid]["resources"].extend(cat["resources"])
            by_cat[cid]["offers"].extend(cat["offers"])
        body = {
            "context": {
                "version": "2.0.0",
                "action": "catalog/publish",
                "networkId": network_id,
                "messageId": f"11111111-1111-4111-8111-{file_n:012d}"[:36],
                "transactionId": f"22222222-2222-4222-8222-{file_n:012d}"[:36],
                "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "bppId": "sim-bpp.example.com",
                "bppUri": "https://sim-bpp.example.com/beckn",
                "subscriberId": "sim-subscriber.example.com",
            },
            "message": {"catalogs": list(by_cat.values())},
        }
        path = out_dir / f"push-{file_n:05d}.json"
        path.write_text(json.dumps(body), encoding="utf-8")
        file_n += 1
        batch = []

    for row in iter_resources(profile, cfg, limit):
        batch.append(row)
        total += 1
        if len(batch) >= batch_size:
            flush()
    flush()
    return total


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--profile", required=True, help="local-smoke|1M|10M|50M|100M")
    ap.add_argument(
        "--format",
        choices=["summary", "es-ndjson", "pg-copy", "pg-loc-copy", "push-batch", "sample-json"],
        default="summary",
    )
    ap.add_argument("--limit", type=int, default=None, help="Cap resources (smoke / partial)")
    ap.add_argument("--out-dir", type=Path, default=None)
    ap.add_argument("--batch-size", type=int, default=100, help="Resources per push file")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    random.seed(args.seed)
    profile, cfg = load_profile(args.profile)
    limit = args.limit

    if args.format == "summary":
        est_es_gb = (profile.resources * 8 * 1024) / (1024**3)  # ~8 KiB planning blob
        print(
            json.dumps(
                {
                    "profile": profile.name,
                    "resources": profile.resources,
                    "catalogs": profile.catalogs,
                    "resources_per_catalog": profile.resources_per_catalog,
                    "catalog_id_prefix": profile.catalog_id_prefix,
                    "es_index": profile.es_index,
                    "limit": limit,
                    "approx_es_primary_gb_planning": round(est_es_gb, 2),
                    "golden": cfg.get("golden", []),
                    "effective_resources": profile.resources if limit is None else min(limit, profile.resources),
                },
                indent=2,
            )
        )
        return 0

    if args.format == "sample-json":
        row = next(iter_resources(profile, cfg, limit=1))
        print(json.dumps(row["payload"], indent=2))
        return 0

    if args.format == "es-ndjson":
        n = emit_es_ndjson(profile, cfg, limit, sys.stdout)
        print(f"# emitted_es_docs={n}", file=sys.stderr)
        return 0

    if args.format == "pg-copy":
        n = emit_pg_copy(profile, cfg, limit, sys.stdout)
        print(f"# emitted_pg_rows={n}", file=sys.stderr)
        return 0

    if args.format == "pg-loc-copy":
        n = emit_pg_loc_copy(profile, cfg, limit, sys.stdout)
        print(f"# emitted_pg_loc_rows={n}", file=sys.stderr)
        return 0

    if args.format == "push-batch":
        out_dir = args.out_dir or Path(f"/tmp/corpus-push-{profile.name}")
        n = emit_push_batches(profile, cfg, limit, out_dir, args.batch_size)
        print(json.dumps({"profile": profile.name, "resources": n, "out_dir": str(out_dir)}))
        return 0

    return 2


if __name__ == "__main__":
    raise SystemExit(main())

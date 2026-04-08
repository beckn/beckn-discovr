# Beckn Fabric AI — Product Vision

An AI-native intelligence layer for the Beckn ecosystem that makes every interaction — publishing, discovering, transacting, and governing — smarter, faster, and more trustworthy.

---

## The Opportunity

Beckn is an open commerce protocol. The fabric (CATALG + DISCOVR + transaction services) provides the infrastructure. But infrastructure alone doesn't win markets — **intelligence on top of infrastructure** does.

Every participant in the Beckn ecosystem has an unmet need:

| Participant | Pain today | What AI solves |
|-------------|-----------|---------------|
| **Consumers (BAPs)** | Search is keyword-based, results are unranked, no personalization | Conversational discovery, smart ranking, context-aware results |
| **Providers (BPPs)** | Onboarding is manual, catalog quality is inconsistent, no visibility into demand | Auto-enrichment, quality scoring, demand insights |
| **Network Facilitators** | Blind to coverage gaps, provider health, fraud | Network intelligence, trust scoring, anomaly detection |
| **Developers** | Building search, recommendations, and compliance from scratch | Pre-built AI APIs for discovery, matching, and validation |

---

## The Product: Beckn Fabric AI

A unified AI platform with four pillars, each solving a critical problem in the Beckn lifecycle:

```
                        Beckn Fabric AI
                              |
        ┌─────────────┬───────┴───────┬─────────────┐
        |             |               |             |
   AI Discovery   Catalog AI    Network AI    Trust AI
   (Find)         (Publish)     (Govern)      (Verify)
```

---

## Pillar 1: AI Discovery

**Who it's for:** BAPs, consumer apps, voice assistants, chatbots

**What it does:** Transforms how consumers find resources across Beckn networks. Instead of keyword search, users express intent naturally — AI understands, decomposes, searches, ranks, and responds.

### Capabilities

**Conversational Search**
- "Find me strong Assam tea under 300 that delivers to HSR Layout today"
- AI decomposes into: text ("Assam tea") + attribute (price < 300) + spatial (HSR Layout) + fulfillment (same-day)
- Multi-turn: "Show me organic options" → refines without losing context

**Multi-Domain Discovery**
- Single query spans retail, mobility, healthcare, energy
- "I'm visiting Bengaluru tomorrow — find me a hotel near MG Road, EV charging nearby, and a good South Indian restaurant for dinner"
- Returns unified results across three domains

**Voice & Chat Ready**
- Speech → intent extraction → Beckn discover → natural language response
- Pluggable into WhatsApp, Alexa, Google Assistant, custom apps

**Smart Ranking**
- Multiple providers offer the same resource → AI ranks by relevance, price, trust score, delivery speed, user history
- Explains why: "Recommended because it's closest to you and has 4.5 rating"

**Personalization**
- Learns user preferences over time
- Time-of-day, location, weather, past transactions influence results
- Multi-language: search in Tamil, results from English catalogs

### What exists today
- NLWeb integration, OpenAI embeddings, GPT-4o query enricher, keyword + semantic + spatial search
- **Gap:** Conversational multi-turn, multi-domain routing, ranking, personalization

---

## Pillar 2: Catalog AI

**Who it's for:** BPPs, providers, catalog managers

**What it does:** Makes catalog publishing effortless and high-quality. Providers go from raw product data to Beckn-compliant, enriched, discoverable catalogs in minutes — not weeks.

### Capabilities

**One-Click Onboarding**
- Import from any source: CSV, JSON, existing marketplace API (Amazon, Flipkart, Shopify)
- AI auto-maps fields to Beckn schema (Resource, Offer, Attributes)
- Handles domain-specific mapping: "SKU → resource.id, MRP → offerAttributes.priceSpecification.originalPrice"

**Auto-Enrichment**
- Generate missing descriptions from product attributes
- Auto-categorize resources into schema types
- Suggest `resourceAttributes` fields based on domain (GroceryItem → weight, brand, manufacturer)
- Generate thumbnail images from product descriptions (future)

**Quality Scoring**
- Rate each catalog on completeness, accuracy, freshness
- "Your catalog scores 72/100 — missing: shortDesc on 5 resources, no geo on 3 providers"
- Actionable fix suggestions

**Schema Compliance**
- Validate beyond JSON schema — semantic validation
- "Your 'weight' field says '200' — should it be '200g'?"
- Auto-fix common issues

**Catalog Monitoring**
- Alert on stale catalogs (not updated in 30 days)
- Detect price anomalies (sudden 90% price drop — fraud or error?)
- Version history with diff: "What changed between version 14 and 15?"

### What exists today
- Schema validation (AJV + NetworkNT), MERGE mode, git versioning
- **Gap:** Auto-mapping, enrichment, quality scoring, monitoring dashboard

---

## Pillar 3: Network AI

**Who it's for:** Network facilitators, governance bodies, platform operators

**What it does:** Gives network operators real-time visibility into the health, coverage, and growth of their Beckn network. AI turns raw data into actionable insights.

### Capabilities

**Coverage Intelligence**
- "EV charging network has 45 providers in South Bengaluru, 0 in North Bengaluru"
- "5,200 monthly searches for 'organic vegetables' but only 3 providers in the network"
- Gap analysis → targeted provider recruitment

**Supply-Demand Gap Analysis**
- Compare what consumers search for (discover queries) against what providers have published (catalog supply)
- "5,200 monthly searches for 'organic vegetables' in North Bengaluru — only 3 providers serve that area, 68% of searches return zero results"
- "EV charging searches peak at 6 PM weekdays in Koramangala — current provider coverage handles only 70%"
- Actionable: tells facilitators where to recruit providers, tells providers where to expand

**Network Health Monitoring**
- Provider uptime and responsiveness scoring
- Stale catalog detection across the network
- Subscription delivery success rates
- Alert: "Provider X hasn't responded to 15 discover callbacks in 24 hours"

**Growth Analytics**
- Track network growth: providers onboarded, catalogs published, subscriptions active, queries served
- Cohort analysis: "Providers onboarded in Q1 publish 3x more catalogs than Q4 cohort"
- Benchmark against other networks

### What exists today
- Structured logging, metrics (Micrometer), catalog_index table
- **Gap:** Dashboard, prediction models, gap analysis, alerting

---

## Pillar 4: Trust AI

**Who it's for:** Everyone — consumers, providers, facilitators

**What it does:** Builds trust in the open network by detecting fraud, scoring credibility, and verifying authenticity. Trust is the moat in an open protocol.

### Capabilities

**Provider Trust Scoring**
- Composite score based on: catalog quality, fulfillment rate, response time, complaint ratio, transaction history
- Updated in real-time
- Visible to consumers: "This provider has a 94% trust score"

**Fraud Detection**
- Anomalous catalog publishes (sudden 1000 resources from new provider)
- Fake ratings detection (pattern analysis)
- Price manipulation alerts (coordinated price changes across providers)
- Duplicate listing detection across providers

**Catalog Authenticity**
- Verify product claims against manufacturer data (when master catalogs exist)
- Flag mismatched descriptions, ratings, or attributes
- "This provider claims 'organic' but manufacturer data doesn't confirm it"

**Transaction Integrity**
- Monitor transaction completion rates per provider
- Detect abandon patterns that indicate bad-faith providers
- Automated dispute evidence collection

### What exists today
- Beckn HTTP signatures, callback URL validation (SSRF protection)
- **Gap:** Scoring engine, fraud detection, authenticity verification

---

## How It All Connects

```
Provider publishes catalog
        |
   [Catalog AI] ──→ Auto-enrich, validate, score quality
        |
   Catalog Store (CATALG)
        |
   [Network AI] ──→ Coverage analysis, demand prediction
        |
   [Trust AI] ──→ Provider scoring, fraud detection
        |
Consumer searches
        |
   [AI Discovery] ──→ Intent understanding, smart ranking, personalization
        |
   Discovery Service (DISCOVR)
        |
   Results ranked by relevance + trust + context
        |
Consumer transacts
        |
   [Trust AI] ──→ Transaction monitoring, dispute resolution
```

Every catalog published gets smarter (Catalog AI). Every search gets more relevant (AI Discovery). Every provider gets a trust score (Trust AI). Every network facilitator sees the full picture (Network AI).

---

## Build Roadmap

### Phase 1: AI Discovery (Q2 2026)
- Package existing NLWeb + embeddings + query enricher into a product API
- Add multi-turn conversation support
- Add result ranking (price + rating + trust + distance)
- **Deliverable:** AI Discovery API + SDK for BAPs

### Phase 2: Catalog AI (Q3 2026)
- Build auto-mapping engine (CSV/JSON → Beckn schema)
- Build quality scoring pipeline
- Add auto-enrichment (description generation, categorization)
- **Deliverable:** Catalog AI dashboard for BPPs

### Phase 3: Network AI (Q4 2026)
- Build analytics pipeline on catalog + subscription + discover query data
- Build coverage intelligence and supply-demand gap analysis
- Track search misses (zero-result queries) by domain, location, and schema type
- **Deliverable:** Network Intelligence dashboard for facilitators

### Phase 4: Trust AI (Q1 2027)
- Build provider trust scoring engine
- Add fraud detection pipeline
- Add catalog authenticity verification
- **Deliverable:** Trust Score API + fraud alerts

---

## Monetization

| Product | Model | Target |
|---------|-------|--------|
| AI Discovery | Per-query or monthly subscription | BAPs, consumer apps |
| Catalog AI | SaaS subscription (per provider) | BPPs, catalog managers |
| Network AI | SaaS subscription (per network) | Network facilitators |
| Trust AI | Per-verification or subscription | All participants |

---

## Competitive Advantage

1. **Protocol-native** — built on Beckn, not bolted on. Understands resources, offers, catalogs, providers natively.
2. **Cross-domain** — one AI layer spans retail, mobility, healthcare, energy. No one else does this.
3. **Open network intelligence** — trust and quality scores are shared across the network, not siloed per app.
4. **Already 80% there on Discovery** — NLWeb, embeddings, semantic search exist. First product ships fast.

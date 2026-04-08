# Beckn Fabric AI — Products & Use Cases

An AI-native intelligence layer for the Beckn ecosystem. Four products, each solving a critical problem in the Beckn lifecycle — from publishing to discovering to transacting to governing.

---

## Product Architecture

```
┌──────────────────────────────────────────────────────────┐
│                     Beckn Fabric AI                      │
├───────────────┬────────────────┬──────────────────────────┤
│ AI Discovery  │ Catalog AI     │ Network Intelligence     │
│ (for BAPs)    │ Studio         │ (for Facilitators)       │
│               │ (for BPPs)     │                          │
├───────────────┼────────────────┼──────────────────────────┤
│ Multi-domain  │ One-click      │ Trust scores             │
│ Multi-lang    │ onboarding     │ Fraud detection          │
│ Ranking       │ Auto-enrich    │ Demand prediction        │
│ Personalize   │ Quality score  │ Coverage gaps            │
│ Recommend     │ Content gen    │ Provider health          │
├───────────────┴────────────────┴──────────────────────────┤
│          Transaction Agent (future — Phase 4)             │
└──────────────────────────────────────────────────────────┘
```

---

## Build Roadmap

| Phase | Product | Timeline | Why This Order |
|-------|---------|----------|----------------|
| Phase 1 | AI Discovery API | Q2 2026 | 80% built, largest buyer pool, usage-based revenue |
| Phase 2 | Catalog AI Studio | Q3 2026 | Feeds supply into discovery. More catalogs = better search = more BAPs |
| Phase 3 | Network Intelligence | Q4 2026 | Needs data from Phase 1+2 to be useful. Higher price, fewer buyers |
| Phase 4 | Transaction Agent | Q1 2027 | Needs full Beckn transaction stack. The crown jewel once everything else works |

---

## Monetization

| Product | Model | Target |
|---------|-------|--------|
| AI Discovery API | Per-query or monthly subscription | BAPs, consumer apps |
| Catalog AI Studio | SaaS subscription (per provider) | BPPs, catalog managers |
| Network Intelligence | SaaS subscription (per network) | Network facilitators |
| Transaction Agent | Per-transaction fee or subscription | Consumer apps |

---

# Product 1: AI Discovery API

**Buyers:** BAPs, consumer apps, voice assistants, chatbots

**What it does:** Transforms how consumers find resources across Beckn networks. Users express intent naturally — AI understands, decomposes, searches, ranks, and responds.

**What exists today:** NLWeb integration, OpenAI embeddings, GPT-4o query enricher, keyword + semantic + spatial search.

**Gap:** Conversational multi-turn, multi-domain routing, ranking, personalization, recommendations.

---

## Feature 1.1: Multi-Domain Discovery

Single query spans retail, mobility, healthcare, energy — returns unified results across domains.

### Use Case: Travel Planning

**Actor:** Consumer

**Scenario:**
> "I'm visiting Bengaluru tomorrow — need a hotel near MG Road, EV charging nearby, and a South Indian restaurant for dinner"

**AI Action:**
Decomposes into 3 domain queries:
1. Hospitality: hotel + location(MG Road) + date(tomorrow)
2. Energy: EV charging + location(near MG Road)
3. Food: restaurant + cuisine(South Indian) + meal(dinner)

Searches across all 3 domains in parallel.

**Result:**
| Domain | Result | Details |
|--------|--------|---------|
| Hotel | The Paul, MG Road | Rs.3,200/night, 4.3 stars, 200m from MG Road |
| EV Charging | Ather Grid Station | 0.5km from hotel, DC fast charge, Rs.15/kWh |
| Restaurant | Vidyarthi Bhavan | 1.2km, 4.7 stars, closes 9 PM |

---

### Use Case: Healthcare + Mobility

**Actor:** Consumer

**Scenario:**
> "Find me a dentist in Koramangala available this Saturday, and a cab to get there from HSR Layout"

**AI Action:**
- Healthcare query: dentist + location(Koramangala) + availability(Saturday)
- Mobility query: cab + from(HSR Layout) + to(matched dentist location)

**Result:**
| Domain | Result | Details |
|--------|--------|---------|
| Dentist | Dr. Meera's Dental Clinic | Sat 10 AM-2 PM, Rs.500 consultation |
| Cab | Namma Yatri | HSR to Koramangala, ~Rs.120, 15 min |

---

## Feature 1.2: Multi-Language Search

Search in any language, find results from English catalogs, get responses in the user's language.

### Use Case: Regional Language Shopping

**Actor:** Consumer (Tamil speaker)

**Scenario:**
> User searches in Tamil: "என் அருகில் 300 ரூபாய்க்கு கீழ் அரிசி" (Rice under 300 near me)

**AI Action:**
Detects Tamil, translates to intent (rice + price < 300 + user location), searches English catalogs.

**Result (returned in Tamil):**
- Ponni Rice 5kg — Rs.280, 2.1 km away
- Surat Basmati 1kg — Rs.190, 3.4 km away

---

### Use Case: Voice in Hindi, Catalog in English

**Actor:** Consumer (Hindi speaker)

**Scenario:**
> User speaks in Hindi: "Bhai, mere paas mein koi accha gym hai kya monthly 2000 ke andar?"

**AI Action:**
Speech-to-text, extracts intent (gym + nearby + price < 2000/month), searches English fitness catalogs.

**Result (in Hindi):**
> "Haan, 3 gyms mile — sabse paas 'FitZone' hai, 800m, Rs.1,500/month, 4.2 rating"

---

## Feature 1.3: Smart Ranking

When multiple providers offer the same resource, AI ranks by relevance, price, trust score, delivery speed — and explains why.

### Use Case: Same Product, Multiple Providers

**Actor:** Consumer

**Scenario:**
> User searches: "Bru Coffee 200g" — 10 providers have it.

**AI Action:**
Ranks by composite score: distance + delivery speed + price + trust score.

**Result:**

| Rank | Provider | Price | Distance | Delivery | Trust | Why |
|------|----------|-------|----------|----------|-------|-----|
| #1 | QuickMart | Rs.185 | 1.2km | 30 min | 96% | Best balance of price, speed, and reliability |
| #2 | MegaStore | Rs.170 | 4.5km | 2 hrs | 91% | Cheapest but farther |
| #3 | FreshBasket | Rs.195 | 0.8km | 20 min | 72% | Closest but low trust + higher price |

---

### Use Case: Context-Aware Late Night Ranking

**Actor:** Consumer

**Scenario:**
> Same query "Bru Coffee 200g" but at 11 PM.

**AI Action:**
Re-weights ranking — availability and speed ranked higher than price at late hours.

**Result:**
> #1 NightOwl Store — Rs.210, 0.5km, open 24/7, 15 min delivery. Availability prioritized over price.

---

## Feature 1.4: Personalization

Learns user preferences over time. Time-of-day, location, weather, and past transactions influence results.

### Use Case: Learning Over Time

**Actor:** Consumer

**Scenario:**
> User picks "Nandini Toned Milk 500ml" three weeks in a row when searching "milk".

**AI Action:**
Tracks selection patterns, builds brand and product preference profile.

**Result:**
- Week 3: Nandini Toned Milk auto-ranked #1 for "milk" searches
- Week 5: Nandini brand boosted for "curd" search too (learned brand preference)

---

### Use Case: Time + Location + Weather

**Actor:** Consumer

**Scenario:**
> Same user searches "food" at different times and conditions.

**AI Action:**
Adjusts ranking based on context signals.

**Result:**

| Context | Ranking Behavior |
|---------|-----------------|
| Monday 7 AM | Breakfast items ranked higher (idli, dosa, poha). Quick delivery prioritized |
| Saturday 8 PM | Dinner/restaurant options ranked higher. Rating and ambience weighted more |
| Rainy day | Delivery services boosted over dine-in. Hot soup and chai nudged up |

---

## Feature 1.5: Cross-Domain Recommendations

AI detects patterns across domains and proactively recommends related resources.

### Use Case: Hotel Booking Triggers Related Domains

**Actor:** Consumer

**Scenario:**
> User books a hotel in Mysuru for the weekend.

**AI Action:**
Detects travel intent, cross-references mobility, food, and energy domains for the destination.

**Result (unprompted recommendations):**
> Based on your Mysuru trip:
> - Mysuru day tour cab — Rs.2,500, covers Palace + Zoo + Brindavan Gardens
> - Top rated Mysuru restaurants near your hotel
> - EV charging stations on Bengaluru-Mysuru highway (if user has EV history)

---

### Use Case: Grocery Replenishment Pattern

**Actor:** Consumer

**Scenario:**
> User buys baby formula every 2 weeks. Last ordered 12 days ago.

**AI Action:**
Tracks purchase frequency, predicts replenishment timing, identifies complementary products.

**Result:**
> "Running low on baby formula? Last ordered 12 days ago."
> - Shows best current price across providers
> - Also suggests: "Baby diapers — 3 providers have offers this week"

---

# Product 2: Catalog AI Studio

**Buyers:** BPPs, providers, catalog managers

**What it does:** Makes catalog publishing effortless and high-quality. Providers go from raw product data to Beckn-compliant, enriched, discoverable catalogs in minutes.

**What exists today:** Schema validation (AJV + NetworkNT), MERGE mode, git versioning.

**Gap:** Auto-mapping, enrichment, quality scoring, content generation, monitoring dashboard.

---

## Feature 2.1: One-Click Onboarding

Import from any source — AI auto-maps fields to Beckn schema, validates, and publishes.

### Use Case: Kirana Store Excel Upload

**Actor:** Small store owner (BPP)

**Scenario:**
> Store owner uploads an Excel sheet:
>
> | Product Name | Price | Weight | Brand |
> |-------------|-------|--------|-------|
> | Toor Dal | 180 | 1kg | Tata |
> | Basmati Rice | 450 | 5kg | India Gate |
> | Coconut Oil | 220 | 1L | Parachute |

**AI Action:**
Auto-maps columns to Beckn schema:
- "Product Name" → `resource.descriptor.name`
- "Price" → `offerAttributes.priceSpecification.price` (currency: INR)
- "Weight" → `resourceAttributes.weight`
- "Brand" → `resourceAttributes.brand`

**Result:**
> Mapped 3 resources, 3 offers. Schema: GroceryItem.
> Missing: shortDesc, images, category. Quality: 62/100.
>
> Options: [Auto-fix suggestions] [Publish anyway] [Let AI enrich first]

---

### Use Case: Shopify Import

**Actor:** Online store owner (BPP)

**Scenario:**
> Provider connects their Shopify store with 500 products.

**AI Action:**
Pulls products via Shopify API, maps fields:
- SKU → `resource.id`
- title → `descriptor.name`
- body_html → `descriptor.longDesc` (HTML stripped)
- variants[].price → offer per variant
- images[0] → `descriptor.images[0]`
- product_type → `category`

**Result:**
> 500 resources published in 5 minutes. 12 had missing descriptions — AI generated them.
> Catalog quality: 84/100.

---

## Feature 2.2: Auto-Enrichment & Content Generation

AI generates missing descriptions, suggests categories, and creates marketing content from existing attributes.

### Use Case: Generate Missing Descriptions

**Actor:** BPP

**Scenario:**
> Provider published "Organic Honey" with attributes (weight: 500g, type: multiflora, origin: Coorg) but no description.

**AI Action:**
LLM generates descriptions from available attributes and domain knowledge.

**Result:**
- **shortDesc:** "Pure multiflora organic honey from Coorg, 500g"
- **longDesc:** "Sourced from the Western Ghats of Coorg, this 500g multiflora organic honey is raw, unprocessed, and naturally flavored by diverse wildflower nectar."

Provider reviews, approves, catalog updated.

---

### Use Case: Automatic Category Suggestion

**Actor:** BPP

**Scenario:**
> Provider publishes 200 resources with no categories assigned.

**AI Action:**
Analyzes resource attributes and names, classifies into domain-specific category hierarchy, flags potential mismatches.

**Result:**
- 45 resources → "Grocery > Staples > Rice & Pulses"
- 30 resources → "Grocery > Beverages > Tea & Coffee"
- 15 resources → "Grocery > Snacks > Namkeen"
- 10 resources → "Grocery > Personal Care > Hair Oil" (flagged: "These seem misplaced in a grocery store — verify?")

Provider bulk-approves with 2 corrections.

---

### Use Case: Marketing Content for Offers

**Actor:** BPP

**Scenario:**
> Provider creates an offer: "Buy 2 Get 1 Free on Tata Tea Gold 250g"

**AI Action:**
Generates offer description copy and analyzes purchase patterns for bundle suggestions.

**Result:**
- **Offer copy:** "Stock up and save! Get 3 packs of Tata Tea Gold 250g for the price of 2. Limited time."
- **Bundle suggestion:** "Consider bundling with Parle-G biscuits — 73% of chai buyers also buy biscuits in your area"

---

## Feature 2.3: Schema Compliance Scoring

Rate each catalog on completeness, accuracy, freshness — with actionable fix suggestions.

### Use Case: Quality Dashboard

**Actor:** BPP

**Scenario:**
> Provider "FreshMart" has 150 published resources.

**AI Action:**
Scores catalog across 4 dimensions: schema compliance, completeness, freshness, accuracy. Identifies specific issues.

**Result:**

| Dimension | Score | Issues |
|-----------|-------|--------|
| Schema compliance | 95/100 | 2 resources have invalid price format |
| Completeness | 60/100 | 45 missing shortDesc, 80 missing images, 12 missing category |
| Freshness | 90/100 | Last updated 2 days ago |
| Accuracy | 40/100 | 3 resources missing weight unit ("200" should be "200g"), 1 price likely wrong (Rs.0.50 should be Rs.50) |

**Overall Score: 71/100**

Options: [Auto-fix 48 issues] [Download report] [Ignore]

---

## Feature 2.4: Schema Generation for New Domains

Auto-generate Beckn-compliant schemas from sample data when launching new domains.

### Use Case: New Pet Services Domain

**Actor:** Network Facilitator

**Scenario:**
> Facilitator launching a "Pet Services" domain provides sample data:
> ```json
> { "service": "Dog grooming", "duration": "1hr", "pet_type": "dog", "price": 800, "home_visit": true }
> ```

**AI Action:**
Analyzes sample data, infers field types, enums, and constraints. Generates Beckn-compliant schema.

**Result:**
```yaml
PetService:
  type: object
  properties:
    @context: { const: "https://schema.beckn.org/pet-services/v1" }
    @type: { const: "PetService" }
    serviceType: { enum: [grooming, boarding, training, veterinary] }
    duration: { type: string, pattern: "^\\d+hr$" }
    petType: { enum: [dog, cat, bird, other] }
    homeVisit: { type: boolean }
```

> "Generated schema with 6 fields. Review and publish to beckn-spec?"

---

# Product 3: Network Intelligence

**Buyers:** Network facilitators, governance bodies, platform operators

**What it does:** Gives network operators real-time visibility into health, coverage, trust, and growth. AI turns raw data into actionable insights.

**What exists today:** Structured logging, metrics (Micrometer), catalog_index table.

**Gap:** Dashboard, trust scoring, fraud detection, prediction models, gap analysis, alerting.

---

## Feature 3.1: Trust & Provider Scoring

Composite credibility score for every provider. Visible to consumers for ranking, facilitators for governance.

### Use Case: Established Provider — High Trust

**Actor:** Network Facilitator / Consumer

**Scenario:**
> Provider "QuickMart" has been active for 6 months with extensive history.

**AI Action:**
Computes composite score from multiple signals.

**Result:**

| Signal | Score | Detail |
|--------|-------|--------|
| Catalog quality | 88/100 | Good descriptions, few missing fields |
| Response rate | 99/100 | 1,847 of 1,850 discover callbacks answered |
| Response time | 95/100 | Avg 120ms callback response |
| Freshness | 92/100 | Catalog updated 3 days ago |
| Transaction history | 96/100 | 4.6 stars avg from 2,300 transactions |
| Fraud flags | 0 | Clean record |

**Trust Score: 94/100** — Badge: "Trusted Provider"

---

### Use Case: New Suspicious Provider — Low Trust

**Actor:** Network Facilitator

**Scenario:**
> Provider "SuperDeals99" — account age: 2 days, published 2,000 resources in first hour, identical descriptions, prices 40-60% below market.

**AI Action:**
Flags multiple risk signals.

**Result:**

| Flag | Detail |
|------|--------|
| Account age | 2 days |
| Volume anomaly | 2,000 resources published in 1 hour |
| Content quality | 85% of descriptions are identical with only product name changed |
| Pricing anomaly | All prices 40-60% below market average |
| Verification | Provider address doesn't match any registered business |

**Trust Score: 31/100** — HIGH RISK: Suspected fake catalog.

Options: [Quarantine provider] [Request verification] [Allow with warning]

---

## Feature 3.2: Fraud Detection

Detect price manipulation, duplicate listings, and anomalous behavior across the network.

### Use Case: Coordinated Price Manipulation

**Actor:** Network Facilitator

**Scenario:**
> 3 providers in the Electronics domain simultaneously change prices on 15 identical resources within a 2-hour window.

**AI Action:**
Detects coordinated price change pattern. Checks for market events (sales, shortages). Computes collusion likelihood.

**Result:**

> **PRICE ANOMALY DETECTED**
>
> | Provider | Product | Old Price | New Price | Change |
> |----------|---------|-----------|-----------|--------|
> | Provider A | Samsung Galaxy Buds2 | Rs.8,999 | Rs.12,999 | +44% |
> | Provider B | Samsung Galaxy Buds2 | Rs.9,200 | Rs.12,800 | +39% |
> | Provider C | Samsung Galaxy Buds2 | Rs.8,800 | Rs.13,100 | +49% |
>
> Pattern: Coordinated price increase within 2-hour window.
> No market event (sale, shortage) detected.
> Likelihood of price collusion: **HIGH**
>
> Options: [Investigate] [Flag providers] [Dismiss]

---

### Use Case: Duplicate Listing Detection

**Actor:** Network Facilitator

**Scenario:**
> Provider "MegaStore" catalog appears highly similar to "FreshMart".

**AI Action:**
Computes catalog similarity via description embeddings and image hashing. Identifies pricing pattern. Checks provider addresses.

**Result:**

> **DUPLICATE CATALOG DETECTED**
>
> | Signal | Detail |
> |--------|--------|
> | Resource overlap | 142 of 160 resources have identical descriptions |
> | Image match | Same images (hash match) |
> | Pricing pattern | Prices exactly 5% higher across all items |
> | Location | Provider addresses are 200m apart |
>
> Possible scenarios:
> 1. Same owner, two storefronts (legitimate)
> 2. Catalog copied from FreshMart (violation)
>
> Options: [Contact both providers] [Flag for review]

---

## Feature 3.3: Demand Prediction & Coverage Gaps

Forecast demand by domain, location, and time. Identify where supply doesn't meet demand.

### Use Case: Supply-Demand Gap Analysis

**Actor:** Network Facilitator

**Scenario:**
> Monthly network analysis — identifying search queries that return zero results.

**AI Action:**
Aggregates zero-result discover queries by domain, location, and volume. Estimates revenue opportunity.

**Result:**

> **ZERO-RESULT QUERIES — Top 5 (March 2026)**
>
> | Rank | Query | Location | Monthly Searches | Providers | Gap |
> |------|-------|----------|-----------------|-----------|-----|
> | 1 | Organic vegetables | North Bengaluru | 5,200 | 0 | 100% |
> | 2 | EV charging | Whitefield | 3,100 | 1 (often offline) | ~85% |
> | 3 | Pet grooming home visit | Indiranagar | 890 | 0 | 100% |
> | 4 | Wheelchair accessible cab | All Bengaluru | 450 | 0 | 100% |
> | 5 | Late night pharmacy | After 11 PM | 2,100 | 2 | ~60% |
>
> **Revenue opportunity:** Recruiting 3 organic vegetable providers in North Bengaluru could capture ~Rs.15L/month in transaction value.
>
> Options: [Export for provider recruitment] [Set up alerts]

---

### Use Case: Seasonal Demand Forecast

**Actor:** Network Facilitator

**Scenario:**
> Approaching Ugadi festival (April 6) and summer season.

**AI Action:**
Analyzes historical search patterns, predicts demand spikes by category, evaluates current provider capacity.

**Result:**

> **DEMAND FORECAST — April 2026**
>
> | Event | Category | Expected Spike | Current Capacity | Action Needed |
> |-------|----------|---------------|-----------------|---------------|
> | Ugadi (Apr 6) | Puja items | +300% | Sufficient | None |
> | Ugadi (Apr 6) | Sweets | +180% | Sufficient | None |
> | Ugadi (Apr 6) | Flowers | +250% | INSUFFICIENT (2 providers) | Recruit 3-5 flower vendors before Apr 1 |
> | Summer | AC servicing | +400% | OK | Monitor |
> | Summer | Water delivery | +200% | GAP beyond Outer Ring Road | Recruit providers |
> | IPL season | Sports bar bookings | +150% | Sufficient | None |

---

### Use Case: Real-Time Capacity Monitoring

**Actor:** Network Facilitator

**Scenario:**
> Thursday 5:45 PM — EV charging searches spiking in Koramangala.

**AI Action:**
Monitors real-time search volume, tracks station availability, predicts demand at peak time.

**Result:**

> **LIVE ALERT — EV Charging, Koramangala**
>
> | Metric | Value |
> |--------|-------|
> | Current demand | 47 active searches in last 15 min |
> | Available stations | 3 of 8 (5 occupied) |
> | Predicted demand at 6:30 PM | 72 searches |
> | Capacity gap | 30% — expect 20+ users unable to find charger |
>
> Suggested actions:
> - Notify nearby charging providers (HSR, BTM) to expect overflow
> - Show users "Available at 7 PM" predictions in search results

---

# Product 4: Transaction Agent (Future)

**Buyers:** Consumer apps, power users

**What it does:** Autonomous AI agents that handle the full Beckn transaction lifecycle — discover, select, init, confirm — on behalf of the user.

**Prerequisites:** Full Beckn transaction stack (select, init, confirm, status), payment integration, error handling.

**Timeline:** Phase 4 (Q1 2027) — build after Discovery, Catalog AI, and Network Intelligence are live.

---

## Feature 4.1: Autonomous Purchase Agent

User gives a natural language instruction, agent handles the entire transaction.

### Use Case: Routine Grocery Purchase

**Actor:** Consumer

**Scenario:**
> "Buy me 2kg Bru Coffee from the cheapest provider near me"

**Agent Workflow:**

| Step | Action | Detail |
|------|--------|--------|
| 1. DISCOVER | Search + rank | "Bru Coffee 2kg" near user. Found 6 providers, Rs.340–Rs.420. Cheapest: ValueMart at Rs.340, 2.1km, trust 88% |
| 2. SELECT | Pick provider | Selects ValueMart. Confirmed: Rs.340 + Rs.30 delivery = Rs.370. Delivery: 45 min |
| 3. CONFIRM | Ask user | "Found Bru Coffee 2kg at ValueMart for Rs.370 (incl delivery), 45 min. Proceed?" — User: "Yes" |
| 4. INIT | Order setup | Provides delivery address |
| 5. CONFIRM | Payment | Confirms via saved UPI |

**Result:**
> "Order confirmed! Bru Coffee 2kg arriving by 7:15 PM. Order ID: BKN-28374. I'll notify you when it's dispatched."

---

### Use Case: Complex Multi-Step — Dinner Party Planning

**Actor:** Consumer

**Scenario:**
> "I'm hosting a dinner party Saturday for 8 people. Budget Rs.5,000. Handle everything."

**Agent Workflow:**

| Step | Action | Detail |
|------|--------|--------|
| 1. Clarify | Ask preferences | "Veg or non-veg? Dietary restrictions?" — User: "Veg, no onion-garlic" |
| 2. Plan | Menu creation | Plans 6 dishes for 8 people (Paneer Butter Masala, Dal Makhani, Jeera Rice, Raita, Gulab Jamun, soft drinks). Estimated: Rs.3,800 |
| 3. Adjust | User feedback | User: "Add starters too" → Adds Samosa (40 pcs) + Dhokla (24 pcs) = Rs.4,600 total. User: "Go ahead" |
| 4. DISCOVER | Multi-provider search | Finds ingredients across 3 providers, optimizes for price + delivery consolidation |
| 5. SELECT + INIT + CONFIRM | Place orders | 2 orders: FreshMart Rs.3,200 (groceries, Friday) + SnackBox Rs.1,400 (ready-made, Saturday morning) |

**Result:**
> "Done! 2 orders placed, total Rs.4,600. Groceries arrive Friday evening, snacks Saturday by 10 AM. Rs.400 under budget."

---

### Use Case: Recurring Auto-Purchase

**Actor:** Consumer

**Scenario:**
> "Every Sunday morning, order 2 packets of A2 milk and today's newspaper. Cheapest available."

**Agent Workflow:**

Creates a recurring task:
- **Every Sunday 6 AM:**
  1. DISCOVER milk + newspaper near user
  2. Pick cheapest provider with Sunday delivery
  3. If price < Rs.150 → auto-confirm
  4. If price > Rs.150 → ask user first
  5. Notify: "Ordered milk + newspaper — arriving by 8 AM, Rs.120"

**Monthly Report:**
> "4 auto-orders this month. Total: Rs.470. Saved Rs.80 vs your previous provider by switching to 'DairyFresh' in week 3."

---

# Summary

| Product | Features | Use Cases | Buyers | Revenue Model |
|---------|----------|-----------|--------|---------------|
| AI Discovery API | Multi-domain, Multi-language, Smart ranking, Personalization, Cross-domain recommendations | 10 | BAPs, consumer apps | Per-query / monthly |
| Catalog AI Studio | One-click onboarding, Auto-enrichment, Content generation, Quality scoring, Schema generation | 7 | BPPs, providers | SaaS per provider |
| Network Intelligence | Trust scoring, Fraud detection, Demand prediction, Coverage gaps, Real-time monitoring | 7 | Network facilitators | SaaS per network |
| Transaction Agent | Autonomous purchase, Multi-step orchestration, Recurring auto-purchase | 3 | Consumer apps | Per-transaction |

**Total: 4 products, 17 features, 27 use cases.**

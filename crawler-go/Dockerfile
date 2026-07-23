# =============================================================================
# Decentralized Catalog Crawler — Go build + run
# Two stages: Go build (static binary, cached dependency layer) → minimal alpine runtime.
# Build context is the crawler-go/ directory.
# =============================================================================
ARG GO_BASE=golang:1.22-alpine
FROM ${GO_BASE} AS builder

WORKDIR /app

# Step 1: dependency layer — cached until go.mod/go.sum change.
COPY go.mod go.sum ./
RUN go mod download

# Step 2: source — only layers below here rebuild on a code change.
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/crawler ./cmd/crawler

# =============================================================================
# Runtime — tiny alpine with root CAs (HTTPS to the bucket, e.g. raw.githubusercontent.com).
# =============================================================================
FROM alpine:3.20

RUN apk add --no-cache ca-certificates \
 && addgroup -S appuser && adduser -S -G appuser appuser
WORKDIR /app

COPY --from=builder /out/crawler /app/crawler
RUN mkdir -p /app/logs && chown -R appuser:appuser /app

USER appuser

# The crawler is a scheduled worker (no web server). All config comes from CRAWLER_* env vars.
ENTRYPOINT ["/app/crawler"]

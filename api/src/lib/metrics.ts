/**
 * Prometheus metrics: request count by method/path/status, request duration histogram,
 * validation and Kafka send failures.
 */

import { Registry, Counter, Histogram } from 'prom-client';

export const register = new Registry();

export const httpRequestsTotal = new Counter({
  name: 'http_requests_total',
  help: 'Total HTTP requests',
  labelNames: ['method', 'path', 'status'],
  registers: [register],
});

export const httpRequestDurationSeconds = new Histogram({
  name: 'http_request_duration_seconds',
  help: 'HTTP request duration in seconds',
  labelNames: ['method', 'path'],
  registers: [register],
});

export const validationFailuresTotal = new Counter({
  name: 'on_discover_validation_failures_total',
  help: 'Total on_discover request validation failures',
  registers: [register],
});

export const kafkaSendFailuresTotal = new Counter({
  name: 'on_discover_kafka_send_failures_total',
  help: 'Total Kafka send failures for on_discover',
  registers: [register],
});

export const kafkaSendSuccessTotal = new Counter({
  name: 'on_discover_kafka_send_success_total',
  help: 'Total successful Kafka sends for on_discover',
  registers: [register],
});

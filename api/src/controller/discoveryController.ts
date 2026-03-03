/**
 * POST /on_discover controller: produce validated body to Kafka and respond.
 * Validation and signature are handled by middleware.
 */

import type { Request, Response } from 'express';
import type { Config } from '../config';
import type { Logger } from 'pino';
import { sendToKafka } from '../lib/kafka';
import {
  httpRequestsTotal,
  httpRequestDurationSeconds,
  kafkaSendFailuresTotal,
  kafkaSendSuccessTotal,
} from '../lib/metrics';

function getTransactionId(body: Record<string, unknown>): string | null {
  const ctx = body?.context as { transaction_id?: string } | undefined;
  return ctx?.transaction_id ?? null;
}

const discoveryController = {
  async onDiscover(req: Request, res: Response): Promise<void> {
    const config = (req.app.locals as { config: Config }).config;
    const logger = (req.app.locals as { logger: Logger }).logger;
    const method = req.method;
    const path = req.path;
    const start = Date.now();

    try {
      const body = req.body as Record<string, unknown>;
      const payload = JSON.stringify(body);
      const transactionId = getTransactionId(body);
      const key = transactionId;

      await sendToKafka(config, payload, key);
      kafkaSendSuccessTotal.inc();
      logger.info({ path, transactionId }, 'Produced to Kafka');

      res.status(202).json({ status: 'accepted' });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unknown error';
      logger.error({ path, err }, 'on_discover error');
      kafkaSendFailuresTotal.inc();
      res.status(503).json({ error: 'Failed to produce message', message });
    } finally {
      const duration = (Date.now() - start) / 1000;
      const status = res.statusCode;
      httpRequestDurationSeconds.observe({ method, path }, duration);
      httpRequestsTotal.inc({ method, path, status: String(status) });
    }
  },
};

export default discoveryController;

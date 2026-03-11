/** POST /on_discover: produce body to Kafka. Validation/signature in middleware. */

import type { Request, Response } from 'express';
import type { Config } from '../config';
import type { Logger } from 'pino';
import { sendToKafka } from '../lib/kafka';
import { httpRequestsTotal, httpRequestDurationSeconds, kafkaSendFailuresTotal, kafkaSendSuccessTotal } from '../lib/metrics';

const getTransactionId = (body: Record<string, unknown>) => (body?.context as { transaction_id?: string } | undefined)?.transaction_id ?? null;

const discoveryController = {
  async onDiscover(req: Request, res: Response): Promise<void> {
    const { config, logger } = req.app.locals as { config: Config; logger: Logger };
    const { method, path } = req;
    const start = Date.now();
    try {
      const body = req.body as Record<string, unknown>;
      const transactionId = getTransactionId(body);
      await sendToKafka(config, JSON.stringify(body), transactionId);
      kafkaSendSuccessTotal.inc();
      logger.info({ path, transactionId }, 'Produced to Kafka');
      res.status(202).json({ status: 'accepted' });
    } catch (err) {
      logger.error({ path, err }, 'on_discover error');
      kafkaSendFailuresTotal.inc();
      res.status(503).json({ error: 'Failed to produce message', message: err instanceof Error ? err.message : 'Unknown error' });
    } finally {
      httpRequestDurationSeconds.observe({ method, path }, (Date.now() - start) / 1000);
      httpRequestsTotal.inc({ method, path, status: String(res.statusCode) });
    }
  },
};

export default discoveryController;

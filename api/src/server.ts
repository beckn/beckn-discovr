/** Entry: Express app, routes, /metrics, Kafka, graceful shutdown. Exports createAppAndKafka for tests. */

import express, { type Express } from 'express';
import { loadConfig } from './config';
import type { Config } from './config';
import { createLogger } from './lib/logger';
import type { Logger } from 'pino';
import { initKafka, disconnectKafka } from './lib/kafka';
import { register } from './lib/metrics';
import routes from './routes/routes';

export async function createAppAndKafka(): Promise<{ app: Express; config: Config; logger: Logger; disconnect: () => Promise<void> }> {
  const config = loadConfig();
  const logger = createLogger(config);
  await initKafka(config);
  logger.info({ topic: config.kafka.topic }, 'Kafka producer connected');
  const app = express();
  app.use(express.json({ limit: '1mb' }));
  app.locals.config = config;
  app.locals.logger = logger;
  app.use(routes);
  app.get('/metrics', async (_req, res) => { res.setHeader('Content-Type', register.contentType); res.end(await register.metrics()); });
  app.get('/health', (_req, res) => res.status(200).json({ status: 'ok' }));
  return { app, config, logger, disconnect: disconnectKafka };
}

async function main(): Promise<void> {
  const { app, config, logger, disconnect } = await createAppAndKafka();
  const server = app.listen(config.port, () => logger.info({ port: config.port }, 'Server listening'));
  const shutdown = async () => { logger.info('Shutting down'); server.close(() => logger.info('HTTP server closed')); await disconnect(); process.exit(0); };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

if (require.main === module) main().catch((err) => { console.error('Startup failed:', err); process.exit(1); });

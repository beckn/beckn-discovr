/**
 * Structured JSON logger (pino). Use for request logs, validation/signature/Kafka results.
 */

import pino from 'pino';
import type { Config } from '../config';

export function createLogger(config: Config): pino.Logger {
  const isDev = config.nodeEnv === 'development';
  return pino({
    level: config.logLevel,
    ...(isDev && {
      transport: {
        target: 'pino-pretty',
        options: { colorize: true },
      },
    }),
  });
}

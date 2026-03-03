/**
 * Application config from environment. Validates required vars on load.
 */

function getEnv(key: string, defaultValue?: string): string {
  const value = process.env[key] ?? defaultValue;
  if (value === undefined || value === '') {
    throw new Error(`Missing required env: ${key}`);
  }
  return value;
}

function getEnvOptional(key: string, defaultValue: string): string {
  return process.env[key] ?? defaultValue;
}

export interface Config {
  port: number;
  kafka: {
    bootstrapServers: string;
    topic: string;
    clientId: string;
  };
  signatureVerificationEnabled: boolean;
  logLevel: string;
  nodeEnv: string;
  schemaPath: string;
}

export function loadConfig(): Config {
  const port = parseInt(getEnvOptional('PORT', '3000'), 10);
  if (Number.isNaN(port) || port < 1 || port > 65535) {
    throw new Error('Invalid PORT');
  }

  const bootstrapServers = getEnv('KAFKA_BOOTSTRAP_SERVERS');
  const topic = getEnv('KAFKA_ON_DISCOVER_TOPIC');
  const clientId = getEnvOptional('KAFKA_CLIENT_ID', 'on-discover-api');

  const sigEnabled = getEnvOptional('SIGNATURE_VERIFICATION_ENABLED', 'false').toLowerCase() === 'true';
  const logLevel = getEnvOptional('LOG_LEVEL', 'info');
  const nodeEnv = getEnvOptional('NODE_ENV', 'development');

  // Schema path: at runtime we run from dist/, so schemas live at repo root of api (parent of dist)
  const schemaPath = getEnvOptional('SCHEMA_PATH', '');

  return {
    port,
    kafka: {
      bootstrapServers,
      topic,
      clientId,
    },
    signatureVerificationEnabled: sigEnabled,
    logLevel,
    nodeEnv,
    schemaPath,
  };
}

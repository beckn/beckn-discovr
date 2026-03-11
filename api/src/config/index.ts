/** Config from env. Validates required vars on load. */

const getEnv = (key: string, defaultVal?: string): string => {
  const v = process.env[key] ?? defaultVal;
  if (v === undefined || v === '') throw new Error(`Missing required env: ${key}`);
  return v;
};
const opt = (key: string, d: string) => process.env[key] ?? d;

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
  const port = parseInt(opt('PORT', '3000'), 10);
  if (Number.isNaN(port) || port < 1 || port > 65535) throw new Error('Invalid PORT');
  return {
    port,
    kafka: {
      bootstrapServers: getEnv('KAFKA_BOOTSTRAP_SERVERS'),
      topic: getEnv('KAFKA_ON_DISCOVER_TOPIC'),
      clientId: opt('KAFKA_CLIENT_ID', 'on-discover-api'),
    },
    signatureVerificationEnabled: opt('SIGNATURE_VERIFICATION_ENABLED', 'false').toLowerCase() === 'true',
    logLevel: opt('LOG_LEVEL', 'info'),
    nodeEnv: opt('NODE_ENV', 'development'),
    schemaPath: opt('SCHEMA_PATH', ''),
  };
}

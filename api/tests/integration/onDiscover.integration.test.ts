/**
 * Integration tests for POST /on_discover using Testcontainers (Kafka).
 * Requires Docker to be running.
 */

import { KafkaContainer, type StartedKafkaContainer } from '@testcontainers/kafka';
import { Kafka } from 'kafkajs';
import request from 'supertest';
import { createAppAndKafka } from '../../src/server';

const KAFKA_PORT = 9093;
const TOPIC = 'test-on-discover';

describe('POST /on_discover integration', () => {
  let kafkaContainer: StartedKafkaContainer;
  let bootstrapServers: string;

  beforeAll(async () => {
    kafkaContainer = await new KafkaContainer('confluentinc/cp-kafka:7.5.0').start();
    const host = kafkaContainer.getHost();
    const port = kafkaContainer.getMappedPort(KAFKA_PORT);
    bootstrapServers = `${host}:${port}`;

    const kafka = new Kafka({ brokers: [bootstrapServers] });
    const admin = kafka.admin();
    await admin.connect();
    await admin.createTopics({ topics: [{ topic: TOPIC, numPartitions: 1 }] });
    await admin.disconnect();
  }, 180000);

  afterAll(async () => {
    await kafkaContainer.stop();
  });

  it('accepts valid DiscoverResponse body and produces to Kafka', async () => {
    const env = {
      ...process.env,
      KAFKA_BOOTSTRAP_SERVERS: bootstrapServers,
      KAFKA_ON_DISCOVER_TOPIC: TOPIC,
      SIGNATURE_VERIFICATION_ENABLED: 'false',
      PORT: '3000',
    };
    const origEnv = process.env;
    process.env = env as NodeJS.ProcessEnv;
    let disconnect: (() => Promise<void>) | null = null;

    try {
      const created = await createAppAndKafka();
      disconnect = created.disconnect;
      const { app } = created;

      const body = {
        context: {
          domain: 'nic2004:60221',
          country: 'IND',
          city: 'std:080',
          action: 'on_discover',
          core_version: '1.2.0',
          bap_id: 'test-bap',
          bap_uri: 'https://test-bap.example.com',
          transaction_id: 'tx-123',
          message_id: 'msg-456',
          timestamp: new Date().toISOString(),
        },
        message: {
          catalogs: [],
        },
      };

      const res = await request(app).post('/on_discover').send(body).expect(202);

      expect(res.body).toEqual({ status: 'accepted' });

      const kafka = new Kafka({ brokers: [bootstrapServers] });
      const consumer = kafka.consumer({ groupId: 'integration-test' });
      await consumer.connect();
      await consumer.subscribe({ topic: TOPIC, fromBeginning: true });

      const messages: unknown[] = [];
      await new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('Timeout waiting for Kafka message')), 10000);
        consumer.run({
          eachMessage: async ({ message }) => {
            const value = message.value?.toString();
            if (value) messages.push(JSON.parse(value));
            clearTimeout(timeout);
            resolve();
          },
        });
      });

      await consumer.disconnect();

      expect(messages).toHaveLength(1);
      expect(messages[0]).toMatchObject({
        context: expect.objectContaining({ transaction_id: 'tx-123' }),
        message: { catalogs: [] },
      });
    } finally {
      process.env = origEnv;
      if (disconnect) await disconnect();
    }
  });

  it('GET /health returns 200', async () => {
    const env = {
      ...process.env,
      KAFKA_BOOTSTRAP_SERVERS: bootstrapServers,
      KAFKA_ON_DISCOVER_TOPIC: TOPIC,
      SIGNATURE_VERIFICATION_ENABLED: 'false',
      PORT: '3000',
    };
    const origEnv = process.env;
    process.env = env as NodeJS.ProcessEnv;
    let disconnect: (() => Promise<void>) | null = null;

    try {
      const { app, disconnect: d } = await createAppAndKafka();
      disconnect = d;
      await request(app).get('/health').expect(200, { status: 'ok' });
    } finally {
      process.env = origEnv;
      if (disconnect) await disconnect();
    }
  });
});

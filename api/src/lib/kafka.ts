/**
 * Kafka producer: connect on init, send on_discover payloads, disconnect on shutdown.
 */

import { Kafka, Producer, ProducerRecord } from 'kafkajs';
import type { Config } from '../config';

let producer: Producer | null = null;

export async function initKafka(config: Config): Promise<void> {
  const kafka = new Kafka({
    clientId: config.kafka.clientId,
    brokers: config.kafka.bootstrapServers.split(',').map((b) => b.trim()),
  });
  producer = kafka.producer();
  await producer.connect();
}

export async function sendToKafka(
  config: Config,
  payload: string,
  key?: string | null
): Promise<void> {
  if (!producer) {
    throw new Error('Kafka producer not initialized');
  }
  const record: ProducerRecord = {
    topic: config.kafka.topic,
    messages: [{ key: key ?? null, value: payload }],
  };
  await producer.send(record);
}

export async function disconnectKafka(): Promise<void> {
  if (producer) {
    await producer.disconnect();
    producer = null;
  }
}

/**
 * Request body schema validation for on_discover (DiscoverResponse shape).
 * Uses Ajv and schemas/on_discover.json.
 */

import type { Request, Response, NextFunction } from 'express';
import Ajv from 'ajv';
import * as fs from 'fs';
import * as path from 'path';
import { validationFailuresTotal } from '../lib/metrics';
import type { Config } from '../config';

let cachedValidate: ((data: unknown) => { valid: boolean; errors?: string[] }) | null = null;

function getSchemaPath(customPath?: string): string {
  if (customPath && fs.existsSync(customPath)) {
    return customPath;
  }
  const defaultPath = path.join(process.cwd(), 'schemas', 'on_discover.json');
  if (fs.existsSync(defaultPath)) {
    return defaultPath;
  }
  throw new Error('Schema file not found: set SCHEMA_PATH or ensure api/schemas/on_discover.json exists');
}

export function validateBody(body: unknown, schemaPath?: string): { valid: boolean; errors?: string[] } {
  if (cachedValidate) {
    return cachedValidate(body);
  }
  const resolved = getSchemaPath(schemaPath);
  const schema = JSON.parse(fs.readFileSync(resolved, 'utf-8'));
  const ajv = new Ajv({ allErrors: true });
  const compile = ajv.compile(schema);
  cachedValidate = (data: unknown) => {
    const valid = compile(data) as boolean;
    const errors = valid ? undefined : (ajv.errors ?? []).map((e) => `${e.instancePath} ${e.message ?? ''}`.trim());
    return { valid, errors };
  };
  return cachedValidate(body);
}

export function validateOnDiscoverRequest(req: Request, res: Response, next: NextFunction): void {
  const config = (req.app.locals as { config?: Config }).config;
  const schemaPath = config?.schemaPath || undefined;

  const body = req.body as unknown;
  if (body === undefined || body === null) {
    validationFailuresTotal.inc();
    res.status(400).json({ error: 'Missing request body' });
    return;
  }

  const validation = validateBody(body, schemaPath);
  if (!validation.valid) {
    validationFailuresTotal.inc();
    res.status(400).json({ error: 'Validation failed', details: validation.errors });
    return;
  }
  next();
}

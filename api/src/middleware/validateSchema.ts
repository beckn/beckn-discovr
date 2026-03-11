/** Body schema validation for on_discover (DiscoverResponse). Uses Ajv + schemas/on_discover.json. */

import type { Request, Response, NextFunction } from 'express';
import Ajv from 'ajv';
import fs from 'fs';
import path from 'path';
import { validationFailuresTotal } from '../lib/metrics';
import type { Config } from '../config';

let cachedValidate: ((data: unknown) => { valid: boolean; errors?: string[] }) | null = null;

function getSchemaPath(custom?: string): string {
  if (custom && fs.existsSync(custom)) return custom;
  const p = path.join(process.cwd(), 'schemas', 'on_discover.json');
  if (fs.existsSync(p)) return p;
  throw new Error('Schema file not found: set SCHEMA_PATH or ensure api/schemas/on_discover.json exists');
}

export function validateBody(body: unknown, schemaPath?: string): { valid: boolean; errors?: string[] } {
  if (cachedValidate) return cachedValidate(body);
  const schema = JSON.parse(fs.readFileSync(getSchemaPath(schemaPath), 'utf-8'));
  const ajv = new Ajv({ allErrors: true });
  const compile = ajv.compile(schema);
  cachedValidate = (data: unknown) => {
    const valid = compile(data) as boolean;
    return { valid, errors: valid ? undefined : (ajv.errors ?? []).map((e) => `${e.instancePath} ${e.message ?? ''}`.trim()) };
  };
  return cachedValidate(body);
}

export function validateOnDiscoverRequest(req: Request, res: Response, next: NextFunction): void {
  const body = req.body as unknown;
  if (body == null) return void (validationFailuresTotal.inc(), res.status(400).json({ error: 'Missing request body' }));
  const v = validateBody(body, (req.app.locals as { config?: Config }).config?.schemaPath || undefined);
  if (!v.valid) return void (validationFailuresTotal.inc(), res.status(400).json({ error: 'Validation failed', details: v.errors }));
  next();
}

/**
 * Signature verification for on_discover requests (configurable).
 * Stub: when enabled, always returns success. Replace with real Beckn HTTP signature
 * verification (e.g. BLAKE-512 + Ed25519) later.
 */

import type { Request, Response, NextFunction } from 'express';
import type { Config } from '../config';

export async function verifySignature(_req: Request): Promise<{ verified: boolean; reason?: string }> {
  // Stub: always succeed. Later: parse Authorization header, resolve key, verify digest.
  return { verified: true };
}

export function requireSignature(req: Request, res: Response, next: NextFunction): void {
  const config = (req.app.locals as { config?: Config }).config;
  if (!config?.signatureVerificationEnabled) {
    next();
    return;
  }
  verifySignature(req)
    .then((sig) => {
      if (!sig.verified) {
        res.status(400).json({ error: 'Signature verification failed', reason: sig.reason });
        return;
      }
      next();
    })
    .catch(next);
}

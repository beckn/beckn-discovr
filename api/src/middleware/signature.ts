/** Signature verification (configurable). Stub: always success; replace with Beckn HTTP sig later. */

import type { Request, Response, NextFunction } from 'express';
import type { Config } from '../config';

export const verifySignature = async (_req: Request): Promise<{ verified: boolean; reason?: string }> => ({ verified: true });

export async function requireSignature(req: Request, res: Response, next: NextFunction): Promise<void> {
  if (!(req.app.locals as { config?: Config }).config?.signatureVerificationEnabled) return next();
  try {
    const sig = await verifySignature(req);
    if (!sig.verified) return void res.status(400).json({ error: 'Signature verification failed', reason: sig.reason });
    next();
  } catch (e) {
    next(e);
  }
}

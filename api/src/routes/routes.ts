/**
 * API routes (e.g. POST /on_discover); more endpoints can be added here.
 * Schema validation: add validateOnDiscoverRequest from '../middleware/validateSchema' to the chain to re-enable.
 */

import express from 'express';
import discoveryController from '../controller/discoveryController';
import { requireSignature } from '../middleware/signature';

const router = express.Router();

router.post('/on_discover', requireSignature, discoveryController.onDiscover);

export default router;

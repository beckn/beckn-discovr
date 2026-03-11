/** Routes. Add validateOnDiscoverRequest to chain to re-enable schema validation. */

import express from 'express';
import discoveryController from '../controller/discoveryController';
import { requireSignature } from '../middleware/signature';

const router = express.Router();

router.post('/on_discover', requireSignature, discoveryController.onDiscover);

export default router;

#!/usr/bin/env node

require('dotenv').config();
const crypto = require('crypto');
const express = require('express');
const app = express();

// Middleware to capture raw body before JSON parsing
app.use(express.json({
    verify: (req, res, buf) => {
        req.rawBody = buf.toString('utf8');
    }
}));

// Configuration from environment
const SUBSCRIBER_ID = process.env.SUBSCRIBER_ID;
const RECORD_ID = process.env.RECORD_ID;
const PRIVATE_KEY_RAW = process.env.PRIVATE_KEY;

if (!SUBSCRIBER_ID || !RECORD_ID || !PRIVATE_KEY_RAW) {
    console.error("❌ Error: Missing required environment variables.");
    console.error("   Please set SUBSCRIBER_ID, RECORD_ID, and PRIVATE_KEY in .env file");
    process.exit(1);
}

// Format raw base64 Ed25519 private key into PKCS#8 PEM
const formatPrivateKey = (key) => {
    if (key.includes('BEGIN PRIVATE KEY')) return key.replace(/\\n/g, '\n');
    const keyBytes = Buffer.from(key.replace(/\s/g, ''), 'base64');
    if (keyBytes.length === 32) {
        const pkcs8Header = Buffer.from('302e020100300506032b657004220420', 'hex');
        const pkcs8Key = Buffer.concat([pkcs8Header, keyBytes]);
        return `-----BEGIN PRIVATE KEY-----\n${pkcs8Key.toString('base64')}\n-----END PRIVATE KEY-----\n`;
    }
    return `-----BEGIN PRIVATE KEY-----\n${key}\n-----END PRIVATE KEY-----\n`;
};

const privateKey = formatPrivateKey(PRIVATE_KEY_RAW);

/**
 * POST /sign-payload
 * Signs the request body and returns the Authorization header value.
 */
app.post('/sign-payload', (req, res) => {
    try {
        const rawBody = req.rawBody;

        if (!rawBody || rawBody.trim() === '') {
            return res.status(400).send('Request body cannot be empty');
        }

        const created = Math.floor(Date.now() / 1000);
        const expires = created + 3600;

        const hash = crypto.createHash('blake2b512');
        hash.update(rawBody);
        const digest = hash.digest('base64');

        const signingString = `(created): ${created}\n(expires): ${expires}\ndigest: BLAKE-512=${digest}`;
        const signature = crypto.sign(null, Buffer.from(signingString, 'utf8'), privateKey).toString('base64');

        const keyId = `${SUBSCRIBER_ID}|${RECORD_ID}|ed25519`;
        const authHeader = `Signature keyId="${keyId}",algorithm="ed25519",created="${created}",expires="${expires}",headers="(created) (expires) digest",signature="${signature}"`;

        res.setHeader('Content-Type', 'text/plain');
        res.send(authHeader);

    } catch (error) {
        res.status(500).send(error.message);
    }
});

app.get('/health', (_req, res) => res.json({ status: 'ok' }));

const PORT = process.env.PORT || 3030;
app.listen(PORT, () => {
    console.log(`🚀 Signature API Server running on port ${PORT}`);
    console.log(`📝 POST /sign-payload - Sign a payload`);
    console.log(`🔑 SUBSCRIBER_ID: ${SUBSCRIBER_ID}`);
    console.log(`🔑 RECORD_ID: ${RECORD_ID}`);
});

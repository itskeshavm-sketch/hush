/**
 * Re-export of the shared HUSH protocol.
 *
 * The implementation lives in `shared/protocol.js` so the Node relay and the
 * Cloudflare Worker relay cannot drift apart. See that file for the reasoning.
 *
 * This file exists only so `server/src/hub.js` keeps its existing import path.
 */
export * from '../../shared/protocol.js';

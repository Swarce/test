# Protocol index

The full wire protocol (transport, signatures, TLS, auth, data shapes, campaign/fights, events,
relics, challenges, game math) was recovered from the 8.11.0 client and is documented in:

- [`reference-server/docs/PROTOCOL.md`](../reference-server/docs/PROTOCOL.md) — transport, the
  `X-SG-SIG` request/response signatures, the Base64-encoded endpoints, auth, the core data shapes
  and the game formulas.
- [`reference-server/docs/ENDPOINTS.md`](../reference-server/docs/ENDPOINTS.md) — every client
  `*Request` class, its verb, path, body and query params, and whether the reference server
  implements it.

For 8.12.0, a class-name diff of the client found no new game-backend request types versus the
8.11.0 list (the many extra `*Request` names in 8.12.0 are .NET/Unity/PubNub SDK internals, not game
endpoints). The reference server's end-to-end suite passes against content extracted from 8.12.0.

Per-service notes live in `docs/protocol/` as they are written (one file per backend service:
auth, storage, config, store, inbox, events, …). The redirect — how the client is pointed at our
server — is in [`docs/research/01-boot-redirect.md`](research/01-boot-redirect.md).

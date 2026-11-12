# Station Routes Cloudflare Worker

Separate free-first routing deployment. Cloud Run source is retained as a fallback; Android signing configuration and SQLite are not changed by this Worker package.

## API contract
- `GET /health`: public health response.
- `POST /v1/routes`: requires `X-Firebase-AppCheck`; JSON body: `{ "origin": {"latitude": 15.4, "longitude": 44.2}, "destination": {"latitude": 15.5, "longitude": 44.3}, "travelMode": "DRIVE" }`.
- Response: `provider`, `distanceMeters`, `distanceKm`, `durationSeconds`, `encodedPolyline`, `alternativesCount`, `origin`, `destination`, `cacheHit`.

## Android client
`StationRoutesClient` obtains a fresh/cached Firebase App Check token through the installed build-variant provider and sends it in `X-Firebase-AppCheck`. It rejects invalid coordinates and malformed route responses. It has bounded HTTP timeouts and never retries without attestation. The client is an optional service layer; UI/business workflows must explicitly call it and handle offline/provider errors without blocking SQLite operations.

## Security and operations
The Worker verifies App Check JWT signature using Google's published JWKS, RS256, issuer, audience, expiry and exact Firebase Android App ID. There is no Debug-provider bypass in the Worker. Debug builds may use Firebase's registered Debug token; Release uses the configured reCAPTCHA Enterprise Android provider. The configured OSRM profile supports DRIVE only. The in-memory cache is per isolate and best-effort, not globally consistent. The public OSRM demo endpoint has no production SLA and must be replaced or used within its published usage policy before substantial production traffic. Configure Cloudflare platform-level rate limiting before exposing the endpoint to material public traffic.

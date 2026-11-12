# Station Routes Cloudflare Worker

Separate free-first routing deployment. Cloud Run source is retained as a fallback; Android, signing configuration and SQLite are not changed by this Worker package.

## Deployment
GitHub Actions deploys only from `feature/ai-health-sqlite`. Required repository/environment secrets: `CLOUDFLARE_API_TOKEN` (Workers Scripts:Edit) and `CLOUDFLARE_ACCOUNT_ID`. The workflow runs unit tests, Wrangler dry-run, deploys, then performs live smoke checks against the deployed URL.

## API contract
- `GET /health`: public health response.
- `POST /v1/routes`: requires `X-Firebase-AppCheck`; JSON body: `{ "origin": {"latitude": 15.4, "longitude": 44.2}, "destination": {"latitude": 15.5, "longitude": 44.3}, "travelMode": "DRIVE" }`.
- Response fields: `provider`, `distanceMeters`, `distanceKm`, `durationSeconds`, `encodedPolyline`, `alternativesCount`, `origin`, `destination`, `cacheHit`.

The Worker verifies the App Check JWT signature against Google's published JWKS, RS256, issuer, audience, expiry and exact Firebase Android App ID. No Debug-provider bypass exists. Release clients must use the production App Check attestation provider. The configured OSRM profile supports DRIVE only; unsupported travel modes are rejected with 422.

## Operational limitations
The in-memory cache is per isolate and best-effort, not a globally consistent cache. The public OSRM demo endpoint has no production SLA and its usage policy must be respected. Configure Cloudflare platform-level rate limiting before exposing the endpoint to material public traffic. No paid Google API or billing account is required by this Worker.

# Station Routes API — provider-independent Cloud Run service

The routing backend is provider-independent. Its default provider is **OSRM**
(open-source routing engine) and Google Routes is an optional provider/fallback.
The Android application must never treat a straight-line estimate as a road route.

## Provider modes

- `ROUTE_PROVIDER=osrm` (default): uses OSRM driving route geometry, road distance,
  and duration. No Google Maps API key or Google billing account is required.
- `ROUTE_PROVIDER=google`: explicitly opts into Google Routes API and its billing.
- `ROUTE_PROVIDER=auto`: tries OSRM first and uses Google only when an explicit
  Google key is configured and OSRM fails.

`OSRM_BASE_URL` may point to an operator-managed OSRM deployment. The public
`router.project-osrm.org` host is a community demo endpoint, not a production
SLA; its availability, rate limits, and fair-use policy can change. For reliable
commercial/multi-station operation, deploy OSRM with regional OpenStreetMap data
on infrastructure you control, or contract a routing provider. OpenStreetMap
data attribution and ODbL requirements must be respected.

## Security

Every `POST /v1/routes` request requires a valid Firebase App Check token in
`X-Firebase-AppCheck`. Configure Firebase App Check with Play Integrity for
the Android package `com.aistudio.dieselstationsms.kxmpzq`. The service rejects
requests if App Check is not configured; it does not silently expose an open
routing proxy.

Google API keys are never placed in the APK or source control. Only configure
`GOOGLE_ROUTES_API_KEY` in Secret Manager if you deliberately enable Google
routing. App Check and Cloud Run deployment still require a configured project
and authorized deployment access.

## Deploy

From this directory, after authenticating with gcloud and selecting the intended
project, substitute the actual region, Firebase project ID and runtime identity:

```sh
gcloud services enable run.googleapis.com secretmanager.googleapis.com
gcloud run deploy station-routes \
  --source . \
  --region REGION \
  --allow-unauthenticated \
  --service-account ROUTES_RUNTIME_SERVICE_ACCOUNT \
  --set-env-vars FIREBASE_PROJECT_ID=YOUR_FIREBASE_PROJECT_ID,ROUTE_PROVIDER=osrm \
  --max-instances 3 --concurrency 20 --timeout 20
```

To enable optional Google fallback, store its key in Secret Manager and add
`--set-secrets GOOGLE_ROUTES_API_KEY=google-routes-api-key:latest`, then set
`ROUTE_PROVIDER=auto`. Restrict that key to Routes API, configure budget alerts,
and set quotas before enabling it.

## API

`POST /v1/routes`, header `X-Firebase-AppCheck: <valid token>`:

```json
{
  "origin": { "latitude": 15.3694, "longitude": 44.1910 },
  "destination": { "latitude": 15.3800, "longitude": 44.2000 },
  "travelMode": "DRIVE"
}
```

Returns provider name, road distance in meters/kilometers, duration, encoded
route polyline, and origin/destination. The OSRM provider currently supports
driving profile only. No route result is returned when the provider cannot
calculate a valid road route.

## Local verification

```sh
npm install
npm test
npm start
```

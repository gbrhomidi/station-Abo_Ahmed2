# Station Routes API — Google Cloud Run

A server-side Google Routes API proxy. The Google Routes API key is never placed
in the Android APK or source control.

## Security requirements

- Every `POST /v1/routes` request must provide a valid Firebase App Check token
  in `X-Firebase-AppCheck`; missing or invalid attestation is rejected.
- Configure Firebase App Check with Play Integrity for the exact Android package
  `com.aistudio.dieselstationsms.kxmpzq` before deploying for production use.
- Store the Google Routes API key in Secret Manager, restricted to Routes API.
- Do not disable App Check verification. Add quotas, budget alerts and abuse
  monitoring; consider Cloud Armor/API Gateway for additional edge controls.

## Configure the Google Cloud/Firebase project

1. Use an existing Google Cloud project and add Firebase to it.
2. Register the Android app in Firebase with package
   `com.aistudio.dieselstationsms.kxmpzq`.
3. Enable App Check and register Play Integrity.
4. Enable Routes API and create a server key restricted to Routes API.
5. Store the key in Secret Manager as `google-routes-api-key`.
6. Grant the Cloud Run runtime service account
   `roles/secretmanager.secretAccessor` on that secret and only the permissions
   needed to verify App Check tokens.

## Deploy

From this directory, after authenticating with gcloud and selecting the intended
project, substitute the actual region, Firebase project ID and runtime identity:

```sh
gcloud services enable run.googleapis.com routes.googleapis.com secretmanager.googleapis.com
gcloud run deploy station-routes \
  --source . \
  --region REGION \
  --allow-unauthenticated \
  --service-account ROUTES_RUNTIME_SERVICE_ACCOUNT \
  --set-env-vars FIREBASE_PROJECT_ID=YOUR_FIREBASE_PROJECT_ID \
  --set-secrets GOOGLE_ROUTES_API_KEY=google-routes-api-key:latest \
  --max-instances 3 --concurrency 20 --timeout 20
```

Cloud Run is internet-reachable so Android clients can call it; App Check
verification is mandatory. Deployment requires authorized access to the actual
Google Cloud project, billing, Firebase registration and Secret Manager.
This repository change does not deploy cloud resources or create credentials.

## API

`POST /v1/routes`, header `X-Firebase-AppCheck: <valid token>`:

```json
{
  "origin": { "latitude": 15.3694, "longitude": 44.1910 },
  "destination": { "latitude": 15.3800, "longitude": 44.2000 },
  "travelMode": "DRIVE"
}
```

The service asks Google Routes for alternatives and returns the shortest
returned road route, its distance, duration and encoded polyline. It uses
traffic-unaware routing; live traffic availability is region-dependent.

## Local verification

```sh
npm install
npm test
npm start
```

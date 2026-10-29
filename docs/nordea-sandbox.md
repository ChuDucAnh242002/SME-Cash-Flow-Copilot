# Nordea Finnish sandbox integration

The v0.1 adapter uses **Instant Reporting API v4**, not the personal accounts API.
You need a developer app subscribed to Instant Reporting and a sandbox authorization
token with the appropriate account access (`ACCOUNTS_BROADBAND` in Nordea's guide).

Official references, inspected 29 October 2026:

- [Sandbox onboarding](https://support.nordeaopenbanking.com/hc/en-us/articles/7836801760412-Nordea-Developer-Portal-API-Market-Sandbox-guide)
- [Instant Reporting v4](https://documentation.nordeaopenbanking.com/premium/corporate/instant-reporting-v4)
- [Corporate Access Authorization v2](https://documentation.nordeaopenbanking.com/compliance/corporate/authorization-v2)
- [Nordea's official Swagger/Postman repository](https://github.com/NordeaOB/swaggers)

## 1. Prepare access in Nordea's portal

1. Register/log in at <https://developer.nordeaopenbanking.com/>.
2. Create a sandbox application and subscribe it to **Instant Reporting**.
   Corporate Access Authorization is the name of the documented authorization
   interface, not an additional product subscription you must find in the catalogue.
3. Open your app's **Try in API console** (or **Test in API Console** from the
   documentation). Select your sandbox app, the available corporate/Instant
   Reporting flow: **Corporate**, authorization version **v2**, and scope
   **ACCOUNTS_BROADBAND**. If a country selector is offered, select Finland.
   Paste your client secret locally into Nordea's console and click **Get Token**.
   Labels and available flow choices can vary; follow the console's current options.
4. Separately select **Instant Reporting API v4.3**, then **Get Account List**, and send a request.
   Authorization v2 and Instant Reporting v4.3 are different version selectors;
   the reporting endpoint still uses `/corporate/premium/v4`.
   Nordea's sandbox guide specifically identifies the Instant Reporting subscription
   and ACCOUNTS_BROADBAND scope as the requirements to check for subscription errors.
   The official `Premium Corporate Access Authorization API with Accounts API and
   Payments API` Postman collection is an alternative for exercising the documented
   authorization endpoints; its name is not a separate subscription requirement.
5. The console's Authorization information panel confirms authorization but does
   not display the token value in its current implementation. To copy it locally:
   open browser Developer Tools → **Network**, then click **Get Token** again.
   Inspect the response of **POST `/corporate/v2/authorize/token`** and copy only
   `response.access_token`. Alternatively inspect the successful account request's
   `Authorization` request header and remove its `Bearer ` prefix.
   Never export/share a HAR, token, request headers or client secret in chat.
6. Identify your accessible Finnish EUR account's exact `_id`, for example
   `FI6816603001007528-EUR`. This is a documented example, not a guarantee that your
   sandbox user has that account.

The app does **not** implement the interactive consent flow or automatic token
refresh in v0.1. Obtain/refresh the token in the official tools, then replace the
local token file. An expired token gives an explicit error and preserves old data.
Client ID + client secret alone are not assumed to be enough.

## 2. Store credentials locally

Create these files using your editor (contents only, no quotes):

```text
secrets/client-secret     your sandbox app's client secret
secrets/access-token     raw access token, WITHOUT the "Bearer " prefix
```

**No private-key file is needed for the developer sandbox's normal console flow.**
The public developer portal implementation sends the literal header
`Signature: SKIP_SIGNATURE_VALIDATION_FOR_SANDBOX`, rather than generating or
returning a private key. The app now uses that same sandbox-only marker by default.
This is not a production authentication mechanism.

```sh
chmod 600 secrets/client-secret secrets/access-token
```

Leave `NORDEA_PRIVATE_KEY_FILE` empty. An optional RSA signing override remains
available for separately configured testing: set it to a readable PKCS#8 RSA PEM
file path (inside the container). When explicitly configured, an unreadable or invalid
key fails; it never silently falls back to the sandbox marker.

The app container runs as UID/GID 1000. Ensure it can read these files. Do not
commit them, include them in screenshots, or paste them in issues/chat.
Docker's build context excludes the entire secrets directory.

In your ignored `.env`:

```dotenv
BANK_MODE=nordea
NORDEA_CLIENT_ID=your-sandbox-client-id
NORDEA_ACCOUNT_ID=your-accessible-finnish-account-id-EUR
NORDEA_FROM_DATE=2026-01-01
NORDEA_PRIVATE_KEY_FILE=
APP_USER=developer
APP_PASSWORD=choose-a-unique-password-at-least-12-characters
POSTGRES_PASSWORD=choose-a-different-database-password
```

Start a **separate database** from the demo as described in the README. Keep the
workspace/account binding stable. `NORDEA_FROM_DATE` is the earliest transaction
date requested on every manual sync; adjust it to the sandbox's historical data.
It must not be in the future. Requesting older history does not reconstruct an
old balance: the forecast always opens at the latest fetched booked balance.

## 3. Sync

Click **Sync bank account**. The adapter sends authenticated read-only GET requests to:

```text
https://api.nordeaopenbanking.com/corporate/premium/v4/accounts/{account_id}
https://api.nordeaopenbanking.com/corporate/premium/v4/accounts/{account_id}/transactions
```

These are the URLs from Nordea's published sandbox collection. Environment access
depends on the supplied credentials: **use sandbox credentials only**. This MVP is
not approved or configured for production banking.

Each GET includes:

- `Authorization: Bearer ...`
- `X-IBM-Client-Id`, `X-IBM-Client-Secret`
- `X-Nordea-Originating-Host`, `X-Nordea-Originating-Date`
- `Signature: SKIP_SIGNATURE_VALIDATION_FOR_SANDBOX` by default, matching the
  developer console. The optional key-file override instead produces an RSA-SHA256
  signature over `(request-target)`, originating host and GMT date.

Only the selected Finnish EUR account is supported. All transaction pages are fetched
before an atomic database import. Empty pages with continuation keys are followed;
repeated keys and more than 1000 pages fail explicitly. All credentials stay server-side.

Recent Finnish date-window requests can return today's transactions too. Repeated
transactions are deduplicated by account + transaction ID. If an already imported
transaction's amount, booking date or reference changes, the import fails for review
rather than silently corrupting allocations. Fixed booked bank snapshots and
transactions are not guaranteed to be an atomic snapshot at Nordea.

## Kubernetes credentials

Create the namespace and `cashflow-config` as in the README. Then:

```sh
kubectl -n sme-cashflow create secret generic nordea-credentials \
  --from-file=client-secret=secrets/client-secret \
  --from-file=access-token=secrets/access-token
```

Files mount read-only. Recreate/update the Secret when rotating credentials.
Secret volumes can take time to refresh; sync after the update has propagated.
The deployment uses a full Secret volume, not `subPath`, so rotation can propagate.

## Verification boundary

Local tests verify the no-key sandbox marker and optional RSA signatures, and exercise documented
response shapes, empty continuation pages, repeated cursors, unsupported currency
and HTTP 401 errors. No user credentials are available to the tests.

Your first successful authenticated sandbox sync is still an onboarding validation
step. If Nordea's current sandbox differs from its published examples, preserve the
error, inspect only a **redacted** response locally, and adapt the parser/tests.
Never make an integration failure look like successful empty data.

Production access requires separate Nordea onboarding. Instant Reporting specifically
requires Corporate Cash Management and Corporate Netbank arrangements. A sandbox app,
Kubernetes deployment or client ID is not a production banking authorization.

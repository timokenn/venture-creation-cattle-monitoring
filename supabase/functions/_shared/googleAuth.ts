import { CONFIG } from "./config.ts";

/**
 * Google OAuth2 for Edge Functions: mint access tokens from the Firebase
 * service-account JSON (RS256 JWT signed with WebCrypto), cached in memory
 * per scope until near expiry. Shared by FCM (firebase.messaging scope) and
 * the Firestore bridge (datastore scope).
 *
 * Secret required:
 *   SERVICE_ACCOUNT_JSON — full service-account key JSON (with private_key)
 */

interface ServiceAccount {
  client_email: string;
  private_key: string;
  project_id?: string;
}

const cache = new Map<string, { token: string; expiresAtMs: number }>();

function b64url(input: ArrayBuffer | string): string {
  const bytes =
    typeof input === "string"
      ? new TextEncoder().encode(input)
      : new Uint8Array(input);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function serviceAccount(): ServiceAccount {
  const raw = Deno.env.get("SERVICE_ACCOUNT_JSON");
  if (!raw) throw new Error("SERVICE_ACCOUNT_JSON secret must be set");
  return JSON.parse(raw) as ServiceAccount;
}

/** GCP/Firebase project id used in Google REST URLs. */
export function googleProjectId(): string {
  const fromSecret = Deno.env.get("FCM_PROJECT_ID");
  if (fromSecret) return fromSecret;
  return serviceAccount().project_id!;
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

export async function getAccessToken(scope: string): Promise<string> {
  const now = Date.now();
  const hit = cache.get(scope);
  if (hit && hit.expiresAtMs - 60_000 > now) return hit.token;

  const sa = serviceAccount();
  const key = await importPrivateKey(sa.private_key);
  const iat = Math.floor(now / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(
    JSON.stringify({
      iss: sa.client_email,
      scope,
      aud: CONFIG.fcmOAuthAudience,
      iat,
      exp: iat + 3600,
    }),
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(`${header}.${claims}`),
  );
  const assertion = `${header}.${claims}.${b64url(signature)}`;

  const res = await fetch(CONFIG.fcmOAuthAudience, {
    method: "POST",
    headers: { "Content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!res.ok) {
    throw new Error(`Google token exchange failed: ${res.status} ${await res.text()}`);
  }
  const json = (await res.json()) as { access_token: string; expires_in: number };
  cache.set(scope, {
    token: json.access_token,
    expiresAtMs: now + json.expires_in * 1000,
  });
  return json.access_token;
}

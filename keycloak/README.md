# Keycloak

`seshat-realm.json` is imported on first boot (`--import-realm`, strategy
IGNORE_EXISTING: it creates the realm once and never overwrites it afterwards).

**The file cannot carry comments.** Keycloak deserialises it into
`RealmRepresentation` with unknown-field rejection on, so a `_comment` key —
even one nested inside a client — fails the import with
`Unrecognized field ... not marked as ignorable`, and the whole server refuses
to start. That is why the notes live here.

## The decisions in that file

**`use-ui` hangs off the realm default role.** `default-roles-seshat` is
composite and carries `use-ui`, so any account created later in the console can
sign in without anyone remembering to assign a role. `admin` stays explicit —
it gates `POST /reindex`, which re-embeds the whole corpus and costs real money
at the embedding API.

**Public client + PKCE, no secret.** A browser SPA cannot keep a secret: any
client secret shipped to it is readable in the bundle. The code exchange is
protected by PKCE S256 instead, which is the current OAuth guidance for exactly
this case.

**The audience mapper is not optional.** Keycloak does not put a public
client's own id into a token's `aud` claim by default. The gateway checks
`KEYCLOAK_AUDIENCE`, and without this mapper that check rejects every token —
so the mapper is what makes the check enforceable rather than something to
switch off. Delete the mapper and you must also unset `KEYCLOAK_AUDIENCE`,
which then accepts any token this realm issued, including one minted for a
different client.

**Redirect URIs are narrow on purpose.** The app is only ever served under
`/seshat/`, plus `localhost:5173` for `npm run dev`. A wildcard like
`http://localhost:8800/*` would let any page on that origin complete a sign-in
flow.

## Users

| Username | Email | Password | Group | Roles |
|---|---|---|---|---|
| peter | peter@peter.co.nz | `$DangerMouse` | /admins | use-ui, admin |
| theta | theta@peter.co.nz | `Theta` | /readers | use-ui |

Sign in with either the username or the email — `loginWithEmailAllowed` is on.

## Adding a deployment host

The realm is imported once. Editing this file after the first boot changes
nothing; use the admin console (`/seshat/auth/admin/`) to add the new origin to
the `seshat-ui` client's **Valid redirect URIs** and **Valid post logout
redirect URIs**, or `docker compose down -v` to start the realm over.

Set `PUBLIC_URL` in `.env` to match. It becomes the token issuer, and the
gateway compares the `iss` claim to it exactly — a trailing slash or an `http`
where the browser used `https` rejects every token.

## The theme

`themes/seshat/` is mounted into the container rather than baked into the
image, so the edit loop is: change the CSS, `docker compose restart keycloak`,
reload. It extends `keycloak.v2` (PatternFly 5) rather than replacing it, so a
Keycloak upgrade keeps the templates working and only the paint is ours.

To switch the login screen back to stock while debugging, set the realm's
`loginTheme` to `keycloak.v2` in the admin console.

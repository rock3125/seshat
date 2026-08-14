# Installing Seshat on another machine

## On the development machine

```bash
./installer/build-installer.sh
```

Produces `dist/seshat-1.0.0.tar.gz` (about 200 KB) and a `.sha256` beside it.

Source is shipped, not images. `docker save` of the two built images is roughly
900 MB against 200 KB of source, and building on the target produces images
matched to that machine's architecture rather than this one's.

## On the target machine

Needs Docker with the Compose v2 plugin, and nothing else — no JDK, no Node, no
Gradle. Both images build from source inside Docker.

```bash
scp dist/seshat-1.0.0.tar.gz you@myserver:~/
ssh you@myserver

tar xzf seshat-1.0.0.tar.gz
cd seshat-1.0.0
./install.sh --public-url https://myserver --gemini-key AIza...
```

Run it without arguments and it prompts for each one. `./install.sh --help`
lists them all.

The first build compiles Kotlin and runs a Vite build, so expect several
minutes. After that, `install.sh` waits for the stack, verifies that the token
issuer Keycloak advertises matches the one the gateway checks, and prints the
nginx block to add.

### What it does

1. Checks Docker, the Compose plugin, daemon access, free RAM and a free port.
2. Writes `.env` with generated passwords for Postgres and the Keycloak admin
   console, `chmod 600`.
3. Adds your public URL to the realm's redirect URI allow list — **before** the
   first boot, because the realm is imported once and only once.
4. Builds both images and starts the five services.
5. Waits for readiness, checks the issuer, and prints the nginx configuration.

Nothing is installed outside the directory you unpacked into, apart from the
Docker images, containers and two named volumes.

## The nginx configuration

The stack publishes **one port on loopback** and owns exactly one path prefix,
so the host nginx needs exactly one location block. Put it inside the existing
`server { }` for your host:

```nginx
location ^~ /seshat/ {
    proxy_pass http://127.0.0.1:8800;

    proxy_http_version 1.1;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host  $host;
    proxy_set_header X-Forwarded-Port  $server_port;

    proxy_buffering off;          # the chat answer is an SSE stream
    proxy_cache off;
    proxy_read_timeout 10m;
    proxy_send_timeout 10m;

    client_max_body_size 20m;     # the Keycloak admin console posts big forms
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

`install.sh` writes this out with your values already filled in, at
`nginx/seshat.generated.conf`. The annotated reference is `nginx/seshat.conf`.

### Four things that are not optional

**`^~`, not a plain prefix.** nginx evaluates regex locations before prefix
ones, and many sites carry a dotfile guard like `location ~ /\. { deny all; }`.
OIDC discovery lives at `/.well-known/openid-configuration` — which contains
`/.` — so that regex outranks a plain `location /seshat/` and returns 403 for
the one endpoint sign-in cannot start without. Every other path works, which
makes it look like a Keycloak fault rather than a routing one. `^~` stops nginx
before it considers regexes. The dotfile guard still protects the rest of the
site.

**`proxy_buffering off`.** The chat answer is Server-Sent Events, written event
by event as the model produces tokens. With buffering on, nginx accumulates the
whole response and flushes it at the end — the answer arrives complete, all at
once, after a silence indistinguishable from a hung server.

**`proxy_read_timeout 10m`.** A turn with several tool calls runs past the 60s
default, and a timeout mid-stream truncates the answer with no error the UI can
surface.

**`X-Forwarded-Proto`.** Keycloak builds every redirect and the token issuer
from the forwarded scheme. Terminate TLS at nginx and forward `http`, and it
hands the browser `http://` links from an `https://` page — blocked as mixed
content — while the issuer disagrees with the one the gateway checks, so every
sign-in fails with an unexplained 401. Hardcode `https` if the server block is
TLS-only.

### Do not split the prefix

It is tempting to add separate location blocks for `/seshat/api/` and
`/seshat/auth/`. Don't: the nginx *inside* the stack already routes all three,
and `/seshat/auth` in particular must arrive with its path intact or Keycloak's
relative-path routing breaks. Forward the whole prefix and let the inner nginx
sort it out.

## After installing

| | |
|---|---|
| App | `https://myserver/seshat/` |
| Keycloak admin | `https://myserver/seshat/auth/admin/` |
| Library folder | whatever you passed to `--library` (default `./library`) |
| Logs | `docker compose logs -f gateway` |
| Update the corpus | drag documents onto the window (any format), or drop `.txt`/`.md` files in the library folder |

The application user comes from `keycloak/seshat-realm.json`:
`peter@peter.co.nz` / `$DangerMouse` (username `rock`, admin). **Change the
password**, in the Keycloak admin console, before this is reachable from
anywhere real.

## Upgrading

Build a new tarball, unpack it beside the old one, copy the old `.env` across,
and rebuild:

```bash
cp ../seshat-1.0.0/.env .
docker compose up -d --build
```

The Postgres and Qdrant volumes are named after the compose project (`seshat`),
so they survive and the corpus is not re-indexed. The Keycloak realm survives
too — which is why a changed `PUBLIC_URL` has to be applied by hand in the admin
console, under the `seshat-ui` client's **Valid redirect URIs**.

## Uninstalling

```bash
docker compose down          # stop, keep the corpus
docker compose down -v       # stop and delete Postgres, Qdrant and the realm
```

## When it does not work

**Sign-in loops back to the login page.** The redirect URI is not on the
client's allow list. Add `https://myserver/seshat/*` in the admin console under
Clients → seshat-ui → Valid redirect URIs.

**Every request 401s after signing in successfully.** The issuer does not match.
Compare the two:

```bash
curl -s https://myserver/seshat/auth/realms/seshat/.well-known/openid-configuration \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["issuer"])'
grep PUBLIC_URL .env
```

They must differ by nothing at all — not a scheme, not a trailing slash.

**The answer arrives all at once instead of streaming.** `proxy_buffering off`
is missing from the host nginx, or from another proxy in front of it.

**`exit code 137` during the build.** The OOM reaper. The image builds want 2 GB;
add swap or build the images elsewhere and `docker save`/`docker load` them.

**Keycloak restart-loops with `ERROR: Failed to run import` and nothing but the
file path; the gateway logs `AccessDeniedException: /library`.** SELinux. On
Fedora and RHEL it is enforcing by default, and a bind mount out of a home
directory arrives labelled `user_home_t`, which no container may read. Both
symptoms are the same cause and `docker-compose.yml` already carries `:z` on
all three bind mounts to fix it. If you have edited those mounts, put it back:

```yaml
- ${LIBRARY_DIR:-./library}:/library:z
- ./keycloak/seshat-realm.json:/opt/keycloak/data/import/seshat-realm.json:ro,z
- ./keycloak/themes:/opt/keycloak/themes:ro,z
```

Check with `getenforce` and `ls -Z ./library`. Note that `:z` **relabels the
host folder** to `container_file_t`, so point `--library` at a folder of
documents rather than at a system path you would rather leave as it is.

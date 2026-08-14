#!/usr/bin/env bash
#
# Install Seshat on this machine. Run from inside the unpacked tarball.
#
#     ./install.sh --public-url https://myserver --gemini-key AIza...
#
# Everything is optional and prompted for if omitted:
#
#   --public-url URL    how the BROWSER reaches this host, including scheme.
#                       This becomes the Keycloak token issuer, so it must
#                       match exactly what people type. Behind a TLS proxy that
#                       is https://, even though the stack itself serves http.
#   --gemini-key KEY    Google AI Studio key. https://aistudio.google.com/apikey
#   --port N            the port the stack publishes on loopback (default 8800).
#                       Your nginx proxies to this.
#   --library PATH      the folder of text files to index (default ./library)
#   --admin-password P  Keycloak admin console password (default: generated)
#   --db-password P     Postgres password (default: generated)
#   --no-start          write the configuration and stop; do not build or run
#   -y, --yes           accept defaults, never prompt

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

PUBLIC_URL=""; GEMINI_KEY=""; PORT="8800"; LIBRARY=""
ADMIN_PASSWORD=""; DB_PASSWORD=""; START=1; ASSUME_YES=0

RED=$'\033[31m'; CYAN=$'\033[36m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
say()  { printf '%s==%s %s\n' "$CYAN" "$OFF" "$*"; }
warn() { printf '%s!!%s %s\n' "$RED" "$OFF" "$*"; }
die()  { warn "$*"; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --public-url) PUBLIC_URL="${2:-}"; shift 2 ;;
    --gemini-key) GEMINI_KEY="${2:-}"; shift 2 ;;
    --port) PORT="${2:-}"; shift 2 ;;
    --library) LIBRARY="${2:-}"; shift 2 ;;
    --admin-password) ADMIN_PASSWORD="${2:-}"; shift 2 ;;
    --db-password) DB_PASSWORD="${2:-}"; shift 2 ;;
    --no-start) START=0; shift ;;
    -y|--yes) ASSUME_YES=1; shift ;;
    -h|--help) sed -n '2,25p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *) die "unknown option: $1  (try --help)" ;;
  esac
done

ask() {   # ask <prompt> <default> -> echoes the answer
  local prompt="$1" default="${2:-}" reply
  if [ "$ASSUME_YES" = 1 ] || [ ! -t 0 ]; then printf '%s' "$default"; return; fi
  if [ -n "$default" ]; then read -r -p "$prompt [$default]: " reply </dev/tty
  else read -r -p "$prompt: " reply </dev/tty; fi
  printf '%s' "${reply:-$default}"
}

# --- 1. prerequisites ---------------------------------------------------------

say "checking prerequisites"

command -v docker >/dev/null 2>&1 || die \
  "docker is not installed. See https://docs.docker.com/engine/install/"

docker compose version >/dev/null 2>&1 || die \
  "the docker compose plugin is missing (v2). 'docker-compose' v1 will not work."

docker info >/dev/null 2>&1 || die \
  "cannot talk to the Docker daemon. Start it, or add yourself to the docker group:
     sudo systemctl enable --now docker
     sudo usermod -aG docker \$USER   # then log out and back in"

# The gateway image compiles Kotlin and the ui image runs a Vite build; both are
# comfortable in 2 GB but will be killed by the OOM reaper below about 1 GB, and
# that failure looks like an unexplained 'exit code 137' rather than an error.
MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
if [ "$MEM_MB" -gt 0 ] && [ "$MEM_MB" -lt 2000 ]; then
  warn "this machine reports ${MEM_MB}MB of RAM. The image builds may be OOM-killed;"
  warn "2GB or more is recommended. Continuing anyway."
fi

if ss -ltn 2>/dev/null | grep -qE "[^0-9]${PORT}\b"; then
  die "port $PORT is already in use. Pick another with --port N."
fi

# SELinux is enforcing on Fedora/RHEL by default, and every bind mount in this
# stack comes out of a home directory. docker-compose.yml carries `:z` on all
# three for exactly that reason; this only reports the situation, so that if a
# mount ever does fail the cause is already on screen.
if command -v getenforce >/dev/null 2>&1 && [ "$(getenforce)" = "Enforcing" ]; then
  say "SELinux is enforcing — the bind mounts carry :z, so the library folder"
  say "   and keycloak/ will be relabelled to container_file_t"
fi

say "docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '?'), compose plugin present"

# --- 2. configuration ---------------------------------------------------------

echo
say "configuration"

if [ -z "$PUBLIC_URL" ]; then
  PUBLIC_URL="$(ask 'Public URL (how the browser reaches this host, e.g. https://myserver)' "http://$(hostname -f 2>/dev/null || hostname)")"
fi
PUBLIC_URL="${PUBLIC_URL%/}"           # no trailing slash: it is concatenated below
case "$PUBLIC_URL" in
  http://*|https://*) ;;
  *) die "--public-url must start with http:// or https:// (got '$PUBLIC_URL')" ;;
esac
case "$PUBLIC_URL" in
  */seshat|*/seshat/) die "--public-url is the ORIGIN only — drop the /seshat suffix" ;;
esac

if [ -z "$GEMINI_KEY" ]; then
  GEMINI_KEY="$(ask 'Gemini API key (blank to set later; chat stays disabled until you do)' '')"
fi

if [ -z "$LIBRARY" ]; then
  LIBRARY="$(ask 'Folder of text files to index' './library')"
fi
[ -d "$LIBRARY" ] || die "library folder '$LIBRARY' does not exist. Create it, or pass --library PATH."
LIBRARY="$(cd "$LIBRARY" && pwd)"

gen() { head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 24; }

# Reuse the passwords already in .env, if there is one. The explicit
# `|| return 0` matters: written as `[ -f .env ] && sed ...` the function
# returns 1 when there is no .env, and under `set -e` a failing command
# substitution aborts the whole script — silently, mid-configuration, with no
# error printed.
prior() {
  [ -f .env ] || return 0
  sed -n "s/^$1=//p" .env | head -1
}

# Postgres applies POSTGRES_PASSWORD only when it INITIALISES a data directory.
# If the volume already exists — a re-install, an upgrade, a second run of this
# script — the database keeps the password it was created with, and handing the
# gateway a freshly generated one produces an authentication failure that
# HikariCP reports as "Connection is not available, request timed out", with no
# mention of credentials anywhere in the logs. So: never invent a new password
# for an existing volume.
VOLUME_EXISTS=0
docker volume inspect seshat_postgres >/dev/null 2>&1 && VOLUME_EXISTS=1

if [ -z "$DB_PASSWORD" ]; then
  DB_PASSWORD="$(prior POSTGRES_PASSWORD)"
  if [ -n "$DB_PASSWORD" ]; then
    say "reusing the Postgres password from the existing .env"
  elif [ "$VOLUME_EXISTS" = 1 ]; then
    warn "the Postgres volume 'seshat_postgres' already exists, but there is no"
    warn ".env here to read its password from. A new random password would not"
    warn "be accepted by that database. Either:"
    warn "  - pass the original with --db-password P, or"
    warn "  - discard the existing corpus:  docker volume rm seshat_postgres seshat_qdrant"
    die  "refusing to guess."
  else
    DB_PASSWORD="$(gen)"
  fi
fi

if [ -z "$ADMIN_PASSWORD" ]; then
  # Same reasoning, for Keycloak's own store: the bootstrap admin is created on
  # first start and never updated from the environment afterwards.
  ADMIN_PASSWORD="$(prior KEYCLOAK_ADMIN_PASSWORD)"
  [ -n "$ADMIN_PASSWORD" ] || ADMIN_PASSWORD="$(gen)"
fi

# --- 3. write .env ------------------------------------------------------------

if [ -f .env ]; then
  cp .env ".env.backup.$(date +%Y%m%d%H%M%S)"
  say "existing .env backed up"
fi

cat > .env <<EOF
# Written by install.sh on $(date -Is). Holds real secrets — chmod 600.
GEMINI_API_KEY=$GEMINI_KEY
GEMINI_MODEL=gemini-flash-latest
EMBED_MODEL=gemini-embedding-001
EMBED_DIMS=768

LIBRARY_DIR=$LIBRARY
LIBRARY_MIRROR=on
LIBRARY_SCAN_MINUTES=1
# The gateway writes uploaded documents into the library folder, so it runs as
# whoever owns it — otherwise every uploaded file lands owned by root.
LIBRARY_UID=$(stat -c %u "$LIBRARY")
LIBRARY_GID=$(stat -c %g "$LIBRARY")
UPLOAD_MAX_MB=25
UPLOAD_ADMIN_ONLY=on

SESHAT_PORT=$PORT
# Loopback: nginx is the only thing that should reach the stack.
BIND_ADDR=127.0.0.1
PUBLIC_URL=$PUBLIC_URL

POSTGRES_USER=seshat
POSTGRES_PASSWORD=$DB_PASSWORD
POSTGRES_DB=seshat
QDRANT_COLLECTION=seshat

KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=$ADMIN_PASSWORD
EOF
chmod 600 .env
say "wrote .env (chmod 600)"

# --- 4. the realm's redirect URIs --------------------------------------------
#
# Keycloak refuses a sign-in whose redirect_uri is not on the client's allow
# list, and the shipped realm only lists localhost. The realm is imported ONCE,
# on the first boot of a fresh database, so this has to be right before the
# stack ever starts — afterwards it is an admin-console edit.

REALM="keycloak/seshat-realm.json"
if grep -q "$PUBLIC_URL/seshat/\*" "$REALM" 2>/dev/null; then
  say "realm already allows $PUBLIC_URL"
else
  say "adding $PUBLIC_URL to the realm's redirect URIs"
  cp "$REALM" "$REALM.orig"
  python3 - "$REALM" "$PUBLIC_URL" <<'PY'
import json, sys
path, public = sys.argv[1], sys.argv[2]
with open(path) as f:
    realm = json.load(f)
pattern = f"{public}/seshat/*"
for client in realm.get("clients", []):
    if client.get("clientId") != "seshat-ui":
        continue
    uris = client.setdefault("redirectUris", [])
    if pattern not in uris:
        uris.insert(0, pattern)
    # post.logout.redirect.uris is a SINGLE string with '##' separators, not a
    # list — Keycloak's attribute encoding, and it is silently ignored if you
    # write an array here.
    attrs = client.setdefault("attributes", {})
    logout = [u for u in attrs.get("post.logout.redirect.uris", "").split("##") if u]
    if pattern not in logout:
        logout.insert(0, pattern)
    attrs["post.logout.redirect.uris"] = "##".join(logout)
with open(path, "w") as f:
    json.dump(realm, f, indent=2)
    f.write("\n")
PY
fi

if [ "$START" = 0 ]; then
  say "configuration written. Start it yourself with: docker compose up -d --build"
  exit 0
fi

# --- 5. build and start -------------------------------------------------------

echo
say "building images (first run compiles Kotlin and the SPA — several minutes)"
docker compose build

say "starting"
docker compose up -d

# --- 6. wait for readiness ----------------------------------------------------

echo
say "waiting for the stack to come up"
deadline=$(( $(date +%s) + 300 ))
gateway_ok=0; keycloak_ok=0

while [ "$(date +%s)" -lt "$deadline" ]; do
  if [ "$keycloak_ok" = 0 ] && \
     curl -sf -o /dev/null "http://127.0.0.1:$PORT/seshat/auth/realms/seshat/.well-known/openid-configuration" 2>/dev/null; then
    keycloak_ok=1
    say "keycloak ready"
  fi
  if [ "$gateway_ok" = 0 ] && \
     curl -sf -o /dev/null "http://127.0.0.1:$PORT/seshat/api/health" 2>/dev/null; then
    gateway_ok=1
    say "gateway ready"
  fi
  [ "$keycloak_ok" = 1 ] && [ "$gateway_ok" = 1 ] && break
  sleep 5
done

if [ "$keycloak_ok" = 0 ]; then
  warn "Keycloak did not answer within 5 minutes. Look at:  docker compose logs keycloak"
fi
if [ "$gateway_ok" = 0 ]; then
  warn "the gateway did not answer within 5 minutes. Look at:  docker compose logs gateway"
  warn "Its last few lines:"
  docker compose logs --tail 8 gateway 2>&1 | sed 's/^/    /' || true
fi

# The issuer Keycloak actually advertises has to equal the one the gateway
# checks, or every token is rejected with a confusing 401. Verify rather than
# assume: this is the single most common way a reverse-proxied install breaks.
if [ "$keycloak_ok" = 1 ]; then
  ISSUER=$(curl -s "http://127.0.0.1:$PORT/seshat/auth/realms/seshat/.well-known/openid-configuration" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["issuer"])' 2>/dev/null || echo "?")
  EXPECTED="$PUBLIC_URL/seshat/auth/realms/seshat"
  if [ "$ISSUER" = "$EXPECTED" ]; then
    say "token issuer verified: $ISSUER"
  else
    warn "issuer mismatch — Keycloak says '$ISSUER', the gateway expects '$EXPECTED'."
    warn "Sign-in will fail. Fix PUBLIC_URL in .env and: docker compose up -d"
  fi
fi

# --- 7. what to put in nginx --------------------------------------------------

HOST="${PUBLIC_URL#*://}"
SCHEME="${PUBLIC_URL%%://*}"

cat > nginx/seshat.generated.conf <<EOF
# Seshat — generated by install.sh on $(date -Is) for $PUBLIC_URL
#
# Put this INSIDE the existing server{} block for $HOST, then:
#     sudo nginx -t && sudo systemctl reload nginx

# ^~ so that a dotfile guard like \`location ~ /\\. { deny all; }\` — which many
# sites have — cannot win over this and 403 the /.well-known/openid-configuration
# endpoint that sign-in depends on. Regex locations outrank plain prefixes.
location ^~ /seshat/ {
    proxy_pass http://127.0.0.1:$PORT;

    proxy_http_version 1.1;
    proxy_set_header Host              \$host;
    proxy_set_header X-Real-IP         \$remote_addr;
    proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $SCHEME;
    proxy_set_header X-Forwarded-Host  \$host;
    proxy_set_header X-Forwarded-Port  \$server_port;

    # The chat answer is a Server-Sent Events stream. With buffering on, nginx
    # holds the whole answer and delivers it in one lump at the end — which is
    # indistinguishable from a hung server for the ten seconds an answer takes.
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 10m;
    proxy_send_timeout 10m;

    # Keycloak's admin console posts large forms.
    client_max_body_size 20m;
}
EOF

echo
printf '%s\n' "${BOLD}Seshat is up.${OFF}"
cat <<EOF

  ${BOLD}Open${OFF}            $PUBLIC_URL/seshat/
  ${BOLD}Sign in${OFF}         peter@peter.co.nz  /  \$DangerMouse
                  theta@peter.co.nz  /  Theta
  ${BOLD}Keycloak admin${OFF}  $PUBLIC_URL/seshat/auth/admin/
                  admin / $ADMIN_PASSWORD
  ${BOLD}Library${OFF}         $LIBRARY
                  drop .txt/.md files there; indexed within 5 minutes

${BOLD}Add this to your nginx${OFF} — inside the existing server{} block for $HOST.
A copy is in ${DIM}nginx/seshat.generated.conf${OFF}:

$(sed 's/^/    /' nginx/seshat.generated.conf | grep -v '^    #' | grep -v '^    $')

  Then:  sudo nginx -t && sudo systemctl reload nginx

${DIM}The stack listens on 127.0.0.1:$PORT and owns the whole /seshat prefix —
the SPA, /seshat/api (the gateway) and /seshat/auth (Keycloak) — so this is the
only location block needed. Everything else on $HOST is untouched.${OFF}

EOF

if [ -z "$GEMINI_KEY" ]; then
  echo "  ${RED}No Gemini API key was set.${OFF} Search works; chat returns a clear error."
  echo "  Set GEMINI_API_KEY in .env and run: docker compose up -d gateway"
  echo
fi

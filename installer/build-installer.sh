#!/usr/bin/env bash
#
# Package Seshat into a single self-contained tarball that builds and runs on
# another machine.
#
# Run this HERE (the development machine):
#
#     ./installer/build-installer.sh
#     -> dist/seshat-1.0.0.tar.gz  +  .sha256
#
# Then copy the tarball to the target, untar it, and run ./install.sh. The
# target needs Docker with the Compose plugin, and nothing else — no JDK, no
# Node, no Gradle. Both images build from source inside Docker.
#
# Source is shipped, not images: `docker save` of the two built images is about
# 900 MB against roughly 200 KB of source, and a source build on the target
# produces images matched to that machine's architecture rather than this one's.

set -euo pipefail

VERSION="1.0.0"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DIST="$ROOT/dist"
STAGE="$DIST/seshat-$VERSION"

say() { printf '\033[36m==\033[0m %s\n' "$*"; }

say "packaging Seshat $VERSION from $ROOT"

rm -rf "$STAGE"
mkdir -p "$STAGE"

# --- source -------------------------------------------------------------------
# Everything the target needs to build, and deliberately nothing else. The
# excludes are not just about size: shipping gateway/build or ui/node_modules
# would carry THIS machine's compiled output and native binaries into an image
# built on a possibly different architecture.

say "copying gateway (Kotlin source + Gradle wrapper)"
mkdir -p "$STAGE/gateway"
tar -C "$ROOT/gateway" \
    --exclude=build --exclude=.gradle --exclude=.kotlin --exclude='*.log' \
    -cf - . | tar -C "$STAGE/gateway" -xf -

say "copying ui (React source)"
mkdir -p "$STAGE/ui"
tar -C "$ROOT/ui" \
    --exclude=node_modules --exclude=dist --exclude=.env --exclude='.env.*' \
    -cf - . | tar -C "$STAGE/ui" -xf -

say "copying keycloak (realm + theme)"
cp -r "$ROOT/keycloak" "$STAGE/keycloak"

say "copying compose, docs and installer"
cp "$ROOT/docker-compose.yml" "$ROOT/.env.example" "$ROOT/README.md" "$STAGE/"
cp "$HERE/install.sh" "$STAGE/install.sh"
cp "$HERE/README.md" "$STAGE/INSTALL.md"
mkdir -p "$STAGE/nginx"
cp "$HERE/nginx/seshat.conf" "$STAGE/nginx/seshat.conf"
chmod +x "$STAGE/install.sh"

# The library ships with the sample documents so a fresh install has something
# to answer questions about on the first sign-in. install.sh points
# LIBRARY_DIR at whatever the operator chooses; these are only the default.
say "copying the sample library"
cp -r "$ROOT/library" "$STAGE/library"

# A real .env must never travel — it holds the API key and the passwords.
rm -f "$STAGE/.env"

printf '%s\n' "$VERSION" > "$STAGE/VERSION"

# --- the tarball --------------------------------------------------------------
say "writing the archive"
tar -C "$DIST" -czf "$DIST/seshat-$VERSION.tar.gz" "seshat-$VERSION"
rm -rf "$STAGE"

cd "$DIST"
sha256sum "seshat-$VERSION.tar.gz" > "seshat-$VERSION.tar.gz.sha256"

SIZE=$(du -h "seshat-$VERSION.tar.gz" | cut -f1)
cat <<EOF

  dist/seshat-$VERSION.tar.gz   ($SIZE)
  dist/seshat-$VERSION.tar.gz.sha256

  On the target machine:

    scp dist/seshat-$VERSION.tar.gz  you@myserver:~/
    ssh you@myserver
    tar xzf seshat-$VERSION.tar.gz && cd seshat-$VERSION
    ./install.sh --public-url https://myserver --gemini-key AIza...

  install.sh prints the nginx block to add when it finishes; it is also in
  nginx/seshat.conf and INSTALL.md.

EOF

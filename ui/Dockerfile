# Build the SPA, then serve it — and reverse-proxy the gateway and Keycloak —
# from one nginx. One container is the public entrance for the whole stack, so
# the browser only ever talks to one origin.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
# `npm ci` needs a lockfile; fall back to `npm install` for a first build in a
# fresh checkout that has not generated one yet.
RUN npm ci 2>/dev/null || npm install
COPY . .

# Vite inlines these at BUILD time — they end up in the JS bundle, not in the
# container's environment, so changing one means rebuilding the image.
#
# All three are origin-relative on purpose: the SPA, the gateway and Keycloak
# are all behind the nginx below, so the bundle never needs to know the public
# hostname. Deploying behind a different domain therefore needs no rebuild.
ARG VITE_BASE=/seshat/
ARG VITE_KEYCLOAK_URL=/seshat/auth
ARG VITE_KEYCLOAK_REALM=seshat
ARG VITE_KEYCLOAK_CLIENT=seshat-ui
ENV VITE_BASE=$VITE_BASE \
    VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL \
    VITE_KEYCLOAK_REALM=$VITE_KEYCLOAK_REALM \
    VITE_KEYCLOAK_CLIENT=$VITE_KEYCLOAK_CLIENT
RUN npm run build

FROM nginx:1.27-alpine
# Into a /seshat subdirectory, matching the public path exactly. nginx can then
# serve it with a plain `root` and no `alias` — alias combined with try_files is
# a long-standing nginx footgun whose failure mode is a 404 on every deep link.
COPY --from=build /app/dist /usr/share/nginx/html/seshat
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80

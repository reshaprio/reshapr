# Build stage: run on the native builder platform since SvelteKit output is arch-independent
# and running vite build under linux/arm64 QEMU emulation intermittently hangs with SIGILL.
FROM --platform=$BUILDPLATFORM node:25-alpine@sha256:bdf2cca6fe3dabd014ea60163eca3f0f7015fbd5c7ee1b0e9ccb4ced6eb02ef4 AS build

WORKDIR /app

# Install dependencies first (layer caching)
COPY package.json package-lock.json* ./
RUN npm ci

# Copy source and build
COPY . .
RUN npm run build

# Prune dev dependencies and build-time native addons for a leaner, arch-independent production image
RUN npm prune --omit=dev \
    && rm -rf /app/node_modules/.vite \
    && rm -rf /app/node_modules/@hugeicons \
    && rm -rf /app/node_modules/@rolldown \
    && rm -rf /app/node_modules/lightningcss* \
    && rm -rf /app/node_modules/vite/node_modules/lightningcss* \
    && rm -rf /app/node_modules/@tailwindcss/oxide*

# -----------------------------------------------------------
# Production stage: Red Hat UBI9 minimal
# -----------------------------------------------------------
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.8-1793862054@sha256:4b79c93106c14ad1c234907f83441ff0e4f8d24e78426a36e159098e9f7e6fa5

# Install Node.js 22 via microdnf
RUN echo -e "[nodejs]\nname=nodejs\nstream=22\nprofiles=\nstate=enabled" > /etc/dnf/modules.d/nodejs.module \
    && microdnf install --setopt install_weak_deps=0 nodejs -y \
    && microdnf clean all \
    && rm /var/lib/rpm/rpmdb.sqlite \
    && rm -rf /mnt/rootfs/var/cache/* /mnt/rootfs/var/log/dnf* /mnt/rootfs/var/log/yum.*

WORKDIR /app

# Copy the built application and production node_modules
COPY --from=build /app/build ./build
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/package.json ./
COPY --from=build /app/server.js ./

# SvelteKit adapter-node configuration
ENV NODE_ENV=production
ENV PORT=3333
ENV HOST=0.0.0.0

EXPOSE 3333

# Run the SvelteKit server
CMD ["node", "server.js"]

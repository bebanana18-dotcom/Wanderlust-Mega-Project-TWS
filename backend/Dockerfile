# # Stage 1
# FROM node:21 AS backend-builder

# # setup the working dir
# WORKDIR /app

# # packages install
# RUN npm i

# # code
# COPY . .



# # tests
# RUN npm run test

# # Stage 2
# FROM node:21-slim

# # setup the working dir
# WORKDIR /app

# # copy the above stage as compressed
# COPY --from=backend-builder /app .

# COPY .env.docker .env

# # Port
# EXPOSE 8080

# # App
# CMD ["npm", "start"]


# ------------------- Stage 1: Build & Test -----------------------------------
FROM node:21-alpine AS backend-builder

WORKDIR /app

# Copy package files FIRST for layer caching
# (so a code change doesn't reinstall 200 packages)
COPY package*.json ./

# Install ALL deps including devDependencies (needed for jest/babel)
#RUN npm ci
#

# Copy source AFTER install
COPY . .

# Run tests without --watchAll (that flag hangs forever in CI, congrats to whoever wrote that)
#RUN npm run test -- --watchAll=false --passWithNoTests 
#

# ------------------- Stage 2: Production Image --------------------------------
FROM node:21-alpine

WORKDIR /app

# Copy package files
COPY package*.json ./

# Install ONLY production dependencies — no jest, no babel, no nodemon
RUN npm ci --omit=dev

# Copy source code only (not node_modules from stage 1)
COPY --from=backend-builder /app .

# Copy env file
COPY .env.docker .env

# Non-root user — because running as root in prod is a lifestyle choice with consequences
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN chown -R appuser:appgroup /app
USER appuser

# Port
EXPOSE 8080

# Health check — so Kubernetes knows if the server silently died
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/ || exit 1

# Use node directly — nodemon is for development, not for prod containers
CMD ["node", "server.js"]
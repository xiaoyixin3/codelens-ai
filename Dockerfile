FROM golang:1.27-alpine AS go-build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/codelens-api ./cmd/api \
 && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/codelens-worker ./cmd/worker \
 && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/codelens-migrate ./cmd/migrate

FROM node:24-alpine AS legacy-build
WORKDIR /app
COPY package.json package-lock.json ./
COPY apps ./apps
COPY packages ./packages
RUN npm ci --ignore-scripts
COPY tsconfig.json tsup.config.ts ./
COPY scripts ./scripts
RUN npm run build:legacy
RUN npm prune --omit=dev

FROM node:24-alpine AS runtime
ENV NODE_ENV=production
WORKDIR /app
COPY --from=go-build --chown=node:node /out/codelens-api /out/codelens-worker /out/codelens-migrate ./
COPY --from=legacy-build --chown=node:node /app/package.json /app/package-lock.json ./
COPY --from=legacy-build --chown=node:node /app/node_modules ./node_modules
COPY --from=legacy-build --chown=node:node /app/dist ./dist
COPY --chown=node:node infra/migrations ./infra/migrations
USER node
EXPOSE 3000
CMD ["/app/codelens-api"]

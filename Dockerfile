FROM maven:3.9-eclipse-temurin-17-alpine AS java-build
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM node:24-alpine AS tooling-build
WORKDIR /app
COPY package.json package-lock.json ./
COPY apps ./apps
COPY packages ./packages
RUN npm ci --ignore-scripts
COPY tsconfig.json tsup.config.ts ./
COPY scripts ./scripts
RUN npm run build:legacy
RUN npm prune --omit=dev

FROM eclipse-temurin:17-jre-alpine AS runtime
RUN apk add --no-cache nodejs \
 && addgroup -S codelens \
 && adduser -S -G codelens codelens
ENV NODE_ENV=production
WORKDIR /app
COPY --from=java-build --chown=codelens:codelens /src/target/codelens-ai.jar ./codelens-ai.jar
COPY --from=tooling-build --chown=codelens:codelens /app/package.json /app/package-lock.json ./
COPY --from=tooling-build --chown=codelens:codelens /app/node_modules ./node_modules
COPY --from=tooling-build --chown=codelens:codelens /app/dist ./dist
COPY --chown=codelens:codelens infra/migrations ./infra/migrations
USER codelens
EXPOSE 3000
CMD ["java", "-Dcodelens.mode=api", "-jar", "/app/codelens-ai.jar"]

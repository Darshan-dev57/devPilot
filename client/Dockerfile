# ---- Build stage ----
FROM node:22-alpine AS build
WORKDIR /app
# Empty by default = same-origin API calls through the reverse proxy.
# For split hosting, pass e.g. --build-arg NEXT_PUBLIC_API_BASE_URL=https://api.example.com
ARG NEXT_PUBLIC_API_BASE_URL=""
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
COPY package.json package-lock.json ./
RUN npm ci
COPY . ./
RUN npm run build

# ---- Run stage (Next.js standalone) ----
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /app/public ./public
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
RUN chown -R appuser:appgroup /app
USER appuser
EXPOSE 3000
CMD ["node", "server.js"]

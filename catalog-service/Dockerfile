FROM node:20.18.1-alpine3.20
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY src ./src
USER node
EXPOSE 8082
CMD ["node", "src/server.js"]

FROM node:20.18.1-alpine3.20
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev --no-audit --no-fund
COPY src ./src
USER node
EXPOSE 8081
CMD ["node", "src/server.js"]

FROM node:22.14-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html vite.config.js ./
COPY src ./src
EXPOSE 5173
CMD ["npm", "run", "dev"]

FROM node:20-alpine

RUN apk add --no-cache python3 make g++

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY server.js .

RUN mkdir -p data uploads/images uploads/audio uploads/video uploads/thumb

EXPOSE 3000

CMD ["node", "server.js"]

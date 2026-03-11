FROM node:24.3.0-alpine
RUN mkdir -p /opt/api-service
COPY . /opt/api-service
WORKDIR /opt/api-service
RUN npm install
RUN npm run build
EXPOSE 3000
CMD ["npm", "run", "start"]

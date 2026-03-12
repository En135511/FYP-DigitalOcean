FROM caddy:2-alpine

WORKDIR /srv
COPY simple-test-ui/ /srv/
COPY deploy/digitalocean/Caddyfile /etc/caddy/Caddyfile

EXPOSE 80

FROM nginx:1.27-alpine

WORKDIR /srv

COPY simple-test-ui/ /srv/
COPY deploy/render/nginx.default.conf.template /etc/nginx/conf.d/default.conf.template
COPY deploy/render/start-ui.sh /usr/local/bin/start-ui.sh
RUN chmod +x /usr/local/bin/start-ui.sh

EXPOSE 10000

CMD ["/usr/local/bin/start-ui.sh"]

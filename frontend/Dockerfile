FROM nginx:1.27-alpine

RUN printf '%s\n' \
    '<!doctype html>' \
    '<html lang="en">' \
    '<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>VoD Platform</title></head>' \
    '<body><main><h1>VoD Platform</h1><p>Frontend skeleton. React/Vite implementation is deferred.</p></main></body>' \
    '</html>' \
    > /usr/share/nginx/html/index.html

EXPOSE 80

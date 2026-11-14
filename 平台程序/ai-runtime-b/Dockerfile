# sharp 只用于测试造图，生产 Runtime 无第三方依赖。基础镜像固定 digest，升级时显式复核。
FROM node:24.19.0-alpine3.24@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43
WORKDIR /app
RUN rm -rf /usr/local/lib/node_modules/npm /opt/yarn-* \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/yarn /usr/local/bin/yarnpkg \
    && mkdir -p /data && chown node:node /data && chmod 0700 /data
COPY --chown=node:node package.json ./
COPY --chown=node:node src ./src
USER node
ENV NODE_ENV=production
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD node -e "fetch('http://127.0.0.1:8081/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "src/main.mjs"]

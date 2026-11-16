# ============================================================
# Elasticsearch 8.11.4 + IK 中文分词插件
# 构建: docker compose build elasticsearch
# 说明: IK 安装包已预先下载到 docker/elasticsearch/analysis-ik-8.11.4.zip，
#       离线安装避免容器构建时访问 GitHub；如安装失败可改用官方镜像 + standard 分词降级。
# ============================================================
FROM elasticsearch:8.11.4

COPY --chown=elasticsearch:elasticsearch docker/elasticsearch/analysis-ik-8.11.4.zip /tmp/ik.zip

RUN elasticsearch-plugin install --batch file:///tmp/ik.zip \
    && rm -f /tmp/ik.zip

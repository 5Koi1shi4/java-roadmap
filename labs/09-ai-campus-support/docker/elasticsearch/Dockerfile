FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.5
RUN set -eu; \
    for attempt in $(seq 1 3); do \
      if bin/elasticsearch-plugin install --batch analysis-smartcn; then exit 0; fi; \
      if [ "$attempt" -eq 3 ]; then exit 1; fi; \
      rm -rf plugins/analysis-smartcn; \
      sleep $((attempt * 5)); \
    done

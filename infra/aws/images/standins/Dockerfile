# The live feeds' stand-in, for the AWS test. images.sh builds it with load/standins as the context and the seed's
# folder (data/load, for the station ids) as the context "seed".
FROM python:3.14.7-slim@sha256:51dafde81dbdb6ebde285137a295cf18a47ca95234fe388a343719cb97305b3d
COPY standins.py /standins/standins.py
COPY --from=seed stations.txt /seed/stations.txt
USER 10001:10001

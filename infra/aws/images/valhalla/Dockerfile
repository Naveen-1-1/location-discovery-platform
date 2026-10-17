# Valhalla with the Massachusetts tiles inside, for the AWS test, whose tasks share no disk. images.sh builds it with
# infra/docker/valhalla as the context and the tiles' folder (data/valhalla) as the context "tiles".
FROM ghcr.io/valhalla/valhalla:3.9.0@sha256:511c095b8caf393dccceb8b519ec96b6f85a0166b2288ba014a8a748acc5a63c
COPY --from=tiles valhalla_tiles.tar admins.sqlite /data/
COPY serve.sh /serve.sh
CMD ["bash", "/serve.sh", "4"]

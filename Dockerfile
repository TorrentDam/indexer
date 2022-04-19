FROM eclipse-temurin:17-focal

COPY out/indexer/assembly.dest/out.jar /opt/indexer.jar

ENTRYPOINT java -jar /opt/indexer.jar nats $NATS_SERVER
FROM openjdk:16-oraclelinux8

ARG MQTT_TEST_SUITE_VERSION
ENV MQTT_TEST_SUITE_VERSION=$MQTT_TEST_SUITE_VERSION

RUN mkdir -p /opt/simplematter-mqtt-test-suite
WORKDIR /opt/simplematter-mqtt-test-suite
COPY target/scala-3.1.3/simplematter-mqtt-test-suite-assembly-${MQTT_TEST_SUITE_VERSION}.jar ./
COPY src/docker/resources/*.sh ./

RUN chmod +x *.sh

CMD ["/opt/simplematter-mqtt-test-suite/simplematter-mqtt-test-suite.sh"]


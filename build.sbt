//import AssemblyKeys._

scalaVersion := "3.1.1"
organization := "io.simplematter"
name := "simplematter-mqtt-test-suite"

val scalaTestVersion = "3.2.9"
val nettyVersion = "4.1.65.Final"
val pahoVersion = "1.2.5"
val hazelcastVersion = "4.2.2"

libraryDependencies ++= Seq(
  //Misc
  "ch.qos.logback"        % "logback-classic"      %  "1.0.13",
  "dev.zio"               %% "zio"                 % "1.0.14",
  "dev.zio"               %% "zio-json"            % "0.2.0-M4",

  //MQTT
  "io.netty"              % "netty-common"         % nettyVersion,
  "io.netty"              % "netty-handler"        % nettyVersion,
  "io.netty"              % "netty-codec-mqtt"     % nettyVersion,

  //Kafka
  "dev.zio"               %% "zio-kafka"           % "0.17.5",

  //HTTP
  "io.d11"                %% "zhttp"               % "1.0.0.0-RC27",

  //Data distribution
  "com.hazelcast"         % "hazelcast-all"        % hazelcastVersion,

  //Config
  "com.typesafe"          % "config"               % "1.4.1",
  "dev.zio"               %% "zio-config-typesafe" % "2.0.4",
  "dev.zio"               %% "zio-config-magnolia" % "2.0.4",

  //Test dependencies
  "org.testcontainers"    % "testcontainers"                  % "1.15.3"         % "test",
  "org.eclipse.paho"      % "org.eclipse.paho.client.mqttv3"  % pahoVersion      % "test",
  "org.scalactic"         %% "scalactic"                      % scalaTestVersion % "test",
  "org.scalatest"         %% "scalatest-flatspec"             % scalaTestVersion % "test",
  "org.scalatest"         %% "scalatest"                      % scalaTestVersion % "test",
  "org.scalatest"         %% "scalatest-core"                 % scalaTestVersion % "test",
  "org.scalatestplus"     %% "scalacheck-1-15"                % "3.2.9.0"        % "test",
  "dev.zio"               %% "zio-test"                       % "1.0.12"         % "test",
)

//this works as of 2021-10-28 - 'sbt run' picks the main class correctly and doesn't complain about multiple main classes
Compile / run / mainClass := Some("io.simplematter.mqtttestsuite.TestSuiteRunner")
//this doesn't work - 'sbt assembly' complains about multiple main classes and JAR isn't runnable
assembly / mainClass := Some("io.simplematter.mqtttestsuite.TestSuiteRunner")

ThisBuild / assemblyMergeStrategy  := {
  case PathList("META-INF", "io.netty.versions.properties", xs @ _*) => MergeStrategy.first
  case PathList("META-INF", "INDEX.LIST", xs @ _*) => MergeStrategy.discard
  case PathList("META-INF", "MANIFEST.MF", xs @ _*) => MergeStrategy.discard
  case PathList("LICENSE", xs @ _*) => MergeStrategy.discard
  case PathList("NOTICE", xs @ _*) => MergeStrategy.discard
  case PathList("module-info.class", xs @ _*) => MergeStrategy.discard
  case PathList("META-INF", "NOTICE", xs @ _*) => MergeStrategy.discard
  case PathList(xs @ _*) if Seq("pom.properties", "pom.xml").contains(xs.last) => MergeStrategy.discard
  case x => MergeStrategy.deduplicate
}

publish / skip := true


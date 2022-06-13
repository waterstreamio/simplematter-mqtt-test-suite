# Simplematter MQTT Test Suite

Functional and performance test suite for MQTT+Kafka combined systems (such as [Waterstram](waterstream.io)) 

Consists of the two main components:

- Test runner (`io.simplematter.mqtttestsuite.TestSuiteRunner`) - they actually run the given test scenario. 
  May be multiple instances to generate higher load. Terminate as soon as the test completes.
  Can report the node-local test stats in console or over HTTP.
- Stats aggregator (`io.simplematter.mqtttestsuite.TestSuiteStatsAggregator`) - optional component, 
  aggregate the statistics from the test runners and report it in console or over HTTP.

Test runner and stats aggregator form a Hazelcast cluster and share the data structures to coordinate the effort. 

General configuration (stats gathering settings, MQTT connection settings, Kafka connection settings)
are provided through the environment variables - see the complete list of configuration options in `src/main/resources/application.conf`.

Test scenario is configured in a separate file using [HOCON syntax](https://github.com/lightbend/config), 
it's provided via `MQTT_LOAD_SCENARIO_CONFIG_INLINE` (inline) or `MQTT_LOAD_SCENARIO_CONFIG_PATH` (file location).
Following scenarios are supported:

- `mqttPublishOnly`
- `mqttToKafka`
- `kafkaToMqtt`
- `mqttToMqtt`

See the examples in `toolbelt/local/scenario`.

## Unit tests 

All tests:

    sbt test

Specific test:

    sbt 'testOnly **.MessageGeneratorSpec'

    sbt 'testOnly **.QuantileApproxSpec -- -z calculate'

As per 2021-10-15 can't run tests in Idea - it doesn't play nicely with zio-config derivation in Scala 3. 
There are some compiler issues in `MqttTestSuiteConfig` on `private val automaticDescription = descriptor[MqttTestSuiteConfig]`

## Preparation 

### Local Mosquitto MQTT broker

You can use it for `mqttPublishOnly` and `mqttToMqtt` scenarios. 
Not suitable out of the box for scenarios that involve Kafka. 

In one terminal start Mosquitto as an example MQTT broker:

    toolbelt/runTestMosquitto.sh

In another run the publishing test suite:

    toolbelt/runMqttPublishOnly1.sh

In yet another - subscribing client to visualise the messages:

    mosquitto_sub -h localhost -p 1883 -t '#'

### Local Waterstream+Kafka

Place the license file `waterstream.license` into the `toolbelt/waterstream` folder.

Start Waterstream+Kafka to have what to test:

    cd toolbelt/waterstream
    docker-compose up

## Running locally 

Run the aggregator:

    toolbelt/local/testAgg1.sh

or with Docker:

    toolbelt/local/testAggDocker1.sh

Statistics summary is periodically printed in the console and also can be retrieved on HTTP port `8080`:

    curl localhost:8080

Now in yet another terminals start the test scenario:

    toolbelt/local/testRun1.sh toolbelt/scenario/mqttToKafka.conf

or with Docker:

    toolbelt/local/runMqttToKafkaDocker1.sh toolbelt/scenario/mqttToKafka.conf

You may customize `MQTT_LOAD_EXPECTED_RUNNER_NODES_COUNT` variable in the script 
to run multiple nodes of test scenarios 
and make them wait for the quorum - actual tests will start when the specified number
of the test nodes is up.

It will run for the `MQTT_LOAD_DURATION_SECONDS` and then shut down. The aggregator still keeps the
combined statistics until it's re-started - so, if you run the test runner again without restarting
the aggregator, you'll get statistics from the two runs.

`testRun1.sh` by default connects to the Waterstream and Kafka running locally.
If you want to run this script against the remote machine customize `MQTT_LOAD_SERVER` and `MQTT_LOAD_KAFKA_BOOTSTRAP_SERVERS`.

It's possible to run just the test runner `testRun1.sh`, without the aggregator `testAgg1.sh` - 
it just means that each test runner is on its own and statistics won't be aggregated. 
You also wan't be able to see the issues summary at `<hostname>:8080/issues` when the test runner quits.
If you want to serve the statistics over HTTP port you can uncomment `export MQTT_LOAD_STATS_PORT=8080` in the 
corresponding script.

### Reporting 

If reporting over HTTP is enabled, by default you can get statistics summary on `<aggregator_hostname>:8080` 
and issues report on `<aggregator_hostname>:8080/issues`

You can see overal status of the test in `<aggregator_hostname>:8080/conclusion` - it will be one of the following:
- `InProgress` - not started yet, ramping up or running
- `Success` - test completed successfully
- `Fail` - test have failed, most likely indicating that MQTT broker either has defects or stressed beyond the limits 
- `Warn` - test completed without obvious errors, but results are suspicious and need manual examination 
- `TechicalError` - couldn't complete the test due to the technical error

## Docker

Test suite is published on Dockerhub as `simplematter/simplematter-mqtt-test-suite`. 
As for 13.06.2022 latest published version is `0.0.4`.

## Running on GCP

Download `account.json` from GCP Account into the `toolbelt/gcp_gke` directory.
You will have to create a [service account on GCP](https://cloud.google.com/iam/docs/creating-managing-service-account-keys) first if you don't have one.
Once you have a Service Account, you can get `account.json` this way:
- Open GCP Console in the web browser
- Go to "IAM & admin / Service Accounts" section
- Click "Actions / Create Key" for the specific service account, choose JSON key
- Download .json file, rename it to `account.json`
- Copy to the `gcp_gke` directory

Customize the configuration and start the GCP GKE cluster:

    cd toolbelt/gcp_gke/gke_cluster
    cp config.auto.tfvars.example config.auto.tfvars
    # customize the config with some text editor
    vim config.auto.tfvars 
    terraform init
    terraform apply --auto-approve

Wait until it completes, it will leave the K8s credentials file `kube_config` in the `gke_cluster` folder.
It may occasionally expire - in this case just re-run `terraform apply --auto-approve` to have it renewed.

In another console configure and run the tests:

    cd toolbelt/gcp_gke/test_suite
    cp config.auto.tfvars.example config.auto.tfvars
    #customize scenario - optionally
    cp scenario/mqttToKafka.conf scenario/mqttToKafkaCustom1.conf
    vim scenario/mqttToKafkaCustom1.conf
    # customize the config with some text editor
    # Be sure to customize MQTT and Kafka access parameter and point to the scenario file you want to use
    vim config.auto.tfvars 
    terraform init
    terraform apply --auto-approve

When the script completes its output contains `mqtt-test-aggregator-endpoint-url` which can be used
to track the progress of the test and test results. Test runner nodes quit immediately after completing the test,
stats aggregator remains until shot down explicitly. After analysing statistics, when aggregator isn't needed 
any more, you can shut down the remaining test infrastructure:

    cd toolbelt/gcp_gke/test_suite
    terraform destroy --auto-approve

and GKE cluster:

    cd toolbelt/gcp_gke/gke_cluster
    terraform destroy --auto-approve
